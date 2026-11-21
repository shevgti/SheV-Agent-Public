# MegaAgent source audit — Brownie and Mika

Date: 2026-11-21
Target branch: `megaagent/mega-core-foundation`
Audit type: read-only source inspection; no third-party source copied.

## Executive decision

Do **not** replace the existing SheV Agent runtime with Brownie or Mika wholesale. Keep SheV Agent as host and preserve its existing Google ADK/LiteRT-LM runtime, tool registry, capability checks, task repository, encrypted conversation storage, widgets, voice and update flow. Use Brownie as a reference for modular agent/memory/tool design and Mika as a reference for Android UI automation/accessibility, after further security and compatibility review.

## Brownie — verified source findings

Repository: https://github.com/natanloterio/Brownie
Audited branch: `master`
Audited tree SHA: `00400841fa58d6c4900df1132f6fd003fd69b196`
License: MIT (LICENSE file present).

The repository is a native Kotlin/Jetpack Compose multi-module Android project:
- `:core:agent`: `AgentRunner`, `ConversationManager`, `AgentConfig`; includes an explicit model/tool loop and configurable max iterations (default 10).
- `:core:inference`: `InferenceEngine` abstraction and LiteRT-LM local engine.
- `:core:memory`: conversation history, session search index, user profile, skill store and simple Markdown memory store.
- `:core:tools`: typed tool interface/registry, web search, browser, files, memory, scheduler, MCP, Android integration.
- Unit tests cover agent loop, tool calls, iteration cap, context trimming, memory and tool registry.
- README specifies on-device Gemma 4 E2B via LiteRT-LM; minimum device tier 6 GB RAM; model download on first launch; no model API key for public model downloads. This does not provide a powerful cloud model, and local reasoning quality/speed is constrained by device hardware/model size.

Important implementation caveats found:
1. `AgentRunner` has a bounded loop and permission pause, but current loop has no explicit repeated-identical-tool-call detector or independent postcondition verifier.
2. `ConversationManager` compresses old messages using a heuristic role-based summary (first 100 chars of user messages / last 150 chars of assistant messages); it is not semantic retrieval by itself.
3. `MemoryStore` is a small key/value Markdown file implementation, not a robust encrypted or structured long-term memory system.
4. `CodeExecutorTool` executes shell scripts and blocks a short list of dangerous string patterns. This is not a sufficient security boundary for model-generated code; do not port or expose it in MegaAgent.
5. `ToolRegistry` converts thrown exceptions into error results, a useful pattern, but tools still need host-side authorization and input validation.
6. Brownie app Gradle configuration includes an optional Hugging Face token property for gated models; public model setup says no token is needed. No token should be embedded in the APK.

Useful references:
- `core/agent/.../AgentRunner.kt`
- `core/agent/.../ConversationManager.kt`
- `core/inference/.../InferenceEngine.kt`
- `core/inference/.../LiteRtLmEngine.kt`
- `core/memory/.../MemoryStore.kt`
- `core/tools/.../ToolRegistry.kt`
- `core/tools/.../CodeExecutorTool.kt`

## Mika — verified source findings

Repository: https://github.com/xiaoyc/mika
Audited branch: `main`
Audited tree SHA: `4ce3ca67e8cf8af8323a4d74505f51fd94178137`
License: Apache-2.0 (LICENSE and NOTICE files present).

Mika is a much larger Android automation product, not a small reusable library. The inspected source tree includes:
- Accessibility service and screen reading/OCR.
- Separate phone capability layers, screen/display control and app switching.
- Browser automation, web actions, CDP and tab management.
- Sandbox and workspace controls.
- Task service, cancellation/recovery tests, parallel task tests and live-run tests.
- Many Android instrumentation/device tests (the tree contains a broad test suite).

README says Mika can operate apps and websites through background screen automation, and that users must supply a model API key or sign in with a ChatGPT, xAI or OpenRouter account. Therefore Mika is not a solution to the user's no-login/no-key model requirement by itself. Its screen automation is a future optional subsystem and would require careful permission UX and device testing.

Useful reference:
- `app/src/main/java/com/wangzan101/mika/a11y/MikaAccessibilityService.kt`
- `app/src/main/java/com/wangzan101/mika/cap/phone/a11y/*`
- `app/src/main/java/com/wangzan101/mika/cap/web/*`
- device tests under `app/src/androidTest`.

## Existing MegaAgent host — verified source findings

Repository: https://github.com/shevgti/SheV-Agent-Public
Base branch: `feature/live-widgets-on-build49`
Inspected file: `android/app/src/main/java/com/shev/agent/model/SheVAgentRuntime.kt`.

