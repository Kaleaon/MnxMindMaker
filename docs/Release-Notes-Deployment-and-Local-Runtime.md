# Release Notes: Deployment & Local Runtime Documentation Update

## Summary

This release adds deployment-focused documentation and operational guidance for persona lifecycle, deployment manifests, and llmedge local-runtime behavior.

## User-facing changes

- Added README sections for:
  - persona lifecycle overview,
  - deploy wizard behavior,
  - llmedge local-runtime setup,
  - legacy `.mnx` compatibility semantics.
- Added `Persona-Deployment-Architecture.md` describing deployment flow and compatibility handling.
- Added `MNX-META-Deployment-Manifest-Spec.md` defining manifest schema, validation, and fallback behavior.
- Added `llmedge-Integration-Notes.md` including operator troubleshooting and policy misconfiguration checklist.

## Compatibility notes

- Legacy `.mnx` artifacts without manifests remain deployable via compatibility mode.
- Missing manifest fields resolve to safe defaults with warnings rather than immediate blocking.

## Known limitations

- Local runtime performance varies with host/device resources and model size.
- Policy defaults may initially appear strict and require operator tuning.
- Without a persisted manifest, operators may repeatedly see compatibility warnings.

## Recommended next actions

- Persist manifests for actively maintained personas.
- Standardize runtime profiles per environment (dev/stage/prod).
- Adopt the operator checklist as part of deploy runbooks.
