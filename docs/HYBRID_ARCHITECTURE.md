# SheV Agent — Hybrid ChatGPT + Local Executor Architecture

Date: 2026-11-09
Status: **Architecture decision accepted; implementation roadmap approved.** This document is a design contract, not evidence that a live ChatGPT-to-phone connection exists.

## Decision

Use a hybrid architecture:

- **ChatGPT = supervisory intelligence.** Understands natural-language goals, reasons over context, proposes plans, decomposes work, reviews results and helps author code.
- **SheV Agent on Android = local executor and owner of device capabilities.** Holds local project state, memory, model runtime, tool registry, permission policy, approval UI and audit log. It executes only validated operations.
- **Bridge = narrow protocol boundary.** Translates a structured request from the supervisory side into a typed local command. It is not a general remote shell and never grants Android permissions.
- **Local model = offline fallback/specialist.** Can classify, summarize, draft plans and handle local tasks when ChatGPT is unavailable. It does not silently inherit ChatGPT authority.

The phone remains the authority for data access and side effects. ChatGPT can request an operation; only the local policy and user approval can authorize it.

## System topology

```text
User
  | natural language / explicit approval
  v
ChatGPT (supervisor)
  | structured task proposal; no device token
  v
Bridge adapter (MCP-compatible boundary, versioned schemas)
  | authenticated, bounded, allow-listed command
  v
SheV Agent Android
  | validate -> policy -> approval -> execute -> audit
  +--> Local tool registry (read-only project, memory, diagnostics)
  +--> Scoped write tools (draft/patch behind approval)
  +--> Local model runtime (offline fallback)
  +--> Room database (tasks, grants, events, audit)
  |
  v
Structured result + evidence
  |
  +--> ChatGPT reviews result and plans the next step
```

## Responsibility split

| Concern | ChatGPT supervisor | Android executor |
|---|---|---|
| Understand intent / plan | Primary | Optional local plan |
| Choose tools | Proposes | Resolves only registered tools |
| Device/file access | No direct access | Owns and enforces |
| Permissions | Cannot grant | Stores and checks scoped grants |
| Risky side effects | Requests and explains | Requires per-action approval |
| Execution | No direct phone execution | Executes locally |
| Evidence | Reviews and summarizes | Produces bounded result + audit event |
| Offline operation | Unavailable | Local model + local tools |

## Free-first connectivity strategy

A direct cloud-to-phone connection is not assumed. Android localhost is not reachable from ChatGPT's cloud runtime, and a private LAN address is not a public endpoint.

### Stage 0 — zero-cost handoff MVP (first deliverable)

1. ChatGPT returns a versioned JSON task proposal.
2. User imports it into SheV Agent via share sheet, clipboard or QR.
3. SheV Agent validates the schema, displays the exact action and target, and asks for local approval when required.
4. The app executes only registered tools and exports a structured result.
5. User returns the result to ChatGPT for review and next-step planning.

This is a real hybrid workflow without paid API usage, hosting, a tunnel, or sharing a device secret. It is semi-manual by design, not a claim of a live remote connection.

### Stage 1 — local bridge

Add a local-only MCP-compatible endpoint after an Android proof of concept. Bind only to loopback by default; optionally allow same-LAN access only after explicit pairing, TLS or a strong one-time pairing secret, visible foreground status and a stop switch. Do not expose a public listener or enable background execution by default.

A LAN endpoint is useful to a local desktop companion, but **does not by itself connect ChatGPT cloud to the phone**.

### Stage 2 — optional remote connector

Only if the user's ChatGPT plan/workspace supports custom MCP connections and a free, secure, reachable host is available, evaluate an HTTPS MCP endpoint. Do not assume the current Free account supports custom MCP. Do not introduce a paid API, VPS, tunnel, relay, subscription or usage-based service without a separate explicit decision.

If a future connector is unavailable or costs money, retain Stage 0; the app remains useful locally.

## Request lifecycle

