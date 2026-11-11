# SheV Chat — official ChatGPT companion for Android

Date: 2026-11-11
Status: product direction pivoted by user. No OpenAI API in the primary path.

## User requirement

The user wants to talk to the same official ChatGPT app/account used in this chat. SheV Chat must be a small Android companion that launches and assists the official ChatGPT client, keeps project continuity in SheV-Memory, and reduces manual context recovery. The user does not want to configure API keys, billing, servers, SDKs, or developer credentials.

## Hard platform limits — never misrepresent

- A third-party Android app cannot embed the private official ChatGPT app UI as a supported in-process component.
- There is no supported public API for a third-party app to read the user's official ChatGPT private conversation history, silently create chats in that account, or control its internal new-chat operation.
- Android does not allow an app to silently grant itself Accessibility permission or install/update itself without the user's system confirmation.
- A WebView login wrapper is not equivalent to the official ChatGPT app, may be blocked by authentication/security controls, and must not be used to collect credentials or session cookies.
- Do not scrape private endpoints, reverse engineer undocumented APIs, inject into the official app, or automate account access in ways that bypass platform security.

## Chosen primary approach: companion, not replacement

SheV Chat is a control panel around the official ChatGPT app:
1. Launch the installed official ChatGPT Android app via its package launch intent.
2. Let the user select a project (ФотоПорядок, SheV Agent, Моя память).
3. Prepare a concise project-specific continuation prompt from the locally cached, confirmed SheV-Memory checkpoint.
4. Copy that prompt to Android clipboard; user pastes it into ChatGPT. Do not claim that the prompt was automatically submitted.
5. Provide shortcuts to open SheV-Memory repository/checkpoint and a visible last-synced status.
6. Keep local notes and a durable task/checkpoint ledger so the user can continue even when ChatGPT starts a new chat.
7. Future optional AccessibilityService may be investigated only as a transparent opt-in convenience after testing against the official app. Android Settings requires the user to enable it manually. It must not inspect arbitrary private UI, capture passwords, or claim guaranteed stability. Default functionality must work without this permission.

## User setup minimization

- Do not require API keys, API billing, backend server, Firebase, OAuth app registration, or manual source editing.
- User's unavoidable actions: install the generated APK and, if optional accessibility automation is later approved, explicitly enable the Android Accessibility service in system Settings. We cannot perform these actions remotely or silently.
- Prefer one-tap actions, clear Russian labels, and no developer-facing configuration.
- If ChatGPT is not installed, show a direct Google Play listing intent/link rather than downloading an APK from an untrusted source.

## Current implementation in this branch

- Standalone Android module `:shevchat`, applicationId `com.shev.chat`.
- Russian-first Jetpack Compose shell.
- Project selector.
- Button to launch package `com.openai.chatgpt` if installed.
- Button to prepare/copy a project continuation prompt to clipboard.
- Clipboard prompt currently directs ChatGPT to read the SheV-Memory ACTIVE-CHECKPOINT file.
- No API client, no API credentials, no API billing, no WebView login, no private ChatGPT session scraping.
- The draft chat bubbles in this screen are not the real ChatGPT conversation; they must be removed or relabelled clearly. Do not make them look like messages sent to ChatGPT.
- Current clipboard handoff is a first working prototype, not full automatic chat management. New-chat creation and automatic transcript syncing are not supported by public interfaces.

## Next implementation steps

1. Fix CI and validate the separate APK builds.
2. Replace fake chat transcript UI with a compact companion dashboard: project selector, "Открыть ChatGPT", "Скопировать контекст проекта", memory sync indicator, latest checkpoint summary.
3. Persist selected project and last confirmed checkpoint locally (DataStore or Room); no ephemeral-only UI state.
4. Add a safe refresh/cache path for public SheV-Memory checkpoint; do not store GitHub credentials in APK. If private repository access is needed, use an explicit OAuth flow only if user approves; otherwise use a manually exported/public checkpoint.
5. Add "Новый чат" helper: open official ChatGPT and copy a project-aware start prompt. It cannot guarantee the official app opens a blank conversation; explain this.
6. Investigate accessibility as an optional opt-in only. If not reliable on current ChatGPT version, keep clipboard workflow.
7. Build and install-test on user's Android device only after CI artifact succeeds. Never claim a build/device test without evidence.

## Acceptance criteria

- SheV Chat launches official ChatGPT app when installed.
- Project selection changes the exact context copied to clipboard.
- The app never presents local draft bubbles as a real ChatGPT conversation.
- No API keys, API billing, backend server, or user developer setup required.
- Context can be prepared offline from last confirmed local checkpoint.
- Every sync has explicit success/failure status; no false "saved" claims.
- Accessibility is not mandatory for core functionality and is never enabled without user consent.
- All limitations around official chat control are stated clearly.
