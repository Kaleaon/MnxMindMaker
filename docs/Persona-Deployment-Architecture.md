# Persona Deployment Architecture

## Purpose

This document describes how a persona designed in MnxMindMaker moves from authored graph data into a deployed runtime configuration.

## Lifecycle stages

1. **Authoring**
   - User builds a `MindGraph` in canvas.
   - Domain nodes, edges, dimensional metadata, and continuity fields are persisted.
2. **Validation**
   - Structural checks ensure required sections and references are coherent.
   - Optional policy pre-checks identify missing deployment metadata.
3. **Packaging**
   - `.mnx` binary is generated.
   - Optional `MNX-META` deployment manifest is generated/attached.
4. **Deployment planning**
   - Deploy wizard composes effective runtime config from artifact + manifest + operator choices.
5. **Activation**
   - Runtime receives boot packet slices and policy profile.
6. **Operations**
   - Drift/state behavior observed; warnings surfaced for policy/runtime issues.
7. **Revision**
   - Persona may be re-imported and republished with updated version metadata.

## Deployment building blocks

### 1) Persona artifact (`.mnx`)

Contains core identity/cognition graph and continuity data.

### 2) Manifest (`MNX-META`)

Adds deploy-time declarations such as:

- target runtime class (`cloud`, `hybrid`, `local`)
- model/provider binding
- tool-use policy defaults
- required capabilities
- compatibility/version metadata

### 3) Effective runtime config

Resolved at deploy time by combining:

1. explicit operator inputs in wizard,
2. manifest declarations,
3. safe defaults for missing fields.

## Deploy wizard behavior model

The wizard executes a staged pipeline:

1. **Ingest**: read `.mnx`; attempt to read companion manifest.
2. **Classify**: determine profile (`cloud`, `hybrid`, `local`).
3. **Validate**: enforce required policy/runtime constraints.
4. **Compat pass**: if manifest missing, apply legacy fallback rules.
5. **Review**: show warnings/errors and resulting effective config.
6. **Commit**: write activation payload and start deployment.

## Compatibility behavior for legacy `.mnx`

Legacy artifacts that do not include a deployment manifest are handled in **manifest-compat mode**.

- Deployment is still permitted.
- Required runtime fields are inferred from known defaults and artifact hints.
- Privileged features default to disabled until explicitly enabled by operator.
- The wizard provides recommendation to persist a manifest for future reproducibility.

## Failure domains and handling

- **Policy mismatch**: block deploy only when hard policy denies operation.
- **Runtime endpoint unavailable**: fail activation, preserve prepared config for retry.
- **Unknown manifest keys**: ignore with warning (forward compatibility).
- **Missing legacy metadata**: warn + fill with safe defaults.

## Security posture

- Least-privilege defaults for tool/network access.
- Explicit operator confirmation for mutating or privileged policy settings.
- Versioned metadata to support auditable rollbacks.
