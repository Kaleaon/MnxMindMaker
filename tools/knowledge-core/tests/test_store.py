import copy
import json
import math
import tempfile
import unittest
from pathlib import Path

from mnx_knowledge.store import Store, ValidationError


SEED = Path(__file__).resolve().parents[1] / "examples" / "seed.json"


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / "graph.sqlite3")
        self.store = Store(self.path)
        self.seed = json.loads(SEED.read_text())

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def seed_graph(self):
        self.store.submit(self.seed)
        self.store.review(self.seed["id"], "test-reviewer", True, "Synthetic fixture")

    def proposal(self, changes, identifier="p2"):
        return {"schema_version": 1, "id": identifier, "base_revision": self.store.revision,
                "contributor": {"id": "test-adapter", "method": "test"}, "changes": changes}

    def test_pending_is_not_visible_until_review(self):
        self.store.submit(self.seed)
        self.assertEqual(self.store.snapshot()["concepts"], [])
        self.store.review(self.seed["id"], "reviewer", True, "demo")
        self.assertEqual(self.store.revision, 1)
        self.assertEqual(len(self.store.snapshot()["concepts"]), 3)

    def test_persistence_and_audit(self):
        self.seed_graph()
        self.store.close()
        self.store = Store(self.path)
        self.assertEqual(self.store.revision, 1)
        history = self.store.history()
        self.assertEqual(history[0]["body"], self.seed)
        self.assertEqual(history[0]["reviewer"], "test-reviewer")
        self.assertEqual(history[0]["revision"], 1)

    def test_missing_evidence_reference_rejects_entire_proposal(self):
        self.seed["changes"]["claims"][0]["evidence"][0]["source_id"] = "missing"
        with self.assertRaises(ValidationError):
            self.store.submit(self.seed)
        self.assertEqual(self.store.history(), [])
        self.assertEqual(self.store.snapshot()["concepts"], [])

    def test_retry_is_idempotent_and_id_reuse_is_rejected(self):
        self.seed_graph()
        self.assertEqual(self.store.submit(self.seed)["status"], "accepted")
        other = copy.deepcopy(self.seed)
        other["changes"]["concepts"][0]["label"] = "Different"
        with self.assertRaises(ValidationError):
            self.store.submit(other)
        self.assertEqual(len(self.store.history()), 1)

    def test_reject_does_not_change_graph(self):
        self.store.submit(self.seed)
        self.store.review(self.seed["id"], "reviewer", False, "Needs better evidence")
        self.assertEqual(self.store.revision, 0)
        with self.assertRaises(ValidationError):
            self.store.review(self.seed["id"], "reviewer", True, "Second review")

    def test_stale_review_is_atomic(self):
        self.store.submit(self.seed)
        second = self.proposal({"concepts": [{"id": "other", "label": "Other"}]})
        self.store.submit(second)
        self.store.review(second["id"], "reviewer", True, "First")
        with self.assertRaises(ValidationError):
            self.store.review(self.seed["id"], "reviewer", True, "Stale")
        self.assertEqual(self.store.revision, 1)
        self.assertEqual([item["id"] for item in self.store.snapshot()["concepts"]], ["other"])
        self.assertEqual(self.store.history()[0]["status"], "pending")

    def test_concepts_are_editable_and_old_version_is_in_history(self):
        self.seed_graph()
        changes = {"concepts": [{"id": "demo:apple", "label": "Apple fruit", "aliases": ["Apple"]}]}
        p = self.proposal(changes)
        self.store.submit(p)
        self.store.review(p["id"], "reviewer", True, "Clarify label")
        apple = next(item for item in self.store.snapshot()["concepts"] if item["id"] == "demo:apple")
        self.assertEqual(apple["label"], "Apple fruit")
        self.assertEqual(self.store.history()[0]["body"]["changes"]["concepts"][0]["label"], "Apple")

    def test_claims_are_immutable_and_corrections_are_linked(self):
        self.seed_graph()
        old = copy.deepcopy(self.seed["changes"]["claims"][0])
        old["confidence"] = 0.1
        with self.assertRaises(ValidationError):
            self.store.submit(self.proposal({"claims": [old]}))
        old["id"] = "correction"
        old["supersedes"] = ["demo:apple-is-fruit"]
        old["contradicts"] = ["demo:apple-is-fruit"]
        p = self.proposal({"claims": [old]})
        self.store.submit(p)
        self.store.review(p["id"], "reviewer", True, "Retain both")
        self.assertEqual(len(self.store.snapshot()["claims"]), 3)

    def test_invalid_vectors_and_nonfinite_values(self):
        for values in ([1], [0, 0], [math.nan, 1], [math.inf, 1], [True, 1]):
            with self.subTest(values=values):
                p = copy.deepcopy(self.seed)
                p["changes"]["observations"][0]["embedding"]["values"] = values
                with self.assertRaises(ValidationError):
                    self.store.submit(p)

    def test_similarity_excludes_other_spaces(self):
        self.seed_graph()
        extra = copy.deepcopy(self.seed["changes"]["observations"][0])
        extra["id"] = "different-model"
        extra["embedding"] = {"space_id": "other-model", "values": [0.9, 0.2]}
        p = self.proposal({"spaces": [{"id": "other-model", "model": "another", "dimensions": 2}],
                           "observations": [extra]})
        self.store.submit(p)
        self.store.review(p["id"], "reviewer", True, "New space")
        matches = self.store.similar("demo:apple-shape")
        self.assertEqual([m["observation_id"] for m in matches], ["demo:pear-shape"])
        self.assertAlmostEqual(matches[0]["cosine_similarity"], 0.7439207780445167)

    def test_large_vector_values_do_not_overflow_similarity(self):
        for item in self.seed["changes"]["observations"][:2]:
            item["embedding"]["values"] = [1e308, 1e308]
        self.seed_graph()
        self.assertAlmostEqual(self.store.similar("demo:apple-shape")[0]["cosine_similarity"], 1)

    def test_unknown_and_malformed_fields_fail_cleanly(self):
        for mutate in (
            lambda p: p.update({"unknown": True}),
            lambda p: p["changes"]["claims"][0].update({"evidence": []}),
            lambda p: p["changes"]["concepts"].append(p["changes"]["concepts"][0]),
            lambda p: p["changes"]["observations"][0].update({"modality": []}),
            lambda p: p["changes"]["claims"][0].update({"confidence": True}),
        ):
            with self.subTest(mutate=mutate):
                p = copy.deepcopy(self.seed)
                mutate(p)
                with self.assertRaises(ValidationError):
                    self.store.submit(p)

    def test_boolean_is_not_an_idempotent_numeric_value(self):
        self.seed_graph()
        changed = copy.deepcopy(self.seed)
        changed["schema_version"] = True
        with self.assertRaises(ValidationError):
            self.store.submit(changed)

    def test_immutable_features_distinguish_boolean_from_number(self):
        self.seed["changes"]["observations"][0]["features"]["measurement"] = 1
        self.seed_graph()
        changed = copy.deepcopy(self.seed["changes"]["observations"][0])
        changed["features"]["measurement"] = True
        with self.assertRaises(ValidationError):
            self.store.submit(self.proposal({"observations": [changed]}))


if __name__ == "__main__":
    unittest.main()
