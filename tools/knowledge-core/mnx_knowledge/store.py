"""Dependency-free local prototype. Review acceptance is not a truth guarantee."""

import json
import math
import sqlite3
import time
import uuid
from datetime import datetime, timezone


COLLECTIONS = ("concepts", "sources", "spaces", "claims", "observations")
MODALITIES = {"text", "image", "audio", "video", "chemical", "shape", "experience"}
SOURCE_KINDS = {"paper", "book", "video", "image", "audio", "dataset", "experience", "web"}


class ValidationError(ValueError):
    """A proposal cannot safely be applied to the current graph."""


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False)


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def string(value, name):
    require(isinstance(value, str) and bool(value.strip()), f"{name} must be a nonempty string")


def fields(value, required, optional=()):
    require(isinstance(value, dict), "expected an object")
    require(set(required) <= value.keys(), f"missing fields: {set(required) - value.keys()}")
    require(not value.keys() - set(required) - set(optional), "unknown fields")


def number(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


class Store:
    def __init__(self, path="mnx-knowledge.sqlite3"):
        self.db = sqlite3.connect(path, timeout=10)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS entities (
                collection TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL,
                PRIMARY KEY (collection, id)
            );
            CREATE TABLE IF NOT EXISTS proposals (
                id TEXT PRIMARY KEY, body TEXT NOT NULL, status TEXT NOT NULL,
                reviewer TEXT, reason TEXT, reviewed_at TEXT, revision INTEGER
            );
            CREATE TABLE IF NOT EXISTS metadata (
                id INTEGER PRIMARY KEY CHECK(id = 1), revision INTEGER NOT NULL,
                graph_id TEXT NOT NULL, created_at INTEGER NOT NULL
            );
        """)
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO metadata VALUES(1, 0, ?, ?)",
                            (str(uuid.uuid4()), int(time.time() * 1000)))

    def close(self):
        self.db.close()

    @property
    def revision(self):
        return self.db.execute("SELECT revision FROM metadata WHERE id = 1").fetchone()[0]

    def snapshot(self):
        # One read transaction keeps revision and all collections consistent.
        with self.db:
            self.db.execute("BEGIN")
            metadata = self.db.execute("SELECT * FROM metadata WHERE id = 1").fetchone()
            result = {"schema_version": 1, "revision": metadata["revision"],
                      "graph_id": metadata["graph_id"], "created_at": metadata["created_at"]}
            for collection in COLLECTIONS:
                result[collection] = [json.loads(row[0]) for row in self.db.execute(
                    "SELECT body FROM entities WHERE collection = ? ORDER BY id", (collection,)
                )]
        return result

    def _validate(self, proposal):
        fields(proposal, ("schema_version", "id", "base_revision", "contributor", "changes"))
        require(type(proposal["schema_version"]) is int and proposal["schema_version"] == 1,
                "unsupported schema_version")
        string(proposal["id"], "proposal.id")
        require(type(proposal["base_revision"]) is int and proposal["base_revision"] == self.revision,
                "base_revision is stale; export the current graph and submit a new proposal id")
        fields(proposal["contributor"], ("id", "method"), ("model",))
        for name, value in proposal["contributor"].items():
            string(value, f"contributor.{name}")
        changes = proposal["changes"]
        fields(changes, (), COLLECTIONS)
        require(any(changes.values()), "proposal must contain changes")
        graph = {collection: {row["id"]: json.loads(row["body"]) for row in self.db.execute(
            "SELECT id, body FROM entities WHERE collection = ?", (collection,)
        )} for collection in COLLECTIONS}
        for collection, items in changes.items():
            require(isinstance(items, list), f"{collection} must be an array")
            seen = set()
            for item in items:
                require(isinstance(item, dict), "each change must be an object")
                string(item.get("id"), f"{collection}.id")
                require(item["id"] not in seen, f"duplicate {collection} id")
                seen.add(item["id"])
                old = graph[collection].get(item["id"])
                # Concepts are editable; evidence and assertions stay immutable.
                require(collection == "concepts" or old is None or canonical(old) == canonical(item),
                        f"{collection}/{item['id']} is immutable; use a new id")
                graph[collection][item["id"]] = item

        def reference(collection, identifier):
            string(identifier, f"{collection} reference")
            require(identifier in graph[collection], f"unknown {collection} reference: {identifier}")

        for item in changes.get("concepts", []):
            fields(item, ("id", "label"), ("aliases",))
            string(item["label"], "concept.label")
            aliases = item.get("aliases", [])
            require(isinstance(aliases, list), "aliases must be an array")
            for alias in aliases:
                string(alias, "alias")
        for item in changes.get("sources", []):
            fields(item, ("id", "kind", "title", "uri"), ("content_sha256",))
            string(item["kind"], "source.kind")
            require(item["kind"] in SOURCE_KINDS, "unsupported source kind")
            string(item["title"], "source.title")
            string(item["uri"], "source.uri")
            if "content_sha256" in item:
                digest = item["content_sha256"]
                require(isinstance(digest, str) and len(digest) == 64 and
                        all(c in "0123456789abcdef" for c in digest), "invalid SHA-256")
        for item in changes.get("spaces", []):
            fields(item, ("id", "model", "dimensions"))
            string(item["model"], "space.model")
            require(type(item["dimensions"]) is int and item["dimensions"] > 0,
                    "dimensions must be a positive integer")
        for item in changes.get("claims", []):
            fields(item, ("id", "subject_id", "predicate", "object", "evidence", "confidence"),
                   ("supersedes", "contradicts", "derived_from"))
            reference("concepts", item["subject_id"])
            string(item["predicate"], "claim.predicate")
            obj = item["object"]
            require(isinstance(obj, dict) and len(obj) == 1 and
                    set(obj) <= {"concept_id", "literal"},
                    "object must contain exactly concept_id or literal")
            if "concept_id" in obj:
                reference("concepts", obj["concept_id"])
            else:
                require(obj["literal"] is not None, "literal cannot be null")
            require(number(item["confidence"]) and 0 <= item["confidence"] <= 1,
                    "confidence must be finite and between zero and one")
            evidence = item["evidence"]
            require(isinstance(evidence, list) and bool(evidence), "claims need evidence")
            for citation in evidence:
                fields(citation, ("source_id", "locator"))
                reference("sources", citation["source_id"])
                string(citation["locator"], "evidence.locator")
            for link in ("supersedes", "contradicts", "derived_from"):
                if link in item:
                    require(isinstance(item[link], list), f"{link} must be an array")
                    for identifier in item[link]:
                        require(identifier != item["id"], "claim cannot reference itself")
                        reference("claims", identifier)
        for item in changes.get("observations", []):
            fields(item, ("id", "concept_id", "modality", "source_id", "locator", "features"),
                   ("embedding",))
            reference("concepts", item["concept_id"])
            reference("sources", item["source_id"])
            string(item["modality"], "observation.modality")
            require(item["modality"] in MODALITIES, "unsupported modality")
            string(item["locator"], "observation.locator")
            require(isinstance(item["features"], dict), "features must be an object")
            if "embedding" in item:
                embedding = item["embedding"]
                fields(embedding, ("space_id", "values"))
                reference("spaces", embedding["space_id"])
                values = embedding["values"]
                require(isinstance(values, list) and
                        len(values) == graph["spaces"][embedding["space_id"]]["dimensions"] and
                        all(number(value) for value in values), "invalid vector dimensions or values")
                require(any(value != 0 for value in values), "zero vector has no cosine similarity")
        try:
            canonical(proposal)
        except (ValueError, TypeError) as exc:
            raise ValidationError("proposal must contain finite JSON values") from exc

    def submit(self, proposal):
        require(isinstance(proposal, dict), "proposal must be an object")
        string(proposal.get("id"), "proposal.id")
        try:
            body = canonical(proposal)
        except (ValueError, TypeError) as exc:
            raise ValidationError("proposal must contain finite JSON values") from exc
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            previous = self.db.execute("SELECT body, status FROM proposals WHERE id = ?",
                                       (proposal["id"],)).fetchone()
            if previous:
                require(previous["body"] == body, "proposal id reused with different content")
                return {"id": proposal["id"], "status": previous["status"]}
            self._validate(proposal)
            self.db.execute("INSERT INTO proposals(id, body, status) VALUES (?, ?, 'pending')",
                            (proposal["id"], body))
        return {"id": proposal["id"], "status": "pending"}

    def review(self, identifier, reviewer, accept, reason):
        string(reviewer, "reviewer")
        string(reason, "review reason")
        require(type(accept) is bool, "accept must be a boolean")
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            row = self.db.execute("SELECT * FROM proposals WHERE id = ?", (identifier,)).fetchone()
            require(row is not None, "unknown proposal")
            require(row["status"] == "pending", "proposal has already been reviewed")
            proposal = json.loads(row["body"])
            revision = None
            if accept:
                self._validate(proposal)
                for collection, items in proposal["changes"].items():
                    for item in items:
                        self.db.execute("INSERT INTO entities VALUES (?, ?, ?) "
                                        "ON CONFLICT(collection, id) DO UPDATE SET body = excluded.body",
                                        (collection, item["id"], canonical(item)))
                revision = self.revision + 1
                self.db.execute("UPDATE metadata SET revision = ? WHERE id = 1", (revision,))
            status = "accepted" if accept else "rejected"
            self.db.execute("UPDATE proposals SET status = ?, reviewer = ?, reason = ?, "
                            "reviewed_at = ?, revision = ? WHERE id = ?",
                            (status, reviewer, reason, datetime.now(timezone.utc).isoformat(),
                             revision, identifier))
        return {"id": identifier, "status": status, "revision": revision}

    def history(self):
        return [{**dict(row), "body": json.loads(row["body"])} for row in self.db.execute(
            "SELECT * FROM proposals ORDER BY rowid"
        )]

    def similar(self, observation_id, limit=5):
        require(type(limit) is int and limit > 0, "limit must be a positive integer")
        observations = {item["id"]: item for item in self.snapshot()["observations"]}
        require(observation_id in observations, "unknown observation")
        query = observations[observation_id].get("embedding")
        require(query is not None, "observation has no embedding")

        def unit(values):
            # Scaling prevents overflow when finite vector components are large.
            scale = max(abs(value) for value in values)
            scaled = [value / scale for value in values]
            norm = math.sqrt(math.fsum(value * value for value in scaled))
            return [value / norm for value in scaled]

        vector = unit(query["values"])
        matches = []
        for item in observations.values():
            other = item.get("embedding")
            if item["id"] == observation_id or not other or other["space_id"] != query["space_id"]:
                continue
            score = math.fsum(a * b for a, b in zip(vector, unit(other["values"])))
            matches.append({"observation_id": item["id"], "concept_id": item["concept_id"],
                            "cosine_similarity": max(-1.0, min(1.0, score))})
        return sorted(matches, key=lambda match: (-match["cosine_similarity"], match["observation_id"]))[:limit]
