# Memory Routing Canonical Keys

To keep imported, generated, and hand-authored memory nodes consistent, use these canonical attributes:

- `memory_wing`
- `memory_hall`
- `memory_room`

These keys define routing at three levels:

1. **Wing**: top-level memory domain.
2. **Hall**: sub-domain within the wing.
3. **Room**: concrete retrieval bucket.

## Recommended Mapping Examples

| Query intent example | memory_wing | memory_hall | memory_room |
|---|---|---|---|
| "What tone should I use when replying?" | `self` | `preferences` | `voice_style` |
| "Which guardrails apply to this request?" | `governance` | `risk` | `guardrails` |
| "Give me the deployment checklist." | `execution` | `project` | `runbook` |
| "What happened in the last session?" | `history` | `events` | `timeline` |
| Generic factual retrieval | `core` | `knowledge` | `reference` |

## Import/Generation Guidance

- CSV/JSON importers should pass through `memory_wing`, `memory_hall`, and `memory_room` when present.
- If one or more keys are missing, route inference should backfill them from query intent and memory text.
- Keep values lowercase snake_case for deterministic matching.

## Minimal Tagged Memory Example

```json
{
  "label": "Deployment runbook",
  "type": "MEMORY",
  "description": "Checklist for launch readiness.",
  "memory_wing": "execution",
  "memory_hall": "project",
  "memory_room": "runbook"
}
```
