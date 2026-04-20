# Layla v6.8.2 Recon Notes (APK-level)

Date: 2026-04-20  
Artifact: `https://r2-assets.layla-cloud.com/releases/layla-v6.8.2-direct.apk`

## What was extracted

Using `jadx` + `strings` over the APK bundle, these relevant patterns emerged:

1. **Character-centric UX is first-class**
   - Character asset sets are extensive (`resources/assets/characters/*`).
   - Prompt/content text references roleplay workflows and per-character customization.

2. **Long-term memory has explicit UX + background workflows**
   - User-facing "Added to Long-term Memory!" flow exists.
   - Background task cadence references indicate periodic ingestion behavior.

3. **Embeddings/vector retrieval are used at runtime**
   - Native layer includes `lvdb_*` methods for saving/querying/deleting embeddings.
   - Suggests retrieval quality is improved when memory writes carry richer metadata.

4. **Companion/overlay runtime exists**
   - `CompanionOverlay*` service/module classes imply persistent multi-surface assistant UX.

## Actionable implications for MnxMindMaker

1. Keep memory writes **character-addressable** by default (`character_id`).
2. Allow memory reads/search to receive a **character hint** and prioritize matching records.
3. Keep mention parsing robust for multi-word identities (already aligned via `@{...}` support).
4. Continue moving toward richer retrieval metadata (tags, route, character linkage).
