# MNX-META Deployment Manifest Specification

## Overview

`MNX-META` is an optional deployment manifest that accompanies an `.mnx` persona artifact.
Its role is to make runtime and policy behavior explicit and reproducible.

## Design goals

- deterministic deployment configuration
- backwards compatibility with legacy `.mnx`
- policy-safe defaults when declarations are absent
- forward-compatible parsing for future fields

## Logical schema

```yaml
manifest_version: 1
persona:
  id: "persona-uuid-or-slug"
  version: "semver"
  display_name: "string"
runtime:
  profile: "cloud|hybrid|local"
  provider: "openai|anthropic|gemini|vllm|llmedge|toolneuron|huggingface|custom"
  model: "string"
  endpoint: "https://... or http://..."
policy:
  tool_use_default: "deny|read_only|approval_required"
  network_default: "deny|allow_list"
  allow_mutations: true
  approval_required_for_mutations: true
compatibility:
  min_runtime_version: "string"
  legacy_mnx_mode: false
metadata:
  created_at: "ISO-8601"
  created_by: "string"
  notes: "string"
```

## Required fields

- `manifest_version`
- `persona.id`
- `persona.version`
- `runtime.profile`
- `policy.tool_use_default`

All other fields are optional but recommended.

## Parsing rules

1. Unknown keys MUST be ignored with warning.
2. Missing optional keys SHOULD use documented defaults.
3. Invalid enum values MUST fail validation.
4. Empty manifest content MUST be treated as absent manifest.

## Legacy `.mnx` compatibility (manifest absent)

When no manifest exists, implementations MUST:

1. Enter `legacy_mnx_mode = true` implicitly.
2. Infer `runtime.profile` from operator selection in deploy wizard.
3. Set `policy.tool_use_default = deny` unless explicitly overridden.
4. Set `policy.network_default = deny` unless endpoint is required and approved.
5. Emit a non-blocking compatibility warning to operator.

This ensures older artifacts remain deployable while preserving a safe baseline.

## Validation severity

- **Error (blocking)**
  - malformed required field
  - unsupported `manifest_version`
  - disallowed policy override
- **Warning (non-blocking)**
  - missing optional fields
  - unknown keys
  - legacy compatibility mode activated

## Versioning strategy

- Major schema changes increment `manifest_version`.
- Minor additive fields are introduced as optional keys and must preserve forward compatibility.
