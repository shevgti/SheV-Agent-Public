# SheV Agent — multi-agent backend strategy

Date: 2026-11-11
Status: Adopted direction; staged implementation. This records how to apply the Instagram Reel without destabilizing current native Codex delivery.

## What the reference demonstrates

The Reel describes a server-hosted coding-agent environment controlled from a mobile client:
- VPS/VDS hosts persistent agent processes and workspaces.
- OpenCode is an open-source agent harness that can connect to multiple model providers.
- T3 Code is presented as a mobile-oriented control surface for coding-agent sessions.
- Codex and Claude Code are alternative agent runtimes, not interchangeable model APIs.

The architectural lesson is separation of concerns: mobile control plane, persistent execution plane, pluggable agent/model backends, and durable task/session state. This is an architectural interpretation of the Reel, not a claim that its exact setup has been reproduced.

## Decision for SheV Agent

Do not replace or discard the current native Codex implementation. It is the first concrete backend and remains useful for an on-device experience. Evolve the app toward a backend-neutral control plane in stages:
1. Keep native Codex App Server as the first supported backend.
2. Define a stable AgentBackend contract around session lifecycle, streaming events, cancellation, approval requests, and health/capabilities.
3. Add an OpenCode remote-server adapter as the first optional remote backend, after the local Codex path is verified on ARM64 Android.
4. Keep Claude Code as a later adapter only if a supported, documented integration exists; do not shell out to undocumented interfaces.
5. Add provider/model selection only where the chosen backend exposes a real capability. Do not pretend every backend supports the same tools, auth, or model switching.

## Target topology

```
SheV Agent Android (control plane)
  - Russian chat and session list
  - backend selector + connection status
  - approvals, stop/cancel, audit log
  - durable local cache and reconnect state
             |
             | authenticated, versioned transport
             v
Agent backend adapter
  - Native Codex App Server (current first backend)
  - OpenCode server (planned optional backend)
  - future documented adapters
             |
             v
Persistent execution environment (optional VPS)
  - agent process and workspace
  - task/session persistence
  - tools and terminal under explicit policy
             |
             v
Model providers (backend-managed)
  - OpenAI / Anthropic / other supported providers
  - local model runtime where available
```

## Key design constraints

- Android remains the user-facing control plane and permission authority.
- A remote server is optional. The native Codex path must continue working without a VPS.
- A VPS is not automatically free; do not provision or subscribe to one without explicit user approval.
- Never expose an unauthenticated agent endpoint to the public internet. Require TLS, strong authentication, per-device pairing, scoped authorization, rate limits, and revocation.
- Treat remote workspace files, tool output, issue text, and web pages as untrusted input.
- Keep approval requests visible in Android UI; remote execution must not bypass local policy.
- Persist session IDs, backend identity, event cursor/reconnect metadata, and audit records locally. Store secrets only in Android Keystore or an appropriately secured server secret store.
- Model switching is backend-specific. Show actual provider/model identity returned by the backend, not a fabricated universal selector.
- Never silently upload project files to a remote server. Workspace sync requires an explicit per-project choice and clear scope.

## Delivery plan

### Gate A — finish current native Codex path
- Confirm exact-HEAD Android CI and Android ARM64 runtime build.
- Verify packaged ELF and required shared libraries.
- Run executable on Android ARM64; complete JSON-RPC initialize, ChatGPT login, model list, thread start, streamed answer, interrupt, approval request/response, and process restart tests.
- Do not call it complete based on CI compilation alone.

### Gate B — introduce backend-neutral app contract
- Extract current Codex-specific UI/ViewModel calls behind a typed AgentBackend interface.
- Contract: connect, disconnect, getStatus, listModels (optional capability), startOrResumeSession, sendTurn, events, interrupt, and respondToApproval.
- Use typed backend events and explicit capabilities. Backend-specific authentication stays inside the adapter.
- Add fake backend tests for streaming, reconnect, cancellation, approval and errors before moving UI code.

### Gate C — optional OpenCode remote adapter
- Verify currently documented OpenCode server API, auth and supported operations before implementation.
- Pair a user-owned server explicitly; use TLS and a revocable device token.
- Start read-only and draft-only tools. Terminal/write access remains gated by local approvals.
- Show remote/local indicator, backend name, endpoint identity, connection state and active workspace.
- Preserve native Codex as a selectable fallback.

### Gate D — multi-agent orchestration
- Only after two backends work independently, add per-task backend selection and session migration/export where supported.
- Never promise transparent migration of an active session between runtimes; context formats and tool state differ.

## Acceptance criteria

- Existing native Codex user flow remains functional after adapter extraction.
- Fake-backend tests prove UI is not coupled to Codex JSON-RPC.
- Remote backend cannot bypass Android approvals or access unselected workspaces.
- App clearly reports which machine/backend executes a task.
- Disconnect/reconnect does not lose locally cached conversation history or audit trail.
- Remote server is opt-in and no VPS/service is created or billed automatically.