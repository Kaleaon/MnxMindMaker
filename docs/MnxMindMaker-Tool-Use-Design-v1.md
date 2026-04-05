# MnxMindMaker Tool Use Design v1

## Goal

Add deterministic, safe **tool-use (function-calling)** to MnxMindMaker so AI assistance can do structured actions (create nodes, connect nodes, run audits, generate boot packets) rather than only returning free-form text.

This document was created after reviewing the current app architecture (`ui`, `model`, `repository`, `util`) and proposes an incremental integration path with provider-specific adapters.

---

## Current Baseline (What Exists Today)

### Strengths already in the app

- Cross-provider LLM configuration with encrypted API key storage.
- A single `LlmApiClient.complete()` request path for Anthropic, OpenAI-compatible, and Gemini endpoints.
- A rich graph model (`MindGraph`, `MindNode`, `MindEdge`) and domain taxonomy (`NodeType`) suitable for tool-based manipulation.
- Existing export pathways (MNX + boot packet slices) that can be exposed as callable tools.

### Limitation blocking tool use

The existing API surface returns only **plain text** from model responses. There is no structured representation for:

- assistant tool-call requests,
- tool execution results,
- iterative tool loop continuation,
- or safety policy evaluation before execution.

---

## Design Principles

1. **Provider-agnostic core, provider-specific adapters**  
   Keep business logic in one place; isolate Anthropic/OpenAI/Gemini protocol differences.

2. **Local tools only for v1**  
   First phase focuses on in-app deterministic tools (graph mutation, validation, packet generation). No shell/network tools.

3. **Human-in-the-loop by default for mutation tools**  
   AI can propose a plan; user approves before graph-changing execution.

4. **Hard guardrails**  
   Tool schemas are strict; max tool-iterations, token budget, and timeout ceilings are enforced in code.

5. **Replayable transcripts**  
   Each tool run stores request/response events for debugging, provenance, and future undo.

---

## Proposed Architecture

## 1) New domain layer: Tool protocol types

Create a lightweight, provider-neutral protocol package (recommended path: `util/tooling/`):

- `ToolSpec`
  - name
  - description
  - JSON schema
  - risk level (`READ_ONLY`, `MUTATING`)
- `ToolInvocation`
  - `id`, `toolName`, `argumentsJson`
- `ToolResult`
  - `toolUseId`, `isError`, `contentJson`
- `AssistantTurn`
  - `textBlocks`
  - `toolInvocations`
  - `stopReason`

This layer acts as the internal contract for all providers.

## 2) New execution layer: Tool registry + policy

- `MindMakerToolRegistry`
  - Registers callable tools and schemas.
  - Maps `toolName -> handler`.
- `ToolPolicyEngine`
  - Decides if tool can run automatically, requires user approval, or is denied.
  - Denies unknown tools by default.
- `ToolExecutionEngine`
  - Parses args, runs handler, catches deterministic execution errors.
  - Returns structured `ToolResult`.

## 3) New orchestration layer: Agent loop

`ToolUseOrchestrator` controls the iterative loop:

1. Send user/system messages + tool definitions.
2. Parse model response.
3. If tool calls exist: evaluate policy, execute approved tools, append tool results.
4. Continue until terminal response or step budget reached.

Initial safe defaults:

- max loops: 4
- max tool calls/turn: 8
- per-tool timeout: 2s (local)

## 4) Provider adapters

Wrap each provider in a small adapter implementing a shared interface:

```kotlin
interface ToolCapableLlmAdapter {
    fun createTurn(request: ToolTurnRequest): AssistantTurn
}
```

- `AnthropicToolAdapter`
  - Uses official messages tool_use/tool_result structures.
- `OpenAIToolAdapter`
  - Uses function/tool call message pattern.
- `GeminiToolAdapter`
  - Uses function declarations and function response parts.

Adapters only convert wire-format <-> internal protocol; they do not execute tools.

