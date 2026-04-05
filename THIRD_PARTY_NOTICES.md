# MindMaker Third-Party Notices

## Scope

This file records prebuilt binary artifacts referenced via:

- `implementation(files(...))` in `app/build.gradle`

Audit date: 2026-04-05.

## Inventory: `implementation(files(...))` Artifacts

No `implementation(files(...))` dependencies are currently declared in `app/build.gradle`.

### Result

Because there are no local prebuilt file dependencies:

- No binary artifact source repository/version entries are required for this scope.
- No additional binary-specific license text is required for this scope.
- No additional binary-specific copyright notices are required for this scope.
- No additional binary-specific redistribution obligations are required for this scope.

## Policy Decision for Critical Binaries with Unavailable Source

MindMaker policy: **replace**.

If a future critical binary is introduced via `implementation(files(...))` and
its source or redistributable licensing terms cannot be verified, it must be
replaced with a source-available and license-compliant alternative before release.

## Release Packaging Attribution

The Android build copies both compliance notice files into release assets:

- `NOTICE`
- `THIRD_PARTY_NOTICES.txt` (generated from this file)

This copy step is wired through the `prepareComplianceAssets` task and attached
to `preBuild` in `app/build.gradle`.
