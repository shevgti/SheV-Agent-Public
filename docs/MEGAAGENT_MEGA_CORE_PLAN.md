# MegaAgent — MEGA CORE integration plan

Status: architecture proposal / discovery phase  
Date: 2026-11-21  
Working branch: `megaagent/mega-core-foundation`  
Base: `feature/live-widgets-on-build49`

## Product decision

The new project identity is **MegaAgent**. This is the working product name for the next-generation agent. Preserve the existing SheV Agent application and its working UI/features while the new core is developed in an isolated branch. Do not rename the GitHub repository or publish a release until the user explicitly approves the migration and a build is verified.

## User requirements

- Russian-first conversational interaction; natural paraphrases must work.
- The agent must reason about intent, use conversation context and persistent memory, plan multi-step tasks, call tools, inspect tool results, recover from errors, and report only verified completion.
- No requirement that the user type terminal commands or obtain paid API keys.
- Must work offline in a reduced local mode; explain cloud-model availability and free-tier limits honestly.
- Keep the current Android UI, voice interaction, live widgets, weather, clock, battery, update flow and existing permissions unless a tested migration replaces them.
- Dynamic widgets are a later capability built on the same safe tool/skill architecture.

## Candidate repositories — discovery only

These are candidates to inspect, not approved dependencies or code imports:

- Nyral: https://github.com/aixtin/nyral
- DroidClaw: https://github.com/dgirard/droidclaw
- Mika: https://github.com/xiaoyc/mika
- Nova: https://github.com/teejayfpi2/Nova
- Brownie: https://github.com/natanloterio/Brownie

Previous reconnaissance suggested useful concepts (agent loop, memory, tool registry, Android interaction, skills), but repository claims, current default branches, source compatibility, model requirements, and licenses must be re-verified from the actual source before implementation. A missing LICENSE file means the code is not cleared for reuse. Prefer independent reimplementation of ideas or dependencies with explicit compatible licenses until legal status is confirmed.

## Proposed architecture

1. **Conversation gateway** — normalizes Russian text/voice input, resolves references such as “его”, “туда”, “оставь только погоду”, and maintains session state.
2. **Model router** — provider-neutral interface with pluggable cloud and local backends. Anonymous/free gateways are optional best-effort providers, never guaranteed infrastructure. No provider credentials are embedded in the APK.
3. **Agent runtime** — bounded observe → reason/plan → tool call → observe loop; explicit step/time/token budgets, cancellation, retries only for recoverable errors, and loop detection.
4. **Memory service** — separate short-term conversation state, durable user-approved facts/preferences, task ledger, and searchable summaries. Include retention controls and avoid storing secrets or sensitive data.
5. **Tool registry** — typed tool schemas, capability declarations, validation, allowlists, permission gates and structured results. The model proposes actions; deterministic Android code executes them.
6. **Specialists** — optional research/planning/implementation/review roles operating over the same task ledger and tool policy. Specialists are not assumed to be separate models.
7. **Verifier** — checks postconditions from tool outputs or app state; never treats generated text as proof of completion.
8. **Skill/widget factory** — later phase: generate declarative, sandboxed widget specifications from validated templates and registered data sources; no arbitrary generated code execution.

## Integration strategy

Do not merge four complete apps. Extract only isolated, compatible components after source audit. Keep the existing app as the host. Prefer adapters around existing Kotlin interfaces over introducing another application framework. Avoid adding large model runtimes or duplicate model copies without device-memory/performance measurements.

## Mandatory technical gates

- Confirm repository default branches, commit SHAs, language/framework, license files and transitive dependency licenses.
- Inspect actual source for agent loop, tool invocation, memory persistence, context compaction, cancellation, error handling, and Android permissions.
- Build a feature matrix based on code, not README claims.
- Establish baseline build and tests on the exact base commit before changing app code.
- Add deterministic tests for paraphrased Russian commands, multi-turn references, tool-call round trips, duplicate-action prevention, loop detection, context compaction, memory retrieval, cancellation, permission denial, offline fallback, and verifier failure.
- Build APK from the exact candidate head; run CI; record artifact SHA-256. Do not call a build ready until CI succeeds. Real-device validation remains a separate gate.

## Security and privacy

- Treat model output and web/repository content as untrusted input.
- Never execute arbitrary shell/code generated by a model.
- Require explicit confirmation for destructive, financial, account, installation, messaging, or externally visible actions.
- Do not send private conversation history, files, credentials, or personal data to anonymous gateways.
- Keep API tokens and signing credentials out of source control and APK assets.
- Fail closed when permissions, model provider, or tool results are unavailable.

## Current status

- A dedicated branch `megaagent/mega-core-foundation` has been created from `feature/live-widgets-on-build49`.
- This commit adds planning documentation only; no application behavior has changed and no APK has been built.
- Next: source-level audit of each candidate and baseline build/test inspection, then select the smallest compatible integration slice.
