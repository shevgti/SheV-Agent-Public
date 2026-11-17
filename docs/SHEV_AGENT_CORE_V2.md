# SheV Agent Core v2 — architecture and rebuild contract

Date: 2026-11-17
Status: implementation baseline; staged migration from the current Android app.
Branch: `rebuild/shev-agent-core-v2`

## Product decision

SheV Agent is one personal agent system, not a chat application with a chatbot promoted to manager, and not a remote ChatGPT supervisor controlling the phone.

The chat is only one user interface / gateway. The authoritative coordinator is the deterministic **Agent Kernel** running inside SheV Agent. It owns task state, plans, tool routing, permissions, execution, verification, memory writes, schedules and audit. A model is a replaceable reasoning engine called by the kernel; it can propose structured decisions but cannot grant itself capabilities or bypass the kernel.

No browser-first redesign. A browser is merely an optional tool adapter later, not the product architecture.

## Research reviewed

The following public repositories were inspected at source/README and architecture-document level on 2026-11-17:

- Pernix — https://github.com/calvincs/Pernix. Useful patterns: scout-then-act, separate primary/background/fallback model roles, append-only context compaction, persistent goals with budgets, worker orchestration, progressive skill disclosure, durable jobs, memory distillation and verification gates. Its own README warns that it is experimental and must be isolated; do not copy unsafe host-shell execution.
- MIRA — https://github.com/Vexillon-ai/MIRA. Useful patterns: explicit agent core/orchestrator/supervisor boundaries, named agents/workflows, provider adapters, tool loop, context budgets, memory providers, guardian/security layer and audit.
- PersonalClaw — https://github.com/PersonalClaw/PersonalClaw. Useful patterns: goal loops, sessions, app/tool platform, knowledge/memory architecture, permission-gated capabilities, triggers and workflows.
- Tini Agent — https://github.com/tini-coder/tini-agent. Useful patterns: very small transparent tool loop, memory gate, semantic/episodic/procedural memory, one SQLite state store, trace/evaluation loop.
- Agenvoy — https://github.com/pardnchiu/Agenvoy. Useful patterns: local daemon, persistent tool registry, build/test/share tools, schedules, model routing and visible execution evidence. Do not import its host command execution model onto Android.

These are architectural references, not dependencies. Check each repository's current license before copying any code; prefer clean-room Kotlin implementations of concepts.

## Responsibility model

```
User
  -> Gateway layer (Compose chat / voice / share intent / future local API)
  -> Agent Kernel (authoritative coordinator and state machine)
       -> Context & Memory service
       -> Planner / task graph
       -> Model Router (local / optional cloud providers; user-selected)
       -> Tool Registry + capability policy
       -> Approval broker
       -> Execution engine (bounded, cancellable, durable)
       -> Verification engine (postconditions and evidence)
       -> Event journal / observability
       -> Scheduler / background work
  <- streamed events, results and evidence
```

- Gateway/UI: renders messages, tasks, approvals, activity and results. It does not orchestrate or possess tool authority.
- Agent Kernel: owns the lifecycle and makes deterministic policy decisions. It is the only component allowed to dispatch tools.
- LLM: proposes intent classification, plans, tool arguments, summaries and memory candidates. All proposals are schema-validated and policy-checked.
- Specialists/workers: optional isolated bounded workers for research, coding, review, etc. They are not a chief agent, cannot dispatch privileged tools directly, and return structured artifacts to the kernel.
- Tools: capability-limited adapters. No arbitrary shell, dynamic code loading or unrestricted filesystem/network on Android.
- Memory: explicit typed records with provenance, confidence, timestamps, supersession and user correction. Chat transcript is not the whole memory.
- Verification: a tool's success string is not proof. Verify expected postconditions and retain evidence tied to task/step IDs.

## Core task lifecycle

`RECEIVED -> CLASSIFYING -> PLANNING -> READY -> (AWAITING_APPROVAL) -> EXECUTING -> VERIFYING -> SUCCEEDED | FAILED | CANCELLED`

Pause/resume and retry are explicit transitions. Each task and step has stable IDs, revision, deadline, budget, retry policy, cancellation token, dependencies and event history. Persist state before side effects; use idempotency keys for retried operations.

## Planning and execution

1. Classify request: answer-only, memory operation, tool task, multi-step goal, scheduled goal or unsafe/unsupported.
2. Retrieve only relevant memory using a retrieval gate; include source/provenance and respect user corrections.
3. Build a typed DAG/plan with step IDs, dependencies, expected outputs, risk, capability and estimated budget.
4. Kernel validates the plan and tool schemas. Unknown tool, field, capability or schema version is rejected.
5. Read-only low-risk steps may execute under a valid narrow grant. Writes/external side effects require approval of the exact action and target.
6. Execute bounded steps with cancellation, time/token/cost limits and durable event streaming.
7. Verify postconditions with deterministic checks where possible; model review is supplemental, never sole proof.
8. Summarize outcome, failures, evidence and remaining work. Offer memory candidates; auto-save only low-risk user-approved memory policies.
9. Schedule continuations only when explicitly requested or when the user has approved a persistent goal and budget.

