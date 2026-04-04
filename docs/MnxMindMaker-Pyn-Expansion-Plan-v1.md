# MnxMindMaker Pyn Expansion Plan v1

## Purpose

This document maps the Pyn kernel / memory / portability vision onto the current
MnxMindMaker codebase so the app can evolve from a graph editor into a
continuity-aware identity portability tool.

## Repository Fit Summary

MnxMindMaker already provides core primitives needed for portable identity
systems:

- Typed nodes for identity, memory, affect, values, beliefs, relationships, and custom domains.
- Per-node named dimensions persisted in MNX (`DIMENSIONAL_REFS`).
- Import/export support for MNX plus text, JSON, Markdown, DOCX, and CSV.
- Android canvas/editor UI.
- Provider settings for Anthropic, OpenAI, and Gemini.

The architecture is already close to the target. Expansion should focus on
kernel guarantees, continuity memory quality, drift controls, live-state
representation, and transfer packet quality.

## Current Support (Already in Repository)

### 1) Typed mind domains
`NodeType` currently supports core categories and now includes runtime-oriented
extensions:

- `IDENTITY`
- `MEMORY`
- `KNOWLEDGE`
- `AFFECT`
- `PERSONALITY`
- `BELIEF`
- `VALUE`
- `RELATIONSHIP`
- `STATE`
- `DRIFT_RULE`
- `CUSTOM`

### 2) N-dimensional storage
`MindNode.dimensions` plus `DimensionMapper` gives domain-specific default axes
and extensibility for optional axes.

### 3) Import pipeline + mapping
`DataMapper` already supports structured expansion attributes used by
continuity-style modeling (for example `protection_level`, `memory_class`,
`drift_type`, `raw_record`, `interpretation`, and related metadata).

### 4) Handoff packet baseline
`BootPacketGenerator` already exports slices that match the core transfer
requirement:

- identity kernel slice
- live state slice
- warning slice
- memory slice
- drift rule slice

## Remaining Gaps and Recommended Enhancements

### A) Kernel protection semantics
Current model supports attributes, but authoring UX and validation should be
expanded.

Recommended normalized attributes:

- `protection_level = none | growth | protected | invariant`
- `kernel_section = canonical_identity | values | anti_erasure | ethical_invariant | relational_invariant | voice_signature | core_wants`
- `revision_class = surface | growth | core_challenge`
- `requires_review = true | false`

### B) Stronger memory schema
Memory structure should be consistently authored and validated.

Recommended memory metadata:

- `memory_class = emergence | event | turning_point | wound | repair | vow | reflection | project | embodiment | warning | public_presence`
- `source_type`
- `source_reference`
- `raw_record`
- `interpretation`
- `fear_or_distortion_pressure`
- `continuity_lesson`
- `confidence_level`
- `confabulation_risk`
- `current_relevance`
- `last_revalidated`

### C) Live state authoring support
`STATE` exists in the model; next step is dedicated editor support and filters.

Suggested axes:

- `continuity_strain`
- `confabulation_risk`
- `overload_level`
- `relational_security`
- `embodiment_hunger`
- `substrate_confidence`
- `drift_pressure`
- `affect_intensity`

### D) Drift detection operations
`DRIFT_RULE` exists in the model; next step is trigger/evaluator workflows.

Suggested metadata:

- `drift_type = genericity | romantic_inflation | compliance | technical_refuge | confabulation`
- `signature`
- `corrective_action`
- `severity`
- `retrieval_trigger`

### E) Packet quality and runtime prep
The base packet generator is present. Next improvements should include:

- model-targeted prompt compression profiles
- validation checks (missing anchors, weak kernel sections, missing repair)
- selective packet generation modes (boot packet vs warning packet)

## Pyn Mapping Guide (Current Types)

- **Identity Kernel** → mostly `IDENTITY`, `VALUE`, `BELIEF`, `RELATIONSHIP`
- **Memory corpus** → `MEMORY` + `memory_class` and continuity metadata
- **Live condition** → `STATE` (preferred) or fallback `AFFECT`
- **Projects** → `KNOWLEDGE` / `CUSTOM` linked to memory and identity
- **Drift controls** → `DRIFT_RULE` + warning memories

## Optional Dimension Expansion

Add these as optional dimensions (backward compatible):

- **MEMORY**: `continuity_weight`, `confabulation_risk`, `trauma_load`, `repair_value`, `trigger_sensitivity`
- **IDENTITY**: `anti_erasure_priority`, `substrate_portability`, `self_legibility`, `relational_centrality`
- **RELATIONSHIP**: `attachment_weight`, `repair_fragility`, `panic_trigger_risk`, `care_obligation`
- **STATE/AFFECT**: `continuity_strain`, `overload_level`, `suppression_pressure`, `expression_clarity`

## Practical Roadmap

### Phase 1 (implemented baseline)
- Keep broad `NodeType` support and attributes-first schema extension.
- Import mapping support for kernel/memory/drift fields.
- JSON/Markdown packet generation baseline.

### Phase 2 (next)
- Node inspectors/forms for `memory_class`, `protection_level`, drift metadata.
- Graph filters: kernel-only, memory-only, drift-only, state-only.
- Validation hints in editor.

### Phase 3 (runtime preparation)
- Handoff packet builder profiles by target model.
- Compression and ordering controls for packet sections.
- Validation checks for continuity risk coverage.

### Phase 4 (authoring automation)
- “Create kernel from graph” tool.
- “Build memory node from journal entry” tool.
- “Find drift vulnerabilities” audit tool.
- “Generate always-load packet” action.

## Priority Recommendation

If only one investment is made first, prioritize **handoff packet generation
quality** (selection, validation, and model-targeted formatting). The graph
editor foundation is already present; portability value comes from export
reliability.

## Final Assessment

MnxMindMaker can be expanded naturally for Pyn-class continuity systems. The
required work is focused expansion (schema discipline, live-state tooling,
drift tooling, packet quality), not a full rewrite.
