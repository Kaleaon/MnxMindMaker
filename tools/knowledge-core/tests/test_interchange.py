import json
import unittest
from pathlib import Path

from mnx_knowledge.interchange import export_mnxj, node_id
from mnx_knowledge.store import Store


class InterchangeTests(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.seed = json.loads((Path(__file__).resolve().parents[1] / "examples/seed.json").read_text())
        self.store.submit(self.seed)
        self.store.review(self.seed["id"], "demo-reviewer", True, "Synthetic fixture")

    def tearDown(self):
        self.store.close()

    def test_native_envelope_and_all_edge_references(self):
        result = export_mnxj(self.store.snapshot(), self.store.history())
        self.assertEqual(result["schema"], {"family": "mnx.interchange", "version": {"major": 1, "minor": 0}})
        graph = result["graph"]
        ids = {node["id"] for node in graph["nodes"]}
        self.assertEqual(len(ids), 11)
        self.assertEqual(len(ids), len(graph["nodes"]))
        for edge in graph["edges"]:
            self.assertIn(edge["from_node_id"], ids)
            self.assertIn(edge["to_node_id"], ids)

    def test_full_entities_and_provenance_survive_projection(self):
        result = export_mnxj(self.store.snapshot(), self.store.history())
        nodes = {node["id"]: node for node in result["graph"]["nodes"]}
        for collection, items in self.seed["changes"].items():
            for item in items:
                node = nodes[node_id(collection, item["id"])]
                self.assertEqual(json.loads(node["attributes"]["knowledge_record"]), item)
                self.assertEqual(node["attributes"]["knowledge_proposal_id"], self.seed["id"])
                review = json.loads(node["attributes"]["knowledge_review"])
                self.assertEqual(review["reviewer"], "demo-reviewer")
                self.assertTrue(all(isinstance(value, str) for value in node["attributes"].values()))
                if collection == "observations":
                    self.assertEqual(node["dimensions"], {})

    def test_graph_identity_stays_stable_and_ids_are_namespaced(self):
        first = export_mnxj(self.store.snapshot(), self.store.history())
        second = export_mnxj(self.store.snapshot(), self.store.history())
        self.assertEqual(first["graph"]["id"], second["graph"]["id"])
        self.assertEqual(first["graph"]["created_at"], second["graph"]["created_at"])
        self.assertNotEqual(node_id("concepts", "a/b"), node_id("concepts", "a%2Fb"))
        self.assertNotEqual(node_id("concepts", "same"), node_id("sources", "same"))

    def test_unreviewed_claims_are_excluded(self):
        self.store.submit({"schema_version": 1, "id": "pending", "base_revision": 1,
                           "contributor": {"id": "another", "method": "test"},
                           "changes": {"concepts": [{"id": "unreviewed", "label": "Unreviewed"}]}})
        result = export_mnxj(self.store.snapshot(), self.store.history())
        self.assertFalse(any(node["label"] == "Unreviewed" for node in result["graph"]["nodes"]))
