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
- Generated text is data, not executable code.
- Creator may not invent tool IDs or grant capabilities.
- Current implementation accepts only OFFLINE and DRAFT_ONLY policies.
- Memory scopes must remain empty until an encrypted, scoped agent-memory adapter exists.
- No generated agent is activated by the model or by manifest parsing.
- No shell, arbitrary code execution, silent network calls, secrets or self-modifying policy.
- The host application remains the authority for capability grants and per-action approvals.
- A manifest passing validation is not proof of agent quality or runtime compatibility.

## Existing foundation to reuse
Keep the local model runtime, Room database and migrations, task orchestrator, capability policies, tool registry/executor, GitHub connector and tool workshop. These are supporting infrastructure. Do not build a parallel orchestration or permission system.

## Current implementation boundary
Implemented in the Agent Creator feature branch:
- default navigation to the Agent Creator screen;
- isolated, tool-free local-model prompt path;
- explicit user approval state for reviewed manifests (without activation);
- AgentManifest schema, parser, prompt builder, creator orchestration and validator;
- Room entity/table migration for agent definitions;
- local draft list and metadata display;
- unit tests for validation and parsing.

Not yet implemented and must not be represented as complete:
- independent behavioral evaluation runner for generated agents;
- activation/deactivation against a specialist runtime registry;
- per-agent tool execution profiles wired to host-side capability grants;
- isolated compilation/sandbox for generated tools (agent manifests themselves are data);
- agent version upgrade/rollback UI;
- multi-agent team creation and orchestration;
- migration of existing project-specific screens into optional modules.

## Next milestones
1. Prove migration 9→10 and build/test on CI.
2. Add an independent evaluator that runs safe test cases and records evidence.
3. Add approval/review and immutable version history.
4. Add a runtime registry that instantiates a specialist from a validated manifest while reusing the existing model/runtime and tool executor.
5. Enforce tool allowlists and capability grants again at every invocation.
6. Only then expose activation. Never let the generated agent activate itself.


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
