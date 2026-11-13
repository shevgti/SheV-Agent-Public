# SheV Agent Factory v2 — implementation contract

Status: active redesign, not a claim of completion.
Branch: `feature/shev-agent-factory-v2`

## Product definition

SheV Agent is an Android-first factory that designs, tests, versions, activates, runs, monitors, stops and improves specialist agents. A saved Markdown prompt is only an export artifact; it is not a working agent.

## AI sources: explicit and truthful

### Existing sources in the repository
- LiteRT-LM local model: downloaded model artifact (currently around 2.58 GB in the app UI), runs on the phone. This is the ONLY model source currently wired into Agent Creator.
- Codex App Server: separate native process and ChatGPT account login integration under PR #32. This is intended to use the signed-in ChatGPT account, but the complete ARM64 launch/OAuth/inference flow must be proven on device.
- OpenAI API, Gemini, OpenRouter, OmniRoute: optional provider clients exist for the main chat. They have their own credentials, billing/eligibility and network conditions. Agent Creator does not currently route to them.

### Required redesign
Introduce `AgentModelProvider` / `AgentModelSession` abstraction with:
- provider id, model id, local/remote classification, readiness and auth status;
- generate structured JSON and stream text;
- cancellation, bounded context and token/call budgets;
- provider-specific capability disclosure.
Agent Creator must use the selected eligible provider and show the exact provider/model before generation. Never claim that a ChatGPT subscription pays for OpenAI API. Prefer the user's already-authorized Codex App Server when verified; otherwise local LiteRT-LM; external API providers only when the user explicitly configures them.

## Tool system: actual inventory vs planned integrations

At the time of redesign the creator validates against three host tool IDs only:
- `agent.inspect_tools`
- `agent.current_local_time`
- `agent.create_task_draft`

The first is read-only catalog inspection, the second reads device local time, the third creates a draft requiring user approval. The app also has a tool workshop, GitHub Actions connector, model provider panels and Codex approval path elsewhere in the application, but these are NOT automatically exposed to generated agents.

Required tool architecture:
1. Discover tools from the real host `AgentToolRegistry`; show title, description, input schema, capabilities, connection state and risk tier.
2. Separate tool definitions from actual tool adapters. A descriptor without an executable handler must be marked unavailable, never offered as usable.
3. Each agent gets an explicit allowlist selected by user from available tools. The model may recommend tools but cannot add/grant them.
4. Every invocation rechecks allowlist, capability, scope, approval, timeout, output size and audit logging in host `AgentToolExecutor`.
5. Tool workshop can author/update adapters only as reviewed drafts; no generated Kotlin, shell or arbitrary code is executed inside the app.
6. Display missing integrations clearly (browser/search, file workspace, GitHub, calendar, messaging, etc.); never invent tools.

## Runtime architecture

Reuse existing Room database, `AgentTaskOrchestrator`, `AgentToolRegistry`, `AgentToolExecutor`, capability policies and current model runtime. Do not create a parallel permission system.

Introduce:
- immutable `AgentVersion` snapshots of manifest + model/provider preference + tool allowlist + evaluation evidence;
- `AgentRuntimeRegistry` for ACTIVE/DISABLED definitions;
- `AgentSession` with conversation, run state, cancellation and event stream;
- `AgentEvaluator` running the manifest's evaluation cases with a separate judge/checker and deterministic assertions where possible;
- explicit lifecycle: DRAFT -> EVALUATING -> REVIEW_REQUIRED -> APPROVED -> ACTIVE -> PAUSED/DISABLED;
- per-run limits and invocation-time authorization;
- run history, tool-call timeline, errors, model/provider identity and evidence;
- enable/disable, version compare, rollback and emergency stop.

A generated specialist is an ADK sub-agent / constrained runtime configuration sharing the already-loaded model where possible. It must not load a duplicate 2.58 GB model. Specialist prompt and policy are immutable for the run. Sub-agents cannot grant permissions, modify their own policies or activate themselves.

## Main UI: Russian-first factory

