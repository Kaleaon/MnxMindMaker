"""Project accepted knowledge into MnxMindMaker's existing mnx.interchange 1.0."""

import json
import time
from urllib.parse import quote

from .store import COLLECTIONS, canonical


def node_id(collection, identifier):
    return f"mnx.knowledge/{collection}/{quote(identifier, safe='')}"


def export_mnxj(snapshot, history, name="Shared Knowledge"):
    """Keep complete entity JSON on nodes; vectors are not named mind dimensions.

    The snapshot and history are a projection, not a writable core database.
    Android edits do not sync back to the core in version 0.1.
    """
    nodes, edges = [], []
    provenance = {}
    for entry in history:
        if entry["status"] != "accepted":
            continue
        for collection, items in entry["body"]["changes"].items():
            for item in items:
                provenance[(collection, item["id"])] = entry

    def edge(from_collection, from_id, to_collection, to_id, label, strength=1.0):
        edges.append({"id": f"mnx.knowledge/edge/{len(edges)}",
                      "from_node_id": node_id(from_collection, from_id),
                      "to_node_id": node_id(to_collection, to_id),
                      "label": label, "strength": strength})

    for collection in COLLECTIONS:
        for item in snapshot[collection]:
            entry = provenance[(collection, item["id"])]
            attributes = {
                "knowledge_kind": collection.rstrip("s"),
                "knowledge_record": canonical(item),
                "knowledge_proposal_id": entry["id"],
                "knowledge_contributor": canonical(entry["body"]["contributor"]),
                "knowledge_review": canonical({key: entry[key] for key in
                                                ("reviewer", "reason", "reviewed_at", "revision")}),
                "knowledge_review_status": "accepted",
            }
            label, description, dimensions = item["id"], "", {}
            node_type = {"concepts": "KNOWLEDGE", "sources": "MEMORY", "spaces": "CUSTOM",
                         "claims": "BELIEF", "observations": "MEMORY"}[collection]
            if collection == "concepts":
                label = item["label"]
                description = "Concept; see connected claims and observations."
            elif collection == "sources":
                label = item["title"]
                description = item["uri"]
                attributes["source_reference"] = item["uri"]
                attributes["source_type"] = item["kind"]
            elif collection == "spaces":
                label = item["model"]
                description = f"Embedding space: {item['dimensions']} dimensions"
            elif collection == "claims":
                label = item["predicate"]
                description = json.dumps(item["object"], ensure_ascii=False, allow_nan=False)
                dimensions = {"confidence": item["confidence"]}
                edge("concepts", item["subject_id"], "claims", item["id"], "has_claim")
                if "concept_id" in item["object"]:
                    edge("claims", item["id"], "concepts", item["object"]["concept_id"],
                         item["predicate"], item["confidence"])
                for citation in item["evidence"]:
                    edge("claims", item["id"], "sources", citation["source_id"],
                         f"evidence: {citation['locator']}")
                for link in ("supersedes", "contradicts", "derived_from"):
                    for identifier in item.get(link, []):
                        edge("claims", item["id"], "claims", identifier, link)
            elif collection == "observations":
                label = f"{item['modality']}: {item['id']}"
                description = canonical(item["features"])
                attributes["modality"] = item["modality"]
                edge("observations", item["id"], "concepts", item["concept_id"], "observes")
                edge("observations", item["id"], "sources", item["source_id"],
                     f"observed_in: {item['locator']}")
                if "embedding" in item:
                    edge("observations", item["id"], "spaces", item["embedding"]["space_id"], "encoded_in")
            index = len(nodes)
            nodes.append({"id": node_id(collection, item["id"]), "label": label, "type": node_type,
                          "description": description, "x": 80 + (index % 4) * 240,
                          "y": 80 + (index // 4) * 180, "parent_id": None,
                          "attributes": attributes, "is_expanded": True, "dimensions": dimensions})
    return {
        "schema": {"family": "mnx.interchange", "version": {"major": 1, "minor": 0}},
        "compatibility": {"min_reader_version": {"major": 1, "minor": 0},
                          "forward_compat_extensions": [], "migration_hint": "knowledge attributes are strings"},
        "metadata": {"source": "mnx.knowledge.core.v1", "knowledge_revision": str(snapshot["revision"])},
        "graph": {"id": snapshot["graph_id"], "name": name, "created_at": snapshot["created_at"],
                  "modified_at": int(time.time() * 1000), "nodes": nodes, "edges": edges},
    }
