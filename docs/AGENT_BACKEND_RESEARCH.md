# SheV Agent — no-API-key AI agent research

Updated: 2026-11-10

## Current decision: embed the official Codex App Server in the Android APK

The user explicitly rejects Termux, command-line setup, manual API keys and remote server configuration. The selected architecture is to package the official OpenAI Codex App Server as an Android ARM64 native executable and launch it inside SheV's own app sandbox.

This is preferable to calling the ChatGPT plan-sharing Responses API directly:
- Codex App Server manages its own ChatGPT OAuth login, account session, refresh, model catalog, persistent threads, streaming events and approvals.
- Codex is available to ChatGPT Free users with limited usage; the model and request allowance are selected by OpenAI for the signed-in account.
- The Android app only needs an in-app **Войти через ChatGPT** button; no API key, Termux, external gateway or always-on host.
- The App Server communicates with Kotlin through stdio JSON-RPC. No WebSocket listener is exposed.

Official references:
- Codex source: https://github.com/openai/codex
- App Server protocol: https://learn.chatgpt.com/docs/app-server
- Codex plan availability: https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan
- Codex App Server protocol types: https://github.com/openai/codex/tree/main/codex-rs/app-server-protocol

### Android implementation design

1. CI cross-compiles the official `codex-app-server` Rust binary for `aarch64-linux-android` using the Android NDK.
2. The executable is packaged in the APK as `jniLibs/arm64-v8a/libcodex_app_server.so` and extracted to Android `nativeLibraryDir`.
3. SheV starts it with `--listen stdio:// --session-source shev_agent` using `ProcessBuilder`; the app talks JSON-RPC over stdin/stdout.
4. `CODEX_HOME` and workspace are inside `noBackupFilesDir`, protected by Android app sandbox and device file-based encryption; no tokens are sent to a separate server.
5. `account/login/start` opens the official ChatGPT sign-in page. The App Server owns the OAuth callback and token storage. SheV listens for `account/login/completed`, then reads account/plan and available models.
6. SheV creates/resumes a persistent thread and streams `item/agentMessage/delta` events into the chat UI.
7. The default Codex config is read-only with on-request approvals. The app shows explicit approval dialogs for command execution and file changes. No action is silently approved.

Important runtime gates:
- A cross-build alone does not prove the binary can execute on Android. Verify it launches from `nativeLibraryDir` on an Android emulator and a physical ARM64 device.
- Verify OAuth browser return, `account/read`, `model/list`, a real first turn, streamed deltas, cancellation and approval decisions.
- Android sandbox behavior differs from desktop Linux. Do not claim that desktop Landlock/seccomp isolation is available on Android until verified. The process always runs under the app's Android UID; the UI must show command/path approval details.
- Keep the App Server on stdio. Do not expose its experimental WebSocket listener on a network interface.
- Keep tokens in app-private no-backup storage. Never log or commit auth files.

## Current implementation PR

https://github.com/shevgti/SheV-Agent-Public/pull/32

Branch: `feat/codex-app-server-android-native`

Current draft adds:
- Kotlin JSON-RPC stdio client and process lifecycle.
- In-app ChatGPT login, account/plan/model status and primary-chat switch.
- Persistent thread start/resume, streamed response rendering, cancellation and command/file approval dialogs.
- Default read-only config with on-request approvals.
- CI probe for Android ARM64 native cross-compilation.

The binary is not yet packaged into the APK and real sign-in/inference has not yet been tested. Do not merge or publish until those gates pass.

## Secondary path: direct Sign in with ChatGPT OAuth

OpenAI also documents an OAuth token-sharing flow for open-source/local apps:
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

This direct Responses API path requires the `chatgpt.tokens.use.direct` scope and is limited to eligible ChatGPT plans (currently Plus/Pro for direct plan usage). It is not the right default for the user's ChatGPT Free account. PR #31 contains a draft implementation for eligible Plus/Pro accounts, but it is secondary and must not be presented as working for Free.

## Alternatives researched, not selected

### OpenHands Agent Canvas
- https://github.com/OpenHands/OpenHands
- Self-hosted orchestration/control plane for multiple coding agents and remote backends.
- Requires a persistent host and separate model entitlement. Too heavy for the initial Android-only setup.

### Qwen Code / qwen serve
- https://github.com/QwenLM/qwen-code
- Capable agent CLI with skills, subagents, MCP, SDK and an experimental HTTP/SSE daemon.
- Current docs say free OAuth ended 2026-04-15; subscription sign-in requires Qwen Plus or Pro. Not a free no-key route.

### Agent Zero
- https://github.com/agent0ai/agent-zero
- Alternative autonomous agent framework, but no verified model entitlement that solves the no-key requirement.

## Infrastructure facts

- A GitHub repository stores source code; it does not provide inference or a permanent interactive agent process.
- GitHub Actions are ephemeral CI runners, not an always-on agent host.
- GitHub Codespaces time out after inactivity and have a 12-hour maximum lifetime: https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle
- Do not use shared/pirated API keys, scraped cookies, stolen refresh tokens, or unofficial token relays.