Default screen must visibly contain:
1. AI status: selected provider/model, connected/not connected, local/remote, setup action.
2. Tool catalog: all real tools, what each does, availability, risk, permissions and test action.
3. Agent Creator conversation: natural-language request, clarifying questions, draft creation.
4. Evaluation panel: test cases, pass/fail, evidence, failure explanation and regenerate/fix loop.
5. Agent library: Draft / Needs approval / Active / Paused; open, test, edit new version, run, stop, disable, rollback.
6. Run console: live reasoning summary (not hidden chain of thought), progress, selected tools, approvals, output and logs.
7. Settings: model provider, skills, tool permissions, memory scopes, budgets and data boundaries.

No screen should say "agent created" when only a manifest or Markdown file exists. Use exact status labels.

## Build milestones and acceptance

### F0 — honest foundation (current slice)
- Make model source and actual available tool inventory visible in Russian UI.
- Document current limitations.
- Add tests that Creator cannot claim external tools or activation.

### F1 — model provider routing
- Implement provider-neutral creator interface.
- Codex path only after real native process, OAuth, model list and inference are verified.
- Local model fallback and optional user-configured providers.
- UI shows exact active provider/model; no silent fallback.

### F2 — real tool catalogue and binding
- Connect UI to registry descriptors + executable adapter availability.
- User selects allowlisted tools for a draft.
- Add host-side policy enforcement and per-invocation approval.

### F3 — evaluator and versioning
- Execute evaluation cases in a constrained sandboxed harness with synthetic fixtures.
- Store immutable evidence; require user approval after passing.
- Failed tests keep agent non-active.

### F4 — specialist runtime
- Activate reviewed definitions into runtime registry.
- Run/stream/cancel sessions using existing model + executor.
- Route tools only through host executor and per-agent allowlist.
- Add audit, disable and rollback.

### F5 — teams / orchestration
- Main coordinator delegates to active specialists, bounded by task budget.
- Shared memory only through explicit scoped adapters.
- Show delegation graph and individual results.

## Security invariants
- No API keys, cookies, ChatGPT tokens or credentials in prompts, manifests, logs or exported files.
- No arbitrary shell or dynamic code execution.
- No agent self-activation, self-permission, or permission escalation.
- No network access unless a real adapter is enabled and user-approved.
- Destructive, financial, external communication, publish/release and permission actions require explicit confirmation for the exact invocation.
- User can stop all runs and revoke tool grants.
- Treat model output and tool output as untrusted input.

## Missing source material
The Instagram tutorial video referenced in prior conversations is not currently present in the accessible files for this task. The redesign above is grounded in the actual repository source and existing architecture docs, not a claim that the exact video was re-read. If the exact clip is reattached, compare its workflow and tool stack against this contract before finalizing.


## F4 implementation update — first constrained runtime slice (2026-11-13)

Implemented on the existing feature branch:
- An explicitly approved specialist can now be run manually from its card.
- The runtime creates an isolated ADK session and a specialist runner that reuses the already-loaded LiteRT-LM model; it does not load a second copy of the model.
- The first runtime is deliberately tool-free, offline, and side-effect-free. The model receives only the manifest role/instructions/process/output format and the user's task.
- Input is capped at 8,000 characters, output at 16,000 characters, model calls at the manifest's bounded limit, and timeout at 10–600 seconds.
- The run policy revalidates the manifest and rejects DRAFT, tool-bearing, network-enabled, or side-effect-enabled agents.
- The UI makes clear that approval does not create a permanently active autonomous agent. Each run is a manual invocation.

Still NOT implemented:
- per-agent tool binding/allowlist enforcement;
- behavioral evaluator of generated specialists;
- immutable version snapshots and rollback;
- persistent ACTIVE/PAUSED runtime registry;
- run event/audit persistence and multi-agent orchestration.

This is a real first execution path for pure reasoning specialists, not the completed factory. CI and device-level model execution must pass before claiming the feature works on a phone.


## F2 implementation update — executor allowlist primitive (2026-11-13)

