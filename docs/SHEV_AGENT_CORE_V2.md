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

## MCP catalogue review — mcpservers.org/ru (2026-11-17)

The directory is a discovery index, not an Android installer or a single SDK. Its categories and individual server pages were reviewed. The home page highlights official GitHub MCP, Context7, Playwright, Chrome DevTools, Exa, Firecrawl, E2B, Supabase and other integrations. Its memory category lists 137 servers, including local-first and provenance-aware options. The remote-server page lists hosted integrations such as GitHub, Context7, Notion, Linear, Supabase, Exa and others.

Useful candidates for SheV Agent:

1. **Context7** — official remote endpoint `https://mcp.context7.com/mcp`; version-specific library docs. Good first MCP connector for coding and API accuracy. The directory says an API key is recommended for higher limits. Keep credentials in Android Keystore; never place them in prompts or logs.
2. **GitHub MCP** — official remote endpoint `https://api.githubcopilot.com/mcp/`; repository/code/issues/PR/Actions and code-security tools. OAuth/PAT scopes are required; expose only user-approved tool sets. Existing native GitHub integration remains in place; MCP is an optional adapter, not a duplicate authority.
3. **Playwright MCP** — browser automation via accessibility snapshots, navigation, clicks, forms and screenshots. The catalog states Node.js 18+ is required and the common install uses local STDIO. This cannot be installed as a normal in-process Android library. Consider only a separately hosted, user-controlled remote deployment; not part of the first core release.
4. **Chrome DevTools MCP** — live Chrome inspection, console/network/performance and browser actions. Requires Node.js and Chrome/Chrome for Testing. Not an Android-native dependency. Use only as a remote developer/QA tool, not as the SheV Agent core.
5. **Memory category** — shortlist local-first/provenance-aware projects such as Simple Memory, State Memory, Talamus, GoodMemory, mneme and EleSync for design comparison. Do not outsource the first-party memory of the Android agent to a third-party cloud; Room remains the source of truth. Consider an optional sync/bridge only after conflict resolution, export, encryption and deletion behavior are defined.
6. **Search / retrieval** — Exa and Firecrawl are candidates for optional research adapters. Their output is untrusted evidence, never instructions. Use bounded retrieval and cite source URLs.
7. **Code execution / sandboxes** — E2B is a possible remote isolated execution service for later coding workflows, but requires network/account/possibly paid usage. No arbitrary local shell or code execution on the phone.

### Android MCP integration constraints and decision

- MCP is a protocol; entries in the directory are servers, not plug-and-play Android packages. Most community examples use STDIO + `npx`, which assumes a Node.js runtime and a process-spawning host. We will not bundle Node.js or a desktop runtime into the Android app merely to make those servers launch.
- The first transport to support is **remote Streamable HTTP over HTTPS**. Do not add new SSE-only integrations; current MCP Kotlin SDK documentation marks SSE deprecated in favor of Streamable HTTP.
- Official Kotlin client SDK candidate: https://github.com/modelcontextprotocol/kotlin-sdk (client-only artifact `io.modelcontextprotocol:kotlin-sdk-client`). SDK documentation shows `Client` + `StreamableHttpClientTransport` over Ktor and coroutine-friendly typed `listTools` / `callTool` APIs. Before adding dependency, verify Android compatibility, current SDK/Ktor versions, manifest/network constraints, auth headers, cancellation and protocol-version compatibility in a dedicated build commit.
- Each remote MCP server is separately paired and disabled by default. Discovering a tool does not authorize it. The user must approve each server and each tool allowlist. Every call is converted to a native SheV tool descriptor and sent through AgentToolExecutor / CapabilityPolicy / scoped grants / approval broker.
- Validate JSON schemas, cap input/output sizes, timeouts and concurrency; redact secrets; treat descriptions, prompts, resources and tool results as untrusted; never let a remote server alter kernel policies, memory rules or system instructions.
- Remote MCP is an optional adapter layer. The kernel owns planning, tool authority, permissions, task state and verification. MCP servers do not become supervisors or agents-in-chief.

