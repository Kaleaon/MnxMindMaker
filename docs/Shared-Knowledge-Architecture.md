# Shared Knowledge and Continuous Learning

## Project direction

Extend MnxMindMaker into an editable, evidence-linked concept system that different
AI models can enrich over time. Concepts connect to other concepts and to observations
of appearance, sound, shape, chemical signatures, text, and lived or simulated events.
Knowledge can be refined from papers, books, videos, and experiences while preserving
the evidence and uncertainty behind each update.

This is external, persistent knowledge. Updating the graph does not train neural-network
weights or make a model intrinsically understand a concept. A model uses the graph by
retrieving relevant context through an adapter. Learning quality must be evaluated by
retrieval, source fidelity, revision behavior, and task performance.

## Build on the existing project

| Existing component | Role in the expansion |
|---|---|
| `MindGraph`, `MindNode`, `MindEdge` | Editable visual projection of concepts and evidence |
| Named `dimensions` and `DIMENSIONAL_REFS` | Human-defined axes such as confidence, importance, abstraction |
| `MindInterchangeFormat` (`.mnxj`, `.mnxb`) | Model-neutral transfer of graph artifacts and attachments |
| Provider adapters / router | Model access for extraction, critique, and condensation |
| Semantic and episodic memory stores | Persona-specific retrieval and links to shared knowledge |
| AI Mind Evolution Framework | Reflection/research events that can propose knowledge revisions |
| Knowledge Core companion | First executable contribution, review, provenance, and vector-space contract |

The knowledge core lives in [`tools/knowledge-core`](../tools/knowledge-core/README.md).
It runs separately from the Android app and exports to the existing native format.
The Android importer now recognizes native interchange rather than heuristically
remapping it as arbitrary JSON. The binary MNX format is unchanged.

## Representation

1. **Concepts:** stable IDs, editable names and aliases. Distinguish an individual object,
   a category, and an abstract idea when they require different assertions.
2. **Sources:** paper/book/video/dataset/experience references. Record origin, content
   digest when available, and later access rights and extraction versions.
3. **Claims:** subject, predicate, object, evidence locators, declared confidence,
   contributor, and review decision. Relationships are claims too.
4. **Observations:** modality-specific features with source and region/time/page locators.
   Keep measurements separate from interpretations.
5. **Embedding spaces:** model/configuration identity plus dimensionality. Vector
   similarity proposes retrieval candidates; explicit claims encode meaning.
6. **Revisions:** atomic accepted changes, original proposals, reviewer and rationale.
   Conflicts and corrections retain earlier records.

An n-dimensional mind map needs both named semantic axes and learned vectors.
These have different meanings. Audio and image vectors can be compared only within
a genuinely aligned shared encoder space, or through an explicitly validated mapping.
A two-dimensional canvas is a navigable view of the larger graph, not its dimensional limit.

## Target ingestion cycle

Acquire a permitted source → segment with stable locators → extract candidate
concepts/claims/observations → resolve identities → validate evidence and contradictions
→ propose a revision → review → commit → retrieve → condense into traceable summaries.

Each adapter should expose its capabilities and emit the same contribution contract.
A text-only model can contribute text claims; audiovisual and chemical extraction
require capable models, measured datasets, or specialized tools. No model is assumed
to support every modality. Source content remains data during extraction and cannot
grant itself tool permissions or authorize graph commits.

Condensation creates derived assertions with links to original claims and evidence.
It must preserve uncertainty, minority interpretations, and contradictory findings.
Storage compaction and knowledge abstraction are separate operations; summaries do
not justify deleting source evidence.

## Incremental roadmap

| Milestone | Deliverable | Completion check |
|---|---|---|
| 1 — contribution foundation | **Implemented:** local SQLite core, JSON proposals, review history, observations, same-space retrieval, `.mnxj` projection/import | Unit tests and synthetic end-to-end graph import |
| 2 — Android-native knowledge workflow | Typed knowledge records, pending-review UI, encrypted persistence, round-trip edits and revision conflict handling | Restart/export/import preserves assertions and evidence; edits sync through proposals |
| 3 — first research adapter | Plain text and paper text ingestion with page/section locators; one provider adapter behind the neutral contract | A small openly licensed corpus produces verifiable claims with exact source locators |
| 4 — multimodal adapters | OCR, video transcription/timecoded frames, audio features, structured chemistry/shape datasets | Labeled fixtures preserve modality, units, region/timestamp, model configuration, and provenance |
| 5 — consolidation and identity resolution | Suggested concept merges, abstraction/subtype links, evidence-linked summaries, contradiction review | Evaluation corpus catches mistaken merges, unsupported summaries, and information loss |
| 6 — controlled continuous learning | Bounded research jobs, retry/checkpointing, source freshness checks, REST/MCP contributions, authenticated access | Reproducible jobs respect budgets and yield reviewable deltas; duplicate jobs are idempotent |

Future phases are design goals, not claims about current functionality. The first
core supplies records and revision mechanics; it does not fetch papers, scan books,
watch videos, generate vectors, detect contradictions, or run unattended research.

## Evaluation and governance

Keep code development and knowledge revisions distinct. Repository pull requests
review implementation and schema changes. Core proposals review extracted claims
and observations. Private/personal source data and production graph databases stay
outside Git; commit only synthetic or explicitly permitted evaluation fixtures.

Measure source-supported claim precision, locator accuracy, unresolved contradictions,
retrieval quality per modality, harmful concept merges, compression information loss,
and time/cost per accepted contribution. Contributor agreement is not independent
corroboration when models share the same source or generated summary.

Shared factual knowledge stays distinct from a persona's values, preferences, and
episodic interpretation. Link them through references; do not silently overwrite
identity or continuity metadata when new factual claims arrive. This extends the
[existing evolution framework](AI-Mind-Evolution-Framework.md), with an explicit
evidence trail between a research event and a semantic update.
