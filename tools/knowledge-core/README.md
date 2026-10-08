# MnxMindMaker Knowledge Core

A working local companion for developing MnxMindMaker's shared concept graph.
Python 3.11+; standard library only; no model account or package installation required.
The repository's Apache-2.0 license applies to this module.

## Run the demonstration

From the repository root:

```bash
cd tools/knowledge-core
python -m mnx_knowledge init
python -m mnx_knowledge submit examples/seed.json
python -m mnx_knowledge history
python -m mnx_knowledge review demo-seed-v1 --reviewer maintainer --decision accept --reason "Reviewed synthetic demonstration"
python -m mnx_knowledge export
python -m mnx_knowledge similar demo:apple-shape
python -m mnx_knowledge export-mnxj --name "Shared Knowledge Demo" > knowledge.mnxj
```

Use `python3` if that is your Python command. The seed expects a new database
at revision zero. Use `--db another.sqlite3` **before** the subcommand to create
a fresh database or select an existing one. Run these commands once for the demo;
review decisions cannot be changed after they are recorded.

The seed is entirely synthetic, including its two-dimensional vectors. Its audio
and chemistry entries are explicitly unmeasured placeholders. It demonstrates
representation and workflow, not scientific extraction or learned understanding.

In the Android app, use **Import → Load file**, select `knowledge.mnxj`, parse it,
then open it in the mind map. Pasting the JSON also works. The importer recognizes
the native `mnx.interchange` envelope and preserves node IDs, evidence attributes,
dimensions, and edges instead of applying generic JSON heuristics.

## What works in v0.1

- SQLite persistence for concepts, sources, claims, observations, and embedding spaces.
- A versioned JSON proposal format that any model adapter can produce.
- Pending proposals, explicit acceptance/rejection, review reasons, and complete proposal history.
- Atomic graph revisions; stale contributions must be rebased explicitly.
- Editable concept labels/aliases; immutable assertions, evidence records, and observations.
- Evidence locators on every claim; corrections can link to superseded or conflicting claims.
- Text, image, audio, video, chemical, shape, and experience observation records.
- Cosine retrieval within a registered model space; vectors are supplied by adapters.
- Native `.mnxj` projection into MnxMindMaker's existing editable canvas.

Acceptance records a review decision. It does not certify a claim as true.
Confidence is contributor-supplied, not independently calibrated.

## Contribute through any AI

1. Read the current graph with `export`, including its `revision`.
2. Ask an AI adapter to extract candidate concepts, assertions, and observations
   from a source, keeping page numbers, timestamps, or measurement locators.
3. Have it emit the JSON contract in [PROTOCOL.md](PROTOCOL.md).
4. Submit a file, or pipe JSON to `python -m mnx_knowledge submit -`.
5. Review with `history`; accept or reject through the separate `review` command.
6. Export the updated snapshot and provide relevant context to the next model.

This is a file/CLI contribution interface. REST, MCP, provider-specific extraction,
OCR, transcription, scheduling, and automatic summarization are future work.
Local reviewer names are audit labels, not authenticated identities; the core is
a single-owner tool, not a network service or a multi-user access-control system.

If review fails because `base_revision` is stale, export the current graph,
re-evaluate the proposed changes, and submit a new proposal ID with the new base.
The old pending proposal can be rejected with a recorded reason.

## Canvas projection and persistence

The core database is authoritative for this module. `.mnxj` is a graph snapshot
for inspection and editing in the Android app. Each entity becomes a node with
its complete JSON in the string attribute `knowledge_record`, and its latest
accepting proposal/review in adjacent attributes. Evidence and observation
relationships become edges. Full proposal history stays in the SQLite database
and is available through `history`; the canvas projection is not a backup.

Embeddings remain in observation attributes, associated with an explicit model
space. They are distinct from MnxMindMaker's named semantic dimensions such as
confidence or importance. Equal vector lengths do not make different spaces compatible.
Similarity is a retrieval aid; it never automatically creates a factual relationship.

The projection creates a separate shared-knowledge graph. Importing or editing
it does not merge into existing persona graphs or sync changes back to the core.
An Android-native review queue and bidirectional synchronization are roadmap items.
Keep the SQLite database for accepted data **and** the revision/review history.

## Tests

```bash
python -m unittest discover -s tests -v
```

See [the project roadmap](../../docs/Shared-Knowledge-Architecture.md) for the
staged path from this core to continuous multimodal learning.
