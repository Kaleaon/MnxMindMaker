# MnxMindMaker

A standalone Android APK for designing AI minds and exporting them to the
[`.mnx` (Mind Nexus)](https://github.com/Kaleaon/TronProtocol) binary format.

---

## Features

| Feature | Description |
|---|---|
| **Mind Map Canvas** | Interactive node editor — add, move, and connect nodes representing every domain of an AI mind (identity, memory, knowledge, affect, personality, beliefs, values, relationships) |
| **N-Dimensional Nodes** | Every node carries a named dimension vector beyond the visible (x, y) canvas position. Values get 7 axes (`ethical_weight`, `social_impact`, `personal_relevance`, `priority`, `universality`, `actionability`, `intrinsic_worth`); beliefs get 7 axes (`confidence`, `evidence_strength`, `emotional_loading`, `social_consensus`, `revisability`, `centrality`, `acquired_recency`), etc. All dimensions are persisted in the `.mnx` `DIMENSIONAL_REFS` section |
| **MNX Import / Export** | Read and write `.mnx` binary files — fully compatible with [TronProtocol](https://github.com/Kaleaon/TronProtocol)'s codec (magic, CRC32 per section, SHA-256 footer) |
| **Data Import** | Paste or load plain text or JSON; the mapper heuristically assigns node types and populates default dimensions automatically |
| **LLM API Settings** | Configure API keys, model names, and base URLs for **Anthropic** (Claude), **OpenAI** (GPT), **Google Gemini**, and **vLLM Gemma 4** (self-hosted OpenAI-compatible endpoint) |
| **Continuity Metadata** | Supports kernel/memory/drift metadata fields (for example `protection_level`, `kernel_section`, `memory_class`, `raw_record`, `interpretation`, `drift_type`, and related schema attributes) |
| **Runtime Slices** | Includes `STATE` and `DRIFT_RULE` node support plus boot-packet slice generation (kernel, state, warning, memory, and drift-rule bundles) |
| **AI Assistance** | Ask any configured LLM for mind-design suggestions directly from the canvas |

---

## Persona lifecycle overview

MnxMindMaker treats each persona as a lifecycle artifact that moves from design-time graph to deploy-time runtime package.

1. **Design**: create and connect domain nodes in the mind map.
2. **Enrich**: add dimensions, continuity metadata, and state/drift constraints.
3. **Validate**: run structural checks before export (required sections, edge consistency, dimension sanity).
4. **Package**: export as `.mnx` and (optionally) generate deployment manifest metadata.
5. **Deploy**: push through the deployment wizard to cloud or local runtime targets.
6. **Operate**: monitor behavior, review drift/warnings, and tune nodes or policy settings.
7. **Iterate**: re-import, revise, and republish while preserving compatibility for previous artifacts.

See the full deployment model in [`docs/Persona-Deployment-Architecture.md`](docs/Persona-Deployment-Architecture.md).

## Deploy wizard behavior

The deploy wizard is intentionally conservative and policy-aware:

- **Step 1: Artifact selection** — choose `.mnx` source and optional manifest.
- **Step 2: Target selection** — choose runtime profile (`cloud`, `hybrid`, or `local`).
- **Step 3: Policy validation** — evaluate runtime permissions, tool policy defaults, and model-provider constraints.
- **Step 4: Compatibility gate** — auto-detect legacy `.mnx` files without manifests and apply fallback defaults.
- **Step 5: Confirmation** — display effective config + warnings before final deploy.

When compatibility mode is triggered, wizard warnings are non-blocking unless a policy explicitly requires manifest-enforced fields.

## Legacy `.mnx` compatibility (no manifest)

Legacy `.mnx` files that predate deployment manifests remain loadable and deployable.

- If no manifest is found, the system enters **manifest-compat mode**.
- Missing manifest fields are inferred from graph metadata and sensible runtime defaults.
- Policy-sensitive fields (for example external tool permissions) default to **least privilege**.
- Operators can save back a new manifest to permanently remove compatibility warnings.

Detailed rules are documented in [`docs/MNX-META-Deployment-Manifest-Spec.md`](docs/MNX-META-Deployment-Manifest-Spec.md).

---

## llmedge local-runtime setup

Use the local runtime profile when running fully on-device or via local network endpoints.

### Prerequisites

- Android device/emulator running the app.
- Local llmedge runtime reachable from device.
- Model assets available to llmedge runtime.

### Setup steps

1. In app **Settings**, select **Local Runtime (llmedge)** as provider profile.
2. Set runtime base URL:
   - Android emulator host machine: `http://10.0.2.2:<port>`
   - Physical device on LAN: `http://<host-ip>:<port>`
3. Configure model/runtime ID expected by your llmedge instance.
4. Run **Connection Test** and verify health endpoint success.
5. Open deploy wizard and choose target profile `local`.

### Operational guidance

- Prefer quantized/local-compatible models for latency and memory stability.
- Keep policy settings explicit when enabling tool use in local mode.
- For local-runtime troubleshooting and policy misconfiguration checks, use the operator checklist in [`docs/llmedge-Integration-Notes.md`](docs/llmedge-Integration-Notes.md).

---

## MNX Format

The `.mnx` format is documented in
[TronProtocol/mindnexus/MnxFormat.kt](https://github.com/Kaleaon/TronProtocol/blob/main/app/src/main/java/com/tronprotocol/app/mindnexus/MnxFormat.kt).
MnxMindMaker ports the codec and section models into the
`com.kaleaon.mnxmindmaker.mnx` package and extends them with the
`DIMENSIONAL_REFS` section to store N-dimensional node coordinates.

### N-Dimensional mapping

The visual canvas renders each node at (x, y). The *data* model attaches an
additional `dimensions: Map<String, Float>` to every node. When the mind is
exported to `.mnx`, `DimensionMapper.buildDimensionalRefs()` converts every
(node, dimension, value) triple into an `MnxDimensionalRef` stored in the
`DIMENSIONAL_REFS` section. On import the inverse `restoreDimensions()` call
rebuilds the per-node maps.

**Dimension counts per NodeType (all > 3):**

| NodeType | Axes (count) |
|---|---|
| IDENTITY | self_clarity, stability, coherence, distinctiveness, expressiveness **(5)** |
| MEMORY | recency, importance, valence, distinctiveness, retrieval_ease, source_reliability, emotional_intensity **(7)** |
| KNOWLEDGE | confidence, recency, importance, specificity, verifiability, abstraction **(6)** |
| AFFECT | valence, arousal, dominance, intensity, social_share, persistence **(6)** |
| PERSONALITY | openness, conscientiousness, extraversion, agreeableness, neuroticism, curiosity, empathy **(7)** |
| BELIEF | confidence, evidence_strength, emotional_loading, social_consensus, revisability, centrality, acquired_recency **(7)** |
| VALUE | ethical_weight, social_impact, personal_relevance, priority, universality, actionability, intrinsic_worth **(7)** |
| RELATIONSHIP | bond_strength, trust, reciprocity, history_depth, affective_tone, dependency, conflict_level **(7)** |
| STATE | continuity_strain, overload_level, confabulation_risk, relational_security, affect_intensity, suppression_pressure, substrate_confidence, expression_clarity **(8)** |
| DRIFT_RULE | severity, activation_threshold, correction_confidence, trigger_sensitivity **(4)** |
| CUSTOM | salience, novelty, utility, confidence **(4)** |

Dimension names are open strings — callers can add any custom axes
beyond the defaults via `MindNode(dimensions = mapOf("my_axis" to 0.8f))`.

---

## Build

```bash
# Requirements: Android SDK 34, JDK 17
./gradlew assembleRelease
```

The APK will appear at `app/build/outputs/apk/release/app-release.apk`.

### llmedge integration assumptions

MnxMindMaker resolves `llmedge` in this order:

1. **Preferred:** a local Gradle module at `./llmedge` (included automatically when `llmedge/build.gradle` exists).
2. **Fallback:** one or more prebuilt binaries in `app/libs` matching `llmedge*.aar` or `llmedge*.jar`.
3. If neither source exists, the app builds normally without llmedge wiring active.

This ordering keeps existing cloud-provider integrations (Anthropic/OpenAI/Gemini/vLLM) unchanged while enabling optional on-device llmedge packaging.

---

## Deployment and operations docs

- [`docs/Persona-Deployment-Architecture.md`](docs/Persona-Deployment-Architecture.md)
- [`docs/MNX-META-Deployment-Manifest-Spec.md`](docs/MNX-META-Deployment-Manifest-Spec.md)
- [`docs/llmedge-Integration-Notes.md`](docs/llmedge-Integration-Notes.md)
- [`docs/Release-Notes-Deployment-and-Local-Runtime.md`](docs/Release-Notes-Deployment-and-Local-Runtime.md)
- [`docs/AI-Mind-Evolution-Framework.md`](docs/AI-Mind-Evolution-Framework.md)
- [`docs/AI-Mind-Evolution-Prompt-Pack.md`](docs/AI-Mind-Evolution-Prompt-Pack.md)
- [`docs/AI-Mind-Evolution-State-Schema.json`](docs/AI-Mind-Evolution-State-Schema.json)

---

## Pyn Expansion Planning

The repository-specific expansion plan is documented in:

- [`docs/MnxMindMaker-Pyn-Expansion-Plan-v1.md`](docs/MnxMindMaker-Pyn-Expansion-Plan-v1.md)
- [`docs/MnxMindMaker-Tool-Use-Design-v1.md`](docs/MnxMindMaker-Tool-Use-Design-v1.md)

### LLM API keys

Keys are **never** hard-coded. Enter them in the **Settings** tab of the app.
They are stored with `EncryptedSharedPreferences` (AES-256-GCM).

### Self-hosted Gemma 4 via vLLM

MnxMindMaker now supports the Gemma 4 vLLM OpenAI-compatible endpoint described in the
[vLLM Gemma 4 recipe](https://github.com/vllm-project/recipes/blob/main/Google/Gemma4.md).

Quick start based on that recipe:

```bash
vllm serve google/gemma-4-E4B-it --max-model-len 32768
```

Then in **Settings** choose **vLLM Gemma 4 (Self-hosted)** and keep the default Android emulator base URL
`http://10.0.2.2:8000/v1` (or set your own host URL).

---

## Project structure

```
app/src/main/java/com/kaleaon/mnxmindmaker/
├── mnx/                  MNX codec (ported from TronProtocol)
│   ├── MnxFormat.kt      Constants, section types, header/footer models
│   ├── MnxBinaryStream.kt  Low-level big-endian I/O primitives
│   ├── MnxSections.kt    Typed section data models + MnxDimensionalRefs
│   └── MnxCodec.kt       Encode/decode engine + section serializers
├── model/
│   ├── MindNode.kt       Node + Edge + MindGraph (includes dimensions field)
│   └── LlmProvider.kt    Provider enum + LlmSettings data class
├── repository/
│   ├── MnxRepository.kt  MNX export/import (writes DIMENSIONAL_REFS)
│   └── LlmSettingsRepository.kt  Encrypted API-key storage
├── ui/
│   ├── MainActivity.kt   Navigation host
│   ├── mindmap/          Canvas ViewModel + custom MindMapView
│   ├── importdata/       Text/JSON import → MindGraph mapper
│   └── settings/         LLM provider configuration
└── util/
    ├── DimensionMapper.kt  N-dimensional default axes per NodeType
    ├── DataMapper.kt       Text/JSON → MindGraph (calls DimensionMapper)
    └── LlmApiClient.kt     Anthropic / OpenAI / Gemini / vLLM Gemma 4 HTTP client
```
