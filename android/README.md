# SheV Agent — Android application

Kotlin + Jetpack Compose Android application. This is the first integrated installable app milestone: Russian-first dashboard, local model panel, persistent Room workspace, task lifecycle and guarded tool modules. It is not yet a fully autonomous production assistant.

## Build and unit tests
Requires JDK 21 and Android SDK 37:
```bash
gradle testDebugUnitTest
gradle assembleDebug
```

## Emulator verification
The required CI smoke-test target is Android 16 / API 36:
```bash
gradle connectedDebugAndroidTest
```

## Current foundations
- Russian-first dashboard with Home, Projects, Tools and Memory tabs.
- Room-backed tasks, events, grants, improvements and workspace layout.
- GitHub Device Flow integration and encrypted token storage foundation.
- Capability policy, validated tool input schemas and guarded tool executor.
- Improvement Lab with diff review and explicit approval workflow.
- Google ADK Kotlin + LiteRT-LM local runtime integration.
- Pinned Gemma 4 E2B IT model artifact specification and resumable SHA-256-verified downloader/import flow.

## Important limitations
- Gemma model file is about 2.58 GB and is not included in this repository. It must be downloaded or imported by the user in the app.
- The integrated source still requires fresh CI and real-device validation. Do not infer that model inference works on the phone from source presence or APK assembly alone.
- Voice identity, speech recognition/synthesis and several real-world tool adapters remain incomplete.


## Task submission behavior
- User-authored tasks and explicit model-requested task drafts are inserted with a conflict-safe Room insert, moved to `WAITING_APPROVAL`, and recorded in the append-only event log within one database transaction.
- The local ADK bridge now exposes a narrow `create_task_draft` suspend tool. It is schema-validated and passes through `AgentToolExecutor` with the low-risk `CREATE_DRAFT` capability. It cannot approve or execute a task.
- Task creation is idempotent. A repeated matching request is reported as a replay; conflicting task IDs or mismatched request data are rejected.
- Saving a task does not start tools or execute actions. One-shot approval evidence storage and the explicit approval dialog are implemented on the current feature branch; approval still does not dispatch tools. An execution worker is not implemented.


## Task cancellation
- User can cancel a task from its card after an explicit confirmation. The lifecycle moves to terminal `CANCELLED`; cancelled tasks cannot be resumed, and completed tasks cannot be cancelled.
- This currently records the cancellation state. Cooperative interruption of a running external tool must be connected with the future execution worker.


## Continuous conversation progress
- Room schema v9 now includes encrypted rolling-summary checkpoint storage (`conversation_state`), with migration 8→9 and a migration-preservation test.
- `ConversationStateRepository` encrypts summary checkpoints using the existing Android Keystore AES-GCM cipher, rejects stale versions, and distinguishes missing from unreadable state.
- DAO supports stable offset-based transcript pages and total count. The existing chat UI/runtime still needs integration; automatic summarization and historical retrieval are not yet implemented.
- Tests have been authored but not executed. Do not treat the schema or repository as end-to-end continuous conversation.


## Chat-first shell update — 2026-11-07
- Primary navigation destination is now labelled “Чат”.
- Local model panel has a clearer first-run setup state: model size, device diagnostics, explicit download/import, progress/cancel and separate runtime launch.
- Chat state uses message bubbles, Russian-first quick prompts, a multiline composer and a voice-input control.
- SpeechRecognizer is configured for ru-RU and requests offline recognition preference; actual offline support depends on the speech provider installed on the phone.
- This is a source update only. The new Compose screen has not yet been compiled, tested on an emulator or installed on a physical phone.
- UI reference notes: AnythingLLM Mobile, PocketPal AI and Open WebUI are recorded in docs/UX-SHELL-2026-11-07.md.


## Local-only primary AI mode — 2026-11-10
- The primary chat is locked to on-device Gemma 4 E2B IT. Existing OmniRoute, OpenAI, Gemini and OpenRouter primary-provider flags are cleared at startup and are not used by the send path.
- The settings screen no longer asks the user to configure cloud AI keys. It explains that the primary chat is local-only.
- If a verified Gemma model is already installed, the app attempts to initialize the local runtime automatically on launch.
- After a model download or verified import, the app attempts to initialize the runtime automatically.
- This does not remove the first-run model requirement: Gemma weights are approximately 2.58 GB and must be downloaded or imported. Internet is needed for the initial download; inference is on-device afterward.
- CI and real-device inference must pass before this is called a working release. Local model performance depends on available RAM, thermal state and device backend.
