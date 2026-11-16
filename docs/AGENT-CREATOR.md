# SheV-Agent Creator — product and implementation contract

## Mission
The primary purpose of SheV-Agent is to create, validate, version and eventually run specialized AI agents. The default app screen is the Agent Creator. The user describes a job in Russian; the local model proposes one narrowly scoped specialist.

## Creation pipeline
1. Accept a bounded natural-language task description.
2. Discover the current tool allowlist and already registered agent IDs.
3. Build a strict creator prompt. User text is JSON-encoded as untrusted data.
4. Ask the on-device model for one JSON AgentManifest.
5. Parse and validate the manifest: unique kebab-case ID, role, purpose, 2–4 triggers, JSON input/output schemas, known tool IDs only, bounded steps/time/model calls, verification strategy and at least two evaluation cases.
6. Force status to DRAFT regardless of model output.
7. Persist the manifest locally in Room and show it for review.

## Manifest contract
A manifest contains:
- identity: id, name, version;
- purpose, expert role and full operating instructions;
- trigger examples;
- input and output schemas;
- allowed tool IDs;
- memory read/write scopes;
- maximum steps, timeout and model-call budget;
- network and side-effect policy;
- verification strategy, evaluation cases and limitations;
- lifecycle status: DRAFT, APPROVED, ACTIVE or DISABLED.

## Security invariants
- Generated text and manifests are data, not executable code.
- The creator itself uses an isolated tool-free generation path; it cannot call host tools, approve a manifest or activate a specialist.
- The creator may select only IDs from the live host-tool catalog. The user must review and explicitly approve the saved manifest.
- Supported manifest policy is `OFFLINE` or `READ_ONLY` networking with `DRAFT_ONLY` side effects.
- Memory read/write scopes must remain empty until a dedicated encrypted, scoped memory adapter exists.
- After approval, the specialist runtime exposes only registered tools selected in that manifest through a per-agent scoped allowlist. Every call is checked again by the host executor and `CapabilityPolicy`.
- Specialists cannot use HIGH/CRITICAL capability tools. A specialist with `OFFLINE` policy cannot use remote-data tools; remote reads require `READ_ONLY`.
- No arbitrary filesystem access, shell, arbitrary code execution, secrets, silent publishing, financial actions or self-modifying policy are granted.
- The task inbox is a separate workflow: it executes only the exact persisted plan approved by the user, records each step, and requires user review of saved outputs before completion.
- A manifest passing validation is not proof of agent quality or runtime compatibility.

## Existing foundation to reuse
Keep the local model runtime, Room database and migrations, task orchestrator, capability policies, tool registry/executor, GitHub connector and tool workshop. These are supporting infrastructure. Do not build a parallel orchestration or permission system.

## Current implementation boundary
Implemented in the current release line:
- Russian-first Agent Creator with isolated manifest generation through the local model or connected ChatGPT/Codex.
- Live catalog of tools that have registered schemas and real host handlers.
- Manifest validation, bounded step/time/model-call budgets, known-tool validation and draft-only creation.
- Explicit user approval of a saved manifest; the model cannot approve or activate itself.
- Manual specialist execution through the loaded local model in an isolated ADK session, with only the manifest-selected tools, immutable per-agent allowlist, capability/risk checks, and bounded execution.
- Specialist network access is limited by the manifest policy; selected remote-data tools require `READ_ONLY`.
- Persistent task inbox with immutable approved plans, durable per-step execution records, pause/resume after interruption, and a `VERIFYING` state. A task becomes complete only after the user reviews the saved outputs and confirms.
- Registered built-in host tools currently include current time, Open-Meteo weather, public web search, tool-catalog inspection, and local draft helpers for tasks/skills.
- Tool Workshop can prepare source proposals and display a diff; it does not write generated code into the project or execute it.

Not yet implemented and must not be represented as complete:
- persistent autonomous background execution or an always-on ACTIVE/PAUSED specialist registry;
- independent behavioral evaluation runner that tests generated specialists and stores evidence;
- specialist run history/audit UI, durable cancellation and restart recovery for individual specialist sessions;
- immutable specialist version history with upgrade/rollback UI;
- multi-agent team creation, delegation and swarm orchestration;
- encrypted, scoped per-agent memory;
- a sandboxed tool-development pipeline that compiles, installs and safely activates newly generated tools;
- broad phone-control or arbitrary file-system tools. Specialists can use only the real host tools currently exposed by the app.

