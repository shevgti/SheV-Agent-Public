# SheV Agent: hosted agent backend research

Updated: 2026-11-10

## Objective

Give the Android app a genuinely capable, persistent agent without asking the user to manually create or paste a model API key. Keep the existing Gemma offline fallback and current Gemini / OmniRoute settings intact.

A repository can contain the agent runtime and integration code, but GitHub does not itself provide free, persistent model inference. A hosted agent still needs a legitimate model entitlement and a machine/process that stays alive. GitHub Actions are ephemeral jobs, not a persistent interactive agent host.

## Candidates inspected

### 1. OpenAI Codex App Server — strongest protocol fit
- Repository: https://github.com/openai/codex
- App-server docs: https://learn.chatgpt.com/docs/app-server
- OpenAI engineering architecture: https://openai.com/index/unlocking-the-codex-harness/
- Provides a bidirectional JSON-RPC protocol, thread lifecycle, persistent conversations, streamed agent events, tool execution, approval requests, model discovery and built-in Sign in with ChatGPT.
- Official docs recommend app-server for embedding Codex into a product. Remote WebSocket listener is supported; non-local use must use authentication and TLS.
- This is a real agent harness, not just a model proxy. It is the preferred first prototype for a SheV remote-agent connector.
- Important: app-server is a runtime, not a hosted service. It must run on an always-on host (user-owned PC/server/cloud VM). OAuth login and actual Codex entitlement are provider-controlled. Do not assume that a third-party Android client automatically gets ChatGPT plan inference just because the user can sign into the first-party Codex client.

### 2. OpenHands Agent Canvas — strongest multi-agent control plane
- Repository: https://github.com/OpenHands/OpenHands
- It describes itself as a self-hosted, always-on developer control center and can orchestrate OpenHands, Claude Code, Codex, Gemini CLI, Pi and OpenCode via local, remote and cloud backends / ACP.
- Supports persistent sessions, agent backends, automations and integrations.
- Requires a host (Node.js 24+, uv; Docker recommended for sandboxing) and an agent/model backend. It does not remove model access requirements or provide free inference by itself.
- Good future option if SheV needs multiple specialist agents and workflows. Too large to embed directly in the Android app; integrate over a secured server API.

### 3. Qwen Code + qwen serve — promising daemon, not a free route
- Repository: https://github.com/QwenLM/qwen-code
- Daemon docs: https://github.com/QwenLM/qwen-code/blob/main/docs/users/qwen-serve.md
- Open-source CLI with tools, skills, subagents, MCP, SDKs and experimental HTTP/SSE daemon.
- Daemon can provide persistent sessions, reconnect-safe SSE, transcript retrieval, multi-workspace support and device OAuth flow for remote login.
- Current docs explicitly say the Qwen OAuth free tier was discontinued 2026-04-15; subscription sign-in requires Qwen Plus or Pro. Do not market this as free/no-subscription.
- Daemon is experimental. Non-loopback access needs bearer authentication and TLS; unauthenticated trusted loopback can grant full code-execution authority.

### 4. Agent Zero
- Repository: https://github.com/agent0ai/agent-zero
- Interesting autonomous agent framework with tools and subagents, but no demonstrated provider entitlement that solves the no-key requirement. Treat as an alternative runtime only, not the first integration.

## Decision

Prototype Codex App Server first as a remote agent backend. It most directly solves the missing agent-harness features: persistent threads, streamed events, tool execution, approvals and a first-party account login flow. Keep OpenHands Agent Canvas as the second-stage orchestration/control-plane candidate. Do not replace current providers or local Gemma until the prototype is working end-to-end.

## Proposed architecture

Android SheV client
  -> authenticated TLS WebSocket / persistent event stream
  -> SheV Gateway (thin relay, no model API key stored on Android)
  -> Codex App Server on a user-controlled always-on host
  -> provider login managed by Codex
  -> Codex agent tools in a sandboxed workspace

The Android app remains the source of truth for its own capabilities and permissions. Remote agent requests for phone actions must become typed proposals sent back to Android; Android validates them with AgentToolExecutor and requires explicit user approval for writes, deletes, publication, spending, security changes or device control. Never expose the remote agent with unrestricted Android permissions.

## Implementation gates