---

## v1 Tool Surface for MnxMindMaker

### Read-only tools (auto-allow)

- `get_graph_summary()`
- `list_nodes(type?, limit?)`
- `get_node(node_id)`
- `find_unlinked_nodes()`
- `suggest_missing_core_domains()`

### Mutating tools (require approval)

- `add_node(label, type, description?, parent_id?)`
- `link_nodes(from_id, to_id, label?)`
- `update_node_dimensions(node_id, dimensions)`
- `set_node_attribute(node_id, key, value)`
- `remove_node(node_id)`

### Export/analysis tools (approval optional by policy)

- `generate_boot_packet(profile?)`
- `run_continuity_audit()`

---

## UX Flow in App

1. User enters prompt in **Ask AI** dialog.
2. If model emits tool calls, show a compact approval card:
   - tool name
   - key arguments
   - risk badge
3. User can:
   - approve once,
   - approve all read-only,
   - deny.
4. After loop completes, show:
   - final assistant summary,
   - executed tools timeline,
   - graph changes applied.

---

## Data Safety & Security Controls

- **No dynamic code execution** in v1.
- **No external network tool execution** in v1.
- Strict JSON schema validation before handler execution.
- Reject oversize string arguments and deeply nested payloads.
- Transaction-style graph updates with rollback on failed mutation tool.
- Persist tool transcript with redaction of keys/secrets.

---

## "Claude harness leak" findings and recommendation

There are public writeups and repos describing unofficial or reverse-engineered Claude harness behaviors, but these are unstable and policy-sensitive. For Android production integration, using leaked/unofficial harness internals is not recommended.

### Recommended stance for MnxMindMaker

- Treat leak reports as **informational only** for understanding generic agent-loop patterns.
- Implement the loop using **official provider APIs and SDK semantics**.
- Keep provider abstraction so MnxMindMaker is not coupled to one vendor’s private harness behavior.

### Useful patterns that are safe to reuse conceptually

- Event-streamed turn loop with explicit stop conditions.
- Tool registry with explicit allowlist.
- Hook/policy interception before tool execution.
- Deterministic tool-result echo back into the conversation.

### What to avoid

- OAuth/session token replay approaches.
- Reverse-engineered private endpoints or headers.
- Dependence on leaked internal prompt templates and proprietary cache behavior.

---

## Implementation Plan (Incremental)

### Phase 1: Internal scaffolding

- Add provider-neutral tool protocol classes.
- Add tool registry + policy + executor.
- Add read-only graph inspection tools.

### Phase 2: Provider adapter upgrade

- Extend `LlmApiClient` to parse structured assistant turns.
- Add Anthropic/OpenAI/Gemini adapters.
- Preserve text-only fallback if provider/tool mode fails.

### Phase 3: UI + approvals

- Add tool approval dialog in `MindMapFragment`.
- Show run timeline and final summary.

### Phase 4: Mutating tools + audit tools

- Add graph mutation handlers with rollback.
- Add continuity/drift audit tool endpoints.

### Phase 5: Reliability

- Unit tests for schema validation and policy engine.
- Integration tests for loop termination guarantees.
- Add telemetry counters (loops, tools, errors) without sensitive payloads.

---

## Acceptance Criteria for v1

- User can ask AI to restructure graph and receive actionable tool plans.
- Read-only tools execute automatically and reproducibly.
- Mutating tools always require explicit user approval.
- Loop terminates predictably under budget/time limits.
- Full feature degrades gracefully to text-only mode if tool support is unavailable.

---

## Integration Notes for Existing Code

- `MindMapViewModel.askLlmForMindDesign()` should delegate to orchestrator instead of directly calling `LlmApiClient.complete()`.
- `LlmApiClient` should gain structured turn APIs while keeping current string API for backward compatibility.
- `BootPacketGenerator` and graph helper methods should be wrapped as typed tool handlers.