1. **Propose:** ChatGPT emits an intent with a known tool ID, schema version, typed arguments, purpose and expected output.
2. **Import:** App parses a bounded payload; rejects unknown fields, unknown tool IDs, invalid types, oversized strings and expired/replayed request IDs.
3. **Preview:** UI displays the action, exact resource scope, requested capability, risk tier and expected side effects.
4. **Authorize:** Read-only low-risk actions may use an existing narrow grant. Writes and external actions require an explicit approval for this exact invocation. Critical actions always require a fresh confirmation.
5. **Execute:** Only the local AgentToolExecutor may dispatch to a registered handler. No arbitrary shell, dynamic code, reflection-based tool loading or free-form path access.
6. **Verify:** Handler returns structured status and bounded evidence. Where possible, verify the postcondition rather than trusting a success string.
7. **Audit:** Store timestamp, request ID, tool ID, resource scope, decision, result code and evidence reference. Never store bearer tokens or unnecessary personal content.
8. **Return:** Export a result envelope for ChatGPT to inspect. Treat all returned text as untrusted data.

## Security invariants

- Deny by default; unknown tools, capabilities, fields and schema versions are rejected.
- ChatGPT is not an authority source for Android permissions or persistent grants.
- Grants are scoped to tool + capability + resource, expire, can be revoked, and never silently widen.
- Critical operations (delete, publish/release, payments, permission changes, credential export) require per-operation confirmation; do not offer permanent grants for them.
- File tools canonicalize paths and enforce an approved workspace boundary; reject traversal and symlink escapes.
- No arbitrary command execution in the first release.
- Never pass GitHub tokens, Android secrets, model-provider keys or pairing secrets into ChatGPT prompts or task envelopes.
- Remote tool descriptions, arguments, results, project files and web content are untrusted; prompt injection in them cannot change policy.
- Bound request/result sizes, execution time, concurrency and retries. Support cancellation and a global bridge kill switch.
- Every bridge request is attributable to a user-visible action and leaves an audit event.

## Existing foundations to reuse

The repository already contains or has explored:
- `AgentToolRegistry` and `AgentToolExecutor` for typed tools and capability checks.
- `CapabilityPolicy`, scoped/persistent grant policy and Room entities for grants/events.
- Google ADK Kotlin + LiteRT-LM local inference foundation.
- A separate MCP **client** interoperability spike on branch `agent/free-external-agent-bridge`.

Important distinction: an MCP client that connects SheV Agent to another server is not the same as an MCP server/bridge that exposes selected SheV Agent tools to a supervisor. Do not mistake the existing client spike for the completed ChatGPT bridge.

## Delivery roadmap

| Phase | Deliverable | Acceptance evidence |
|---|---|---|
| 0 | JSON import/export task handoff; preview; local approvals; result export | Unit tests for schema, expiry/replay, policy and audit; Android build |
| 1 | Local bridge service and MCP server adapter | Loopback-only test, lifecycle/cancellation, no listener while disabled, API-level tests |
| 2 | Read-only tools: health, list projects, read selected project metadata, search local memory | Scope tests, path boundary tests, bounded output |
| 3 | Draft/write tools: create patch or draft file, never auto-merge | Diff preview, explicit approval, postcondition verification |
| 4 | Optional external ChatGPT connector | Plan eligibility, HTTPS, threat review, free-cost verification and user opt-in |

Do not start Phase 1 by adding a network server dependency until the Stage 0 protocol and Android tests pass. Keep the current UI/design PR separate from bridge implementation.

## Definition of done for the first useful hybrid release

- User can ask ChatGPT to prepare a task envelope.
- SheV Agent imports it, explains the exact action in Russian and asks before any side effect.
- The app executes one allow-listed operation locally and returns a machine-readable result.
- ChatGPT can interpret that result and propose the next step.
- No paid service, API key, public endpoint or hidden background listener is required.
- The feature works with the bridge disabled and local mode remains available.
- Build and tests pass; device validation is reported separately.