Added to the host executor:
- `executeForAgent(agentAllowedToolIds, request, context)`
- `executeSuspendingForAgent(agentAllowedToolIds, request, context)`
- Both reject a tool ID not present in the agent's approved run allowlist snapshot with `AGENT_ALLOWLIST_DENIED` before looking up or invoking the handler.
- If the tool is allowlisted, the existing registry, schema, capability, scoped-grant and approval checks still run unchanged.
- Four tests cover denied unselected tool despite a global capability grant, empty allowlist, selected tool continuing through normal checks, and suspend-handler denial.

Important boundary: this is the executor enforcement primitive and its unit tests are authored; it is NOT yet wired into the generated-specialist ADK runtime or a user-facing tool-selection/grant flow. `AgentRunPolicy` still deliberately rejects tool-bearing specialists. Do not advertise tool-using specialists as available until the runtime routes every ADK invocation through these per-agent methods and a user-approved capability context.

Commits:
- `eadd2c5f1b1804b99b06c9d6ae3b24d72c3ccbcb` — tests first.
- `bcad2b9571080f76fb06e5125b9d27727811b8d2` — executor implementation.
- `$(toolWrite.result.commit_sha)` — comment precision.

Verification:
- Diff reviewed for the two commits.
- Unit tests not yet executed; no successful CI result is available for the latest head.
- Prior run #525 / `43459869410` was cancelled during the ARM64 Rust build when later commits updated the PR branch. This is cancellation, not a compiler/test failure.


### F2 follow-up — ADK adapter accepts the agent allowlist (2026-11-13)

- `SheVAgentAdkTools` now accepts an optional `agentAllowedToolIds` snapshot.
- When supplied, all three annotated ADK functions route through `AgentToolExecutor.executeForAgent` / `executeSuspendingForAgent`; none of those adapter entry points bypasses the allowlist.
- A non-null allowlist is intended for generated specialists. The null default is retained only for existing host-owned callers to preserve their current behavior.
- Added a unit test that calls the real ADK adapter with an empty allowlist and confirms the registered handler is not invoked and the adapter reports `AGENT_ALLOWLIST_DENIED`.
- This is still not end-to-end specialist tool execution: the specialist runtime currently passes `tools = emptyList()`, `AgentRunPolicy` rejects tool-bearing manifests, and there is no user-facing tool grant flow. Do not relax those restrictions yet.
- Test fixture was corrected to use a valid optional-field schema; `ToolInputSchema` intentionally rejects empty field lists.
- Latest commits: `14f2d4d9be835e0dd8a59fcf468e650f50c8e002` (adapter routing), `283bdfe78031778f8e58e2bd95f9c1d828ad51e6` (route all three ADK entry points), `9b11fa1320b20e85566720faaacc3e20db119cf9` (valid test schema).
- CI remains unverified for the latest head; the previous #525 run was cancelled and no replacement run is currently visible through the connected GitHub workflow-run query.


### F2 security hardening — immutable adapter allowlist snapshot (2026-11-13)

- Added a regression test that passes a mutable tool-ID set to `SheVAgentAdkTools`, clears that caller-owned set after adapter construction, and asserts the tool remains denied and its handler is never invoked.
- The adapter now copies the supplied IDs with `toSet()` at construction and uses only that private snapshot for all synchronous and suspending ADK calls.
- This closes a time-of-check/time-of-use permission escalation path where caller mutation could otherwise grant a new tool during a live session.
- Test-first commit: `9cb2e582c981797a28ba5457f01c1ccd6d9f74f1`; implementation commit: `066505b23cd0ad9dcffae187e0b2a271b04fc8b8`.
- Diff reviewed. Tests are authored but not yet executed; no successful CI result for this change is available.
- The existing exact-head run #533 (`43461225559`) is still building ARM64 Codex App Server from an earlier PR head; steps through Rust toolchain, Android linker, dependency patch and cache succeeded, while the native Cargo build remains in progress. It does not verify these latest Kotlin changes.
