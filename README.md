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
| **LLM API Settings** | Configure API keys and models for **Anthropic** (Claude), **OpenAI** (GPT), and **Google Gemini** — keys stored with AES-256-GCM encrypted SharedPreferences |
| **AI Assistance** | Ask any configured LLM for mind-design suggestions directly from the canvas |

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

### LLM API keys

Keys are **never** hard-coded. Enter them in the **Settings** tab of the app.
They are stored with `EncryptedSharedPreferences` (AES-256-GCM).

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
    └── LlmApiClient.kt     Anthropic / OpenAI / Gemini HTTP client
```
