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