## Memory design

Use one local Room-backed store initially, with typed tables/records:
- episodic: task/conversation outcomes and evidence references;
- semantic: stable facts/preferences, source and confidence;
- procedural: approved skills/workflows, versioned and reviewed;
- working: short-lived task context and step state;
- project: decisions, constraints, current checkpoints.

Retrieval pipeline: exact/FTS search first, optional embeddings later; rank by relevance, recency, confidence and scope. Every memory item can be inspected, corrected, superseded or deleted by the user. Never turn untrusted page/repository content into system instructions.

## Tool and permission system

Keep and strengthen the existing `AgentToolRegistry`, `AgentToolExecutor`, `ToolInputSchema`, `CapabilityPolicy` and scoped grants:
- every tool declares typed schema, capability, risk, target resolver, timeout and output bound;
- target is derived by trusted adapter code from validated arguments, never accepted as model-authored authority;
- approvals bind to request ID + tool ID + exact canonical target + argument digest + expiry;
- critical actions always require per-invocation approval;
- all executions emit audit events;
- unknown tools and malformed schemas fail closed;
- secrets remain in Android Keystore and never enter model context or logs.

MCP is a connector protocol, not a permission bypass. Each MCP server is separately paired, allow-listed and scoped. Treat tool descriptions and results as untrusted.

## Model routing

- Keep the on-device model as the offline/private path.
- Optional cloud providers remain opt-in, individually selected, with clear data egress and cost disclosure.
- Separate model roles by workload (reasoning, cheap background classification/embedding, fallback) without loading duplicate large on-device model copies.
- The model adapter returns typed proposals; the kernel retains authority.
- Never claim local model capability is equivalent to a frontier cloud model.

## Android implementation strategy

Preserve Kotlin + Compose + Room + WorkManager. Refactor incrementally; do not delete existing working provider, security, voice, update or project capabilities before replacements pass tests.

1. Architecture inventory and migration map (this document).
2. Introduce kernel contracts: `AgentRequest`, `TaskPlan`, `PlanStep`, `AgentEvent`, `TaskBudget`, `VerificationResult`, `MemoryRecord`, `ToolRisk`.
3. Make existing task repository/state machine the durable source of truth; move orchestration out of ViewModel/UI.
4. Make all chat/provider paths submit requests to the same kernel. Remove provider-specific bypasses.
5. Unify tool discovery/execution under registry and policy; typed target resolvers and approval evidence.
6. Add task DAG executor, cancellation, retry/idempotency, budgets and deterministic verification.
7. Upgrade memory to typed, provenance-aware retrieval and user correction.
8. Add goal scheduler/continuation via WorkManager with Android background limits respected.
9. Add trace/eval suite and regression tests for prompt injection, replay, cancellation, duplicate execution, corrupted memory and offline fallback.
10. Rebuild Compose UI around one chat gateway plus visible task/activity/approval/memory/tools screens. No decorative feature without an operating backend.

## Existing SheV Agent assets to retain and integrate

The current main branch already contains useful foundations: `AgentTaskOrchestrator`, Room task/event entities, scoped capability grants, tool registry/executor and JSON schemas, provider adapters, local LiteRT-LM/ADK runtime, specialist definitions, model store verification, GitHub connector, update pipeline, workspace layout policy, voice contracts and a substantial test suite. Reuse these only after verifying they fit the kernel contracts. Do not assume the current ADK root agent is the desired authority model.

## Explicitly out of scope / remove from the product direction

- ChatGPT cloud acting as the permanent chief/supervisor.
- Chat agent as the manager of the whole system.
- Browser-first product direction or a WebView shell as the central architecture.
- Arbitrary shell/code execution on the Android device.
- Unbounded autonomous loops or silent spending.
- Automatic self-modification of policies, credentials, system prompts or app binaries.
- Copying entire reference projects or adding heavyweight runtimes without a measured Android need.

## Definition of done

A task is not complete because a screen exists. It is complete only when:
- a user request flows through the kernel;
- plan/steps are persisted and visible;
- all tool calls pass schema, capability, approval and scope checks;
- cancellation and app restart behave correctly;
- results are verified and tied to evidence;
- memory retrieval is relevant and correctable;
- offline and provider-failure paths are honest;
- automated tests pass and a signed APK is produced;
- physical-device smoke tests pass on the user's Android phone.

## Known architectural conflict to resolve

`docs/HYBRID_ARCHITECTURE.md` previously described ChatGPT as supervisory intelligence. That decision is superseded by this document. Do not delete historical notes; mark them superseded and migrate any still-useful protocol contracts into the kernel design.