The existing app already uses Google ADK Kotlin with LiteRT-LM, an in-memory ADK session service, registered host tools, scoped specialist manifests and capability restrictions. `runSpecialist` checks approved status, network policy and draft-only side-effect policy, and only exposes selected registered tools. This is valuable infrastructure and a strong reason not to replace the runtime wholesale.

The existing code also contains a persistent Room task repository, encrypted conversation storage and a live-widget repository in `LocalModelViewModel.kt`. The next audit should trace how ADK sessions are restored/compacted and how the selected local model/provider is configured.

## Recommended implementation sequence

1. Preserve the existing ADK/LiteRT runtime and current feature set.
2. Add a provider-neutral `ModelBackend` adapter seam only if source audit confirms the current routing cannot support a second backend cleanly. No anonymous gateway should receive private conversation history.
3. Improve conversation continuity through durable task state + semantic/relevant memory retrieval, rather than simply raising character limits.
4. Add deterministic agent-loop protections: iteration/time/token budgets, repeated-call detection, cancellation, structured tool error feedback, and a verifier that checks actual tool postconditions.
5. Add tests before wiring new behavior: paraphrased Russian commands, multi-turn references, failed tool retry, repeated tool loop, permission denial, cancellation, context restoration, and false completion prevention.
6. Consider Mika-inspired accessibility automation only as a separate opt-in module, with explicit Android permission screens and safe action confirmation.
7. Do not port Brownie's shell execution tool. Keep all execution through typed, allowlisted host tools.

## Current status

This document is an audit/design commit only. No app source code was changed, no APK was built, and no third-party code was copied. The next implementation slice should be selected after inspecting the existing ADK runtime/session persistence and CI baseline.

## Critical host-context finding — existing ADK session may duplicate prior turns

Inspected:
- `SheVAgentRuntime.run(text)` always invokes the same fixed `SheVAgentSession.SESSION_ID` (`main-session`) in the same `InMemorySessionService`.
- `LocalModelViewModel.send()` rebuilds a prompt containing the rolling summary plus up to 12 previous completed messages / 4,000 history characters, then passes that *entire reconstructed context* as the new user message to `active.run(promptWithSkills)` on every turn.
- The in-memory ADK session is not reset between these calls in the inspected `run()` path.

This creates a likely context duplication defect: each new turn appends another user message containing prior turns that the same persistent ADK session already retains. Over several turns, the model may receive repeated copies of old dialogue, wasting context and making references/intent less reliable. This is a stronger and more actionable hypothesis than simply increasing the history limits.

Recommended fix direction (not yet implemented):
- Keep the encrypted Room transcript and rolling summary as the durable source of truth.
- Give each top-level user turn a fresh ADK session ID, injecting the bounded reconstructed context exactly once for that turn. Keep all tool calls and specialist delegation within that per-turn session.
- Alternatively, retain one ADK session but send only the new user message after the first turn; however this requires careful recovery when the native model/session cache is reset and when rolling summaries are updated. Prefer fresh per-turn sessions for deterministic context reconstruction unless ADK LiteRT cache costs make this impractical.
- Add regression tests that run at least 5 turns and assert each prior transcript entry is present exactly once in the model input, the current turn is present once, and tool-call round trips remain in the same turn session.
- Measure latency and memory before adopting the per-turn session approach. The loaded model must remain shared; do not reload model weights per turn.

## Corrections to earlier rough diagnosis

The current host does have a rolling summary repository and a tested context builder. The builder explicitly limits history to 12 messages / 4,000 characters and summary to 2,000 characters, and tests cover checkpoint exclusion, bounds, failed/pending rows and current-message preservation. Therefore do not describe the app as having no memory or no summarization. The problem to investigate is how this reconstructed context is combined with the persistent in-memory ADK session.


## First code change — per-turn ADK session isolation

Implemented on `megaagent/mega-core-foundation`:
- Commit: `44b1ad9d928cc7f23c5d32e98d1be85c15140998`
- `SheVAgentRuntime.run(text)` now returns a Flow that creates a unique ADK session for each top-level user turn.
- The reconstructed Room transcript/summary is passed once as that session's user message.
- All model/tool round-trips for that turn remain within the same session.
- The session is deleted in a `NonCancellable` cleanup block after collection, preventing accumulation of per-turn event history in the in-memory session service.
- The LiteRT model and InMemoryRunner remain alive; no model weights are reloaded per turn.
- Removed initialization of the old fixed `main-session`.

Validation status:
- Draft PR #68 opened against `feature/live-widgets-on-build49` (not main) to keep the review diff isolated.
- PR currently contains 4 commits / 3 changed files relative to that base.
- GitHub Actions had not yet reported a run at the time of this update. The patch is not yet verified by CI or a device test. Do not merge or distribute an APK until tests/build pass.
- Review exact ADK Kotlin 1.5.0 API compatibility and ensure cancellation cleanup works under the Android coroutine runtime.
