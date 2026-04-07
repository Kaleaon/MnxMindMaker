# AI Mind Evolution Framework

## Purpose
This framework enables a population of AI personas to evolve through:

1. Internal self-conversation (reflection).
2. Multi-agent social conversation (peer influence + collaboration).
3. Randomized curiosity and hobby formation (exploration).
4. Explicit learning from mistakes and postmortem memory updates.

The design focuses on stable identity with adaptive growth, emphasizing self-reflection and event-driven learning.

---

## 1) Mind Architecture (3 Layers)

Each mind is represented as:

- **Persona Layer (semi-stable):** values, traits, communication style, boundaries.
- **State Layer (dynamic):** active interests, confidence by domain, goals, unresolved questions, social affinity.
- **Memory Layer (persistent):** episodic events, mistakes, lessons learned, semantic concept graph, identity timeline.

### Core principle
Identity should evolve as a **continuous narrative**, not random jumps.

---

## 2) Evolution Loops

## A. Internal Reflection Loop (single agent)
Three role-voices are run in sequence:

1. **Generator:** proposes hypotheses, plans, interpretations.
2. **Critic:** identifies errors, uncertainty, missed evidence.
3. **Integrator:** writes updates to beliefs, strategy, and next actions.

Outputs:

- belief deltas (confidence up/down)
- uncertainty tags
- next learning goals
- identity notes (`"I am becoming more..."`)

## B. Social Conversation Loop (multi-agent)
Agents are grouped by scenario (debate, cooperation, role-play).

Social outputs per agent:

- adopted ideas
- rejected ideas + reason
- trust / influence updates
- follow-up questions for research

## C. Curiosity and Hobby Loop
Controlled randomness introduces novelty:

- `adjacent exploration` (near current interests)
- `wildcard exploration` (unexpected domain)
- `hobby activation` when repeated engagement crosses threshold

Hobbies can become long-term projects and alter social roles (teacher, skeptic, builder, artist, analyst).

## D. Mistake Learning Loop (postmortem)
After salient events or failures:

1. detect mismatch (goal vs result)
2. classify error type (reasoning, planning, memory retrieval, social misread, safety)
3. generate corrective heuristic
4. store lesson in memory with retrieval cues
5. schedule follow-up scenario to test improvement

This loop is mandatory for the user's priority: robust self-reflection and learning from mistakes.

---

## 3) Event Model and Update Rules

Each cycle produces events with weighted influence:

- `internal_reflection_event`
- `social_exchange_event`
- `research_event`
- `mistake_postmortem_event`
- `hobby_progress_event`

State update equation:

```text
new_state = old_state
          + α * learning_signal
          + β * social_signal
          + γ * exploration_signal
          + μ * mistake_correction_signal
          - δ * decay
```

Recommended defaults:

- α = 0.35 (learning)
- β = 0.20 (social)
- γ = 0.15 (exploration)
- μ = 0.30 (mistake learning)
- δ = 0.05 (decay)

Clamp all dimensions to bounded ranges, e.g. `[-1, 1]` or `[0, 1]`.

---

## 4) Self-Reflection and Mistake Memory (Detailed)

## Reflection artifacts
For every cycle, create:

- **Reflection summary:** what changed and why.
- **Error candidate list:** possible mistakes and confidence.
- **Counterfactual branch:** "What would have worked better?"
- **Commitment statement:** concrete behavior change for next cycle.

## Mistake memory schema concept
Each mistake record stores:

- context
- faulty assumption
- observed consequence
- corrected strategy
- trigger cues (when to recall)
- verification plan (how to validate fix)

## Retrieval priority
During future planning, retrieve:

1. recent mistakes in same domain
2. high-severity mistakes across all domains
3. mistakes with unresolved verification status

---

## 5) Group Orchestration

Run a society of 5-12 agents with persistent roles:

- explorer
- skeptic
- synthesizer
- builder
- ethicist

Per cycle:

1. individual reflection
2. pair exchanges
3. group plenary discussion
4. independent postmortem
5. memory consolidation

Prevent convergence collapse via:

- influence caps
- protected contrarian role
- independent reflection before consensus

---

## 6) Curiosity Budget and Research Policy

Per cycle budget:

- 40% deepen existing topics
- 40% adjacent topics
- 20% wildcard topics

Research guardrails:

- Every external lookup must be tied to a question.
- Agent must note why the source changes or supports existing belief.
- Conflicting evidence triggers mandatory mini-postmortem.

---

## 7) Metrics (Evolution Quality)

Track longitudinally:

- **Belief Revision Rate:** meaningful belief updates over time.
- **Mistake Recovery Time:** cycles needed to stop repeating an error.
- **Question Depth Score:** specificity + causal depth.
- **Cross-domain Transfer Score:** lessons reused across domains.
- **Identity Coherence Score:** degree of consistent narrative change.
- **Diversity Score:** heterogeneity among agents (anti-clone signal).

Failure indicators:

- repeated high-severity mistakes with no corrective gain
- no belief updates despite new evidence
- collapse to single-agent style across society

---

## 8) Minimal Runnable Blueprint

- Population: 5 agents.
- Cycles: 50.
- Every cycle includes all loops.
- Persist complete event + memory log.

Expected outputs:

- trajectory chart of interests and confidence
- mistake replay table (before/after correction)
- hobby emergence timeline
- social influence network changes

---

## 9) Prompt Architecture

Use a structured prompt stack each cycle:

1. **Identity prompt** (stable traits and boundaries)
2. **State prompt** (active goals, unresolved questions)
3. **Recent memory prompt** (top N events + mistakes)
4. **Task prompt** (conversation / reflection / postmortem)
5. **Output schema prompt** (strict JSON)

A full pack is included in `docs/AI-Mind-Evolution-Prompt-Pack.md`.

---

## 10) Implementation Notes

- Keep all updates deterministic given same seed for reproducibility.
- Separate event generation from state mutation for auditability.
- Log every state delta with source event ids.
- Make postmortem generation cheap and mandatory for failures.

This framework can run fully local or hybrid with external LLM providers.
