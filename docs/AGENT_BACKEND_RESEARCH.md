# SheV Agent — no-API-key agent backend research

Updated: 2026-11-10

## Decision: use official Sign in with ChatGPT inside the open-source Android app

OpenAI now documents a direct OAuth flow for open-source and locally hosted apps to use an eligible user's ChatGPT plan for Responses API inference. This is the best fit for SheV: no manually created API key, no Gemini, no third-party gateway, no Termux, and no separate always-on server.

Official documentation:
- Overview: https://developers.openai.com/siwc/token-sharing-open-source
- Registration/sign-in: https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- Accounts/sessions: https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- Models/inference: https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- Preview limitations: https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

### How the route works

1. SheV creates and persists an opaque `ext_agent_host_id`.
2. First sign-in uses `client_id=dynamic_agent_client`, `agent_name_hint`, PKCE, nonce and state.
3. The Android app starts a loopback callback on `127.0.0.1` and opens the system browser.
4. The user signs in and explicitly consents to ChatGPT plan usage.
5. OpenAI redirects to the exact loopback callback with an authorization code and issued `client_id`.
6. SheV exchanges the code using PKCE; no client secret or API key is required for this open-source flow.
7. SheV validates the ID-token signature against OpenAI's JWKS, issuer, audience, nonce and expiry, and verifies the granted `chatgpt.tokens.use.direct` scope.
8. Access, refresh and ID tokens are encrypted locally using Android Keystore. Refresh-token rotation is handled in the app.
9. Inference uses only the public `POST https://api.openai.com/v1/responses` endpoint with `store:false` and `stream:true`. Do not use ChatGPT `backend-api` endpoints.
10. The app uses namespaced client-side SheV functions; every function call is validated by the app-owned tool executor.

### Hard boundaries

- OpenAI documents this token-sharing route for open-source and locally hosted apps. Paid or remotely hosted products must use the separate OpenAI interest/partner route.
- Keep the integration in the public SheV Android client and keep all OAuth tokens on the device. Do not build a shared hosted token proxy.
- OAuth consent does not guarantee inference eligibility. The selected account, workspace, region, model and current usage limits are checked by OpenAI. A real completed Responses turn is the final proof of access.
- ChatGPT plan usage is separate from access to ChatGPT conversation history. SheV only requests the documented inference permission.
- On this route, Responses requires `store:false` and `stream:true`. Avoid unsupported parameters and tools listed in the Preview Limitations page. Use namespaced functions for the small local tool set.
- On disconnect, revoke the renewable refresh session when possible, then clear local credentials. Never log or commit tokens.

## Current implementation: PR #31

https://github.com/shevgti/SheV-Agent-Public/pull/31

Branch: `feat/chatgpt-plan-oauth-android`

The current PR adds:
- Native Android OAuth with loopback callback, PKCE, dynamic client registration, state/nonce and JWKS-backed ID-token validation.
- Encrypted Android Keystore storage and refresh-token rotation.
- A simple in-app **Войти через ChatGPT** flow; the API-key entry UI is removed.
- Account-specific model discovery.
- A direct ChatGPT plan Responses path with SSE completion/error handling and the existing bounded SheV local tools.
- No Termux, command-line setup, remote host or API key.

CI and real account sign-in/inference testing are required before release.

## Alternatives researched, not selected

### Codex App Server
- https://github.com/openai/codex
- https://learn.chatgpt.com/docs/app-server
- Strong agent runtime: persistent threads, streamed events, approvals and local tools.
- It is a runtime, not hosted compute. It requires a host that stays running.
- Its default transport is stdio JSONL; WebSocket is experimental/unsupported. Remote access needs authentication and TLS.
- The official open-source SIWC route above is simpler for this Android-only client, so a separate Codex runtime is not needed for the first release.

### OpenHands Agent Canvas
- https://github.com/OpenHands/OpenHands
- Self-hosted orchestration/control plane for multiple coding agents and remote backends.
- Requires a persistent host and separate model entitlement. Too heavy for the initial Android-only setup; revisit only if SheV later needs multi-agent orchestration.

### Qwen Code / qwen serve
- https://github.com/QwenLM/qwen-code
- Capable agent CLI with skills, subagents, MCP, SDK and an experimental HTTP/SSE daemon.
- Current docs say free OAuth ended 2026-04-15; subscription sign-in requires Qwen Plus or Pro. Not a no-cost entitlement workaround.

### Agent Zero
- https://github.com/agent0ai/agent-zero
- Interesting autonomous agent framework, but no verified model entitlement that solves the no-key requirement.

## Infrastructure facts

- A GitHub repository stores source code; it does not provide inference or a permanent interactive agent process.
- GitHub Actions are ephemeral CI runners, not an always-on agent host.
- GitHub Codespaces time out after inactivity and have a 12-hour maximum lifetime: https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle
- Do not use shared/pirated API keys, scraped cookies, stolen refresh tokens, or unofficial token relays.
