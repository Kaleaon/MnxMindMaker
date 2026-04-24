# Kotlin quality gates

This project uses **Detekt + a targeted Gradle scanner** to keep Kotlin app-layer code safe while we gradually tighten enforcement.

## Rules currently enforced

- **`checkNonNullAssertions` task**: flags every `!!` usage and compares against `config/detekt/non-null-assertion-baseline.txt` so new non-null assertions are blocked in CI.
- **`UnsafeCast`** (app-layer paths): flags `as` casts in repository/util/persona/interchange/mnx code where parsing and mapping bugs are most costly.
- **`TooGenericExceptionCaught`** (app-layer paths): flags broad catches like `catch (Exception)` around business/parsing flows.
- **`SwallowedException`**: flags silent exception swallowing, especially relevant for serialization/parsing fallbacks.

Configuration is in `config/detekt/detekt.yml`; existing Detekt findings are tracked in `config/detekt/baseline.xml`, and existing `!!` usages are tracked in `config/detekt/non-null-assertion-baseline.txt`.

## Rollout model

1. **Warning mode by default (local/dev)**
   - `./gradlew :app:checkNonNullAssertions :app:detekt`
   - Does not fail the build unless `-Pdetekt.failOnIssues=true` is passed.

2. **Fail on new violations in CI**
   - CI runs `:app:checkNonNullAssertions :app:detekt -Pdetekt.failOnIssues=true`.
   - Because baseline entries suppress only existing findings, new code must pass cleanly.

## Contributor expectations

- Prefer safe null-handling (`?.`, `?:`, preconditions) over `!!`.
- Prefer safe casts (`as?`) + validation when mapping untrusted/parsed data.
- Catch specific exception types for parsing/serialization; avoid `catch (Exception)` unless rethrowing/wrapping with context.
- If you intentionally introduce a temporary violation, discuss it in PR review instead of expanding the baseline by default.