## Next milestones
1. Fix and verify the live tool-catalog handoff in the Agent Creator UI so approved specialists with valid selected tools are not incorrectly rejected.
2. Add an independent evaluator and persist evidence before exposing specialist activation.
3. Add a durable specialist registry, run history, cancellation and restart recovery.
4. Add encrypted, scoped memory only after access isolation and migration tests.
5. Add multi-agent team orchestration only after single-specialist execution and verification are reliable.
6. Keep all writes, publishing, deletion, security changes and financial actions behind explicit per-action approval.

## Anthropic-compatible Markdown output

The design source is the official Apache-2.0 Anthropic repository:
- `plugins/plugin-dev/agents/agent-creator.md`
- `plugins/plugin-dev/skills/agent-development/SKILL.md`
- `plugins/plugin-dev/skills/agent-development/references/agent-creation-system-prompt.md`
- `plugins/plugin-dev/skills/agent-development/scripts/validate-agent.sh`

SheV-Agent adapts the workflow to Android/Kotlin and the local model. The generated portable artifact is a Markdown subagent file with YAML frontmatter:
`name`, `description` (including 2–4 worked `<example>` blocks), `model`, and `color`, followed by the system prompt and its `When to invoke`, responsibilities, quality, output and edge-case sections.

The app previews the generated Markdown and exports it through Android's document picker to a location selected by the user. It does not pretend that an app-private file is already installed into a Claude Code project. The user can select a project folder or share the exported file manually. The optional Claude Code tools field is restricted to the explicit Read/Write allowlist and is omitted when neither tool is needed.

### Clarification stage

If the local model reports missing critical requirements, the app displays up to three focused questions and appends an answer area to the request. No agent record is saved until a complete manifest passes validation.

### Tool namespaces are separate

Claude Code frontmatter `tools` and SheV-Agent host tool IDs are different namespaces. The exporter intentionally omits the Claude Code `tools` field rather than copying SheV-Agent IDs into it. Native host tool IDs remain in the private manifest and are checked against the host allowlist. A Markdown file never grants permissions.

### Validation and lifecycle

The Android validator enforces Anthropic-compatible name, model, color, description, and worked-example requirements in addition to the native manifest checks. The Markdown file is stored beside the native manifest in Room (schema migration 10→11) and can be exported explicitly. Draft approval remains separate from activation. No generated agent is executable until the independent evaluator, runtime registry and per-invocation host capability checks are implemented and reviewed.

### Attribution

The upstream methodology and file conventions are adapted from Anthropic's `claude-plugins-official/plugins/plugin-dev` toolkit, licensed under Apache License 2.0. SheV-Agent's Kotlin/Android implementation is an independent adaptation, not a bundled Claude Code runtime.


## Kilo anonymous free-model backend

The Creator and manually invoked specialists can optionally use the Kilo AI Gateway through Google's Android-compatible ADK OpenAI Chat Completions integration.

- The app fetches the public model catalogue without credentials.
- Only models whose live prompt and completion prices are both exactly zero and whose catalogue advertises `tools` support are offered.
- The selected model is revalidated against the live catalogue immediately before each generation/run. If it disappears or becomes paid, the request stops; there is no paid-model fallback.
- No Kilo API key, account login, payment method or credit balance is used. The official anonymous free tier is rate-limited by IP (currently about 200 requests/hour).
- Kilo is a model provider, not a replacement orchestration framework. ADK continues to own sessions, specialist execution and tool invocation.
- Kilo use is disabled until the user explicitly opts in during the current ViewModel lifetime. The UI warns that prompts may be processed by upstream providers and must not contain personal data, secrets or private code.
- Remote Kilo specialist runs require the manifest's `READ_ONLY` network policy. `OFFLINE` specialists must use the local model.
- The main chat provider is not switched to Kilo automatically. Local LiteRT-LM and the existing Codex route remain separate choices.

This backend is optional and is not evidence of a live inference round-trip until a real, user-approved test request succeeds. Automated unit tests validate catalogue filtering only.