1. Confirm a practical always-on host. A GitHub repository alone is not a host. Do not use GitHub Actions as a fake always-on daemon.
2. Launch Codex app-server on that host and complete its supported ChatGPT sign-in flow in a browser. Verify model availability and rate-limit/plan entitlement from the server's own account methods.
3. Secure the transport: TLS, per-device bearer/session authentication, rotation/revocation, no public unauthenticated WebSocket.
4. Build a minimal protocol adapter in SheV: create/resume thread, send user turn, render streamed events, cancel turn, handle approval requests.
5. Add an app-owned tool bridge with a strict allowlist and explicit approval mapping. No arbitrary shell/file access on the Android device.
6. Test reconnect, interrupted turns, expired login, entitlement denial, server restart, rate limits, malformed events and denied approvals.
7. Only after end-to-end validation, offer it as a selectable provider and preserve Gemma/Gemini/OmniRoute as fallbacks.

## Current app work

- PR #26 already merged: full-width settings, bottom Back button fixed, updater moved to top.
- PR #27 adds opt-in OmniRoute Responses function calling for three bounded local tools. It now includes a fallback to ordinary Chat Completions when the gateway returns 404/405/501, so unsupported gateways should keep ordinary chat working. This still requires CI and endpoint verification and does not remove the gateway API key.
- The direct ChatGPT SIWC/Responses API route is not the first choice for this app because the OAuth client registration, scopes, entitlement checks and streaming constraints add a separate integration burden. Codex App Server already owns the Codex login and agent protocol.

## Non-goals / security

- Do not scrape, steal, reuse or distribute third-party API keys, cookies, refresh tokens or shared accounts.
- Do not claim that GitHub hosting, a public repository, or a free model catalog provides free inference.
- Never store OAuth credentials in source control, GitHub Actions logs, Android preferences in plaintext, or an unauthenticated remote endpoint.
- Never silently downgrade an action requiring approval.

## Newly verified Android-native route: official Codex in Termux

The Termux community fork is now marked end-of-life because Codex has become an official Termux package:
- Official Termux package recipe: https://github.com/termux/termux-packages/tree/master/packages/codex
- Community fork migration notice: https://github.com/DioNanos/codex-termux
- The current Termux package recipe builds the upstream OpenAI Codex source for Android ARM64; it is not a pirated binary or a third-party model proxy.
- Installation from the Termux package repository is documented as `pkg install codex`; then `codex login` can start the supported ChatGPT sign-in flow. Actual model access still depends on account entitlement/rate limits. Codex CLI README currently recommends Plus, Pro, Business, Edu or Enterprise for plan login; do not assume Free can use the CLI just because the first-party Codex product has limited Free availability.
- The community package `@mmmbuto/codex-cli-termux` is EOL; do not base a new integration on it. The separate `codex-vl` fork offers extra `/remote-control` commands but is experimental and community-maintained; do not bundle it into SheV.

This creates a useful fallback to a cloud VM: run Codex locally on the user's Android ARM64 phone inside Termux. It is likely the lowest-infrastructure prototype, but it still needs an Android-to-Termux bridge and battery/background reliability testing.

### Android bridge feasibility
- Codex App Server supports stdio JSONL as its default transport. Its WebSocket transport is currently described as experimental/unsupported in official docs; never expose an unauthenticated listener on the LAN.
- Prototype a local Termux-side Node.js bridge that spawns `codex app-server --listen stdio://`, exposes only a loopback HTTP/WebSocket endpoint with a random per-install bearer token, and translates SheV's thread/turn/event/approval operations to the app-server JSON-RPC protocol.
- SheV Android app should connect only to loopback, store the random token in Android Keystore, and verify the bridge process is alive. If Termux is stopped by Android battery management, show reconnect status and retain Gemma fallback.
- Alternative app-to-Termux invocation via Android RUN_COMMAND permission must be opt-in and explicitly documented. Never attempt to read Termux's private files or OAuth token from the SheV app.
- First test manually in Termux before implementing Android UI. The user must install Termux from a trusted official source and run the official package installation/login once; ChatGPT credentials remain inside Codex's own credential store.

### Updated implementation priority
1. Test official Termux Codex install/login on the user's Poco device (no API key).
2. Start app-server over stdio and validate thread/start, turn/start, streamed events, cancel, and approval request/response.
3. Write the minimal local Node bridge and test reconnect/auth.
4. Add SheV Android connector to that bridge.
5. Consider OpenHands Agent Canvas only if multi-agent orchestration becomes necessary; it is too heavy for the initial phone-only setup.
