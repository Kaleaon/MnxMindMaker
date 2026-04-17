# Skill Pack Manifest Format (v1)

Skill pack manifests are JSON files loaded from:

- `app/src/main/assets/skills/*.json`

Each manifest declares tool metadata while handler execution remains hard-mapped to approved
internal handler IDs in code (`ToolRegistry`).

## Top-level schema

```json
{
  "pack_id": "sample-pack",
  "version": "1.0.0",
  "enabled": true,
  "tools": [
    {
      "name": "tool_public_name",
      "description": "Tool description shown to the model",
      "handler_id": "graph.read.get_summary",
      "input_schema": {
        "type": "object",
        "properties": {},
        "additionalProperties": false
      },
      "risk": {
        "operation_class": "READ_ONLY",
        "requires_confirmation": false
      },
      "playbook": {
        "summary": "Optional step-by-step workflow for small/local models",
        "steps": [
          "Read current graph context",
          "Apply one focused update",
          "Re-check graph summary for consistency"
        ],
        "source": "https://github.com/agents-io/PokeClaw"
      }
    }
  ]
}
```

## Validation rules

- `handler_id` must match an approved in-code handler ID.
- `input_schema` must be a valid JSON schema block (object form; strict field types validated).
- `playbook.steps` is optional, but when provided it must be a non-empty array of non-blank strings.
- malformed JSON, duplicate tool names, or invalid schema fields cause the pack to be skipped.
- skipped packs and validation issues are recorded for diagnostics surfaces.

## Why playbooks?

Inspired by PokeClaw's local-first "tools + skills" approach, `playbook` metadata lets each tool carry a compact recipe so smaller models can follow reliable execution sequences instead of improvising each turn.