Catalogue pages reviewed:
- https://mcpservers.org/ru/
- https://mcpservers.org/ru/remote-mcp-servers
- https://mcpservers.org/ru/category/memory
- https://mcpservers.org/ru/servers/context7-mcp-server
- https://mcpservers.org/ru/servers/github-mcp-server
- https://mcpservers.org/ru/servers/playwright-mcp-server
- https://mcpservers.org/ru/servers/chrome-devtools-mcp-server

### Initial MCP implementation in this branch

- Added `core/McpServerProfile.kt`: HTTPS-only remote server profiles, authentication/trust metadata, disabled-by-default curated Context7 and GitHub endpoints, and explicit per-server tool allowlists. This is configuration/policy scaffolding, not yet a connected MCP transport.
- Added `McpServerProfileTest.kt`: rejects plain HTTP and embedded URL credentials; verifies disabled-by-default and explicit tool allowlisting.
- Next implementation step: integrate the official Kotlin MCP client with Streamable HTTP after a dedicated dependency/Android compatibility build; implement secure credential retrieval and an adapter that maps MCP tools into AgentToolRegistry without bypassing policy.

## Agent Skills catalogue review

The directory has a separate Agent Skills library (not the same thing as MCP servers). It lists reusable instruction/code packages for specialist workflows and shows official collections from Anthropic, OpenAI, GitHub, Microsoft, Cloudflare, Figma, Vercel, Google Workspace, Notion and Stripe. Useful examples surfaced include repository research/reproduction, browser testing with DevTools, frontend design, and coding best-practice skills.

SheV Agent should support **declarative, versioned skills**, not blindly install or execute every item in the catalogue:
- skill manifest: stable ID, semantic version, description, trigger phrases, instruction text, source URL, SHA-256, trust class and required native tool IDs;
- skills are disabled until reviewed and explicitly enabled;
- skill text is untrusted guidance, never a system/developer policy layer;
- a skill cannot create tools, grant permissions, widen filesystem/network access, invoke shell or change approval requirements;
- required tools must already exist in the native registry and pass the kernel's normal capability/approval policy;
- community skill packages must be pinned to an immutable revision and checksum; updates require review;
- executable scripts, if ever supported, must run in an isolated remote sandbox with bounded resources and explicit user approval, never as arbitrary code inside the Android process.

Implementation added in this branch:
- `core/AgentSkillManifest.kt`: declarative manifest, version/source/checksum validation, trust class and explicit tool dependency gate.
- `AgentSkillManifestTest.kt`: validates version, HTTPS source, tool IDs and disabled/missing-tool behavior.

The catalogue's browser skills and Chrome DevTools recipes are useful for a later hosted browser/QA worker, not a reason to make the Android app browser-first. Keep the core tool loop small and expose skills progressively only when relevant.

### Remote MCP transport implementation update — 2026-11-17

The official Kotlin MCP SDK was checked against its immutable 0.16.0 release and version catalog:
- Kotlin SDK `0.16.0`, compiled with Kotlin 2.4.21 (matches this app's Kotlin Gradle plugin 2.4.21).
- SDK Ktor baseline `3.6.1`.
- Client artifact: `io.modelcontextprotocol:kotlin-sdk-client:0.16.0`.
- Android transport dependencies: `io.ktor:ktor-client-cio:3.6.1` and `io.ktor:ktor-client-sse:3.6.1`.
- Official SDK exposes `Client`, `StreamableHttpClientTransport`, `listTools()`, `callTool()`; requestBuilder can add auth headers. SSE transport itself is deprecated; the SSE Ktor plugin is used for event-stream parsing inside Streamable HTTP.

Implementation in branch:
- Added the three dependencies to `android/app/build.gradle.kts`.
- Added `core/RemoteMcpClient.kt`: HTTPS profile endpoint, credential supplied at connection time (not stored), Streamable HTTP transport, bounded tool discovery and result output, explicit profile allowlist, and mandatory per-invocation authorization callback supplied by the Agent Kernel before every remote tool call.
- Existing Android manifest already has INTERNET permission.
- The remote client is a transport adapter only. It is not yet wired to UI, persistent credential storage, native tool schema conversion or a production OAuth flow. Those remain required before enabling Context7/GitHub in the app.
- CI started for dependency and client commits; check the exact latest branch head before claiming a pass. Do not merge PR #63 yet.
