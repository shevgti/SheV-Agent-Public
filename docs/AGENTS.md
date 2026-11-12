# SheV Agent — coding-agent instructions

## Mission
Build SheV-Agent primarily as an Android-first AI-agent factory. The default workflow is Agent Creator: design, validate, version and eventually activate specialized agents from natural-language tasks. Keep existing chat, project, memory, GitHub and tool systems only where they support this mission; do not expand unrelated features ahead of Agent Creator. Work in coherent batches; inspect the whole feature path before changing code. Prefer finishing vertical slices over scattering partial changes.

## Repository and branch safety
- Android application code lives in `android/**`; project documentation lives in `docs/**`; CI lives in `.github/workflows/**`.
- Never push to or modify `main`. Use a dedicated feature branch and open a draft pull request against `main`.
- Do not merge pull requests, publish releases, upload APKs as public releases, or change repository secrets/permissions without explicit user approval.
- Preserve unrelated user changes. Inspect git status and diffs before committing.
- Never expose, print, commit, or copy API keys, tokens, signing material, or personal data.

## Required workflow for every task
1. Read this file, the project README, architecture/roadmap, relevant source and tests.
2. State a short implementation plan, then implement the complete feature including UI, persistence, error handling, tests and documentation where appropriate.
3. Run formatting/static checks available in the repo, then:
   - `gradle testDebugUnitTest`
   - `gradle assembleDebug`
   - `gradle connectedDebugAndroidTest` when Android SDK/emulator is available.
4. The required emulator target is Android 16 / API 36 only.
5. Inspect the final diff, report exact tests run and their outcomes, and leave a draft PR for review. Never claim a test passed unless the command output proves it.

## Product and safety requirements
- Agent Creator is the default app screen. Generated manifests are inert drafts until a separately reviewed runtime activation path exists.
- The model cannot invent tools, grant capabilities, activate agents, or change network/side-effect policy.
- Reuse the existing orchestrator, tool registry/executor, Room database and capability policy; do not create parallel frameworks.
- Keep existing user data and migrations. Do not delete legacy code or data merely to simplify navigation.

- Android only. Russian-first interface and voice interactions.
- The agent may plan and execute benign tasks within granted scopes. Dangerous, destructive, financial, external communication, permission, installation, deletion, and security-sensitive actions require explicit confirmation unless the user has granted a clearly scoped persistent authorization.
- A persistent grant must be capability- and scope-specific, visible, revocable, auditable, and expire when configured. Never interpret a vague grant as blanket permission.
- Voice authorization must use a clear spoken confirmation flow and must not rely on voice recognition alone as proof of identity for high-risk actions.
- Keep a permanent, visible emergency stop and permission-management entry. The agent cannot hide, delete, or weaken them.
- Never generate or execute arbitrary code as a UI customization shortcut. UI changes must use validated component schemas, preview/rollback and policy enforcement.
- Keep secrets out of source control; use environment secrets only when needed and document variable names without values.

## Collaboration
- Work independently through multiple related tasks in one batch.
- Avoid frequent progress messages; deliver concise milestone reports.
- If a blocker needs user input, ask one precise question and continue with independent safe work.
