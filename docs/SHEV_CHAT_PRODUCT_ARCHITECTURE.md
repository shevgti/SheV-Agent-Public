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
3. Prepare a concise project-specific continuation prompt that directs official ChatGPT to read the private SheV-Memory checkpoint through its already-connected GitHub integration.
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

- Standalone Android module `:shevchat`, applicationId `com.shev.chat`, independent from the SheV Agent app.
- Russian-first Jetpack Compose companion UI with a persisted project selector for ФотоПорядок, SheV Agent and Моя память.
- Local Room database stores only notes manually entered by the user, isolated by project and bounded to the latest 100 entries per project. These notes are clearly labelled as local/manual; they are not presented as a live ChatGPT transcript.
- Project-aware handoff prompt includes up to 20 recent local notes (bounded to 12 KB), points to the private SheV-Memory ACTIVE-CHECKPOINT and tells ChatGPT to use its already-connected GitHub connector.
- Private SheV-Memory is NOT fetched from raw.githubusercontent.com. The app has no GitHub token, no OAuth flow, and no network permission. A previous prototype attempted unauthenticated raw access; it was removed because a private repository returns 404 and would create a false sync status.
- If ChatGPT's GitHub connector is unavailable, the prompt explicitly asks the assistant to request a user-provided export/attachment. It must not claim to have read memory.
- Launches the official `com.openai.chatgpt` Android app if installed. User pastes the copied prompt manually.
- Optional AccessibilityService prototype is scoped to the ChatGPT package and a user-queued New chat action; requests expire after 20 seconds. User must explicitly enable the service in Android Settings. This is not a guaranteed or device-verified capability.
- No API client, API credentials, API billing, backend server, WebView login, private ChatGPT session scraping, or transcript synchronization.
- CI workflow builds both debug APKs, checks SheV Agent versionCode against the latest release, verifies SheV Chat package identity/signature, uploads checksums/build-info and launches SheV Chat on Android 16 emulator.

## Remaining implementation and acceptance work

1. Run CI on the latest PR head; inspect both APKs, checksums and build-info.
2. Replace any remaining misleading "chat history" wording with "local notes" and ensure the screen remains usable on a small Android display.
3. Device-test official ChatGPT launch, clipboard handoff, Room persistence, project isolation and the optional AccessibilityService on the user's Android device. Accessibility stays optional and must not be enabled without consent.
4. If the user wants private memory visible inside the companion itself, first agree on an authenticated, least-privilege GitHub integration; do not add a token or unauthenticated raw fetch as a shortcut.

## Acceptance criteria

- SheV Chat launches official ChatGPT app when installed.
- Project selection changes the exact context copied to clipboard.
- The app never presents local draft bubbles as a real ChatGPT conversation.
- No API keys, API billing, backend server, or user developer setup required.
- Context can be prepared offline from manually saved local project history; the private GitHub checkpoint is read by ChatGPT only when its GitHub connector is connected, otherwise the user must attach/export it.
- Every sync has explicit success/failure status; no false "saved" claims.
- Accessibility is not mandatory for core functionality and is never enabled without user consent.
- All limitations around official chat control are stated clearly.
