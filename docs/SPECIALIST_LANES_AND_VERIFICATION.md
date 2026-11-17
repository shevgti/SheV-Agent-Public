# Specialist lanes and deterministic verification

This is the first Kotlin-native architecture slice inspired by the reviewed NOMAD and DeepSeek Harness designs. It is a policy foundation, not a claim that a multi-agent runtime is already wired into the Android app.

## Specialist lanes

- A lane is an allowlist and budget envelope for a specialist role.
- Each lane has exact native tool IDs, a maximum risk, a bounded TaskBudget, and explicit delegation/review policy.
- A specialist assignment cannot select a different lane, widen its budget, or delegate unless the lane permits it.
- The lane does not grant Android capabilities. Every invocation still passes AgentToolExecutor, CapabilityPolicy, scoped grants, and exact-action approval.
- External side effects are blocked when the lane requires an independent reviewer. Reviewer execution is a separate later orchestration stage, not an LLM self-approval.

## Verification evidence

- Model-authored statements are never proof of task completion.
- Read-only steps require a trusted local-state snapshot or test result.
- Reversible writes require a file hash, local-state snapshot, or test result.
- External side effects require a remote receipt.
- Critical actions require a signed receipt with SHA-256.
- Evidence must be attributed to an application-trusted verifier, reference a real plan step, and not come from the future.
- Trusted verifier IDs must come from application-owned configuration, never from model output or remote tool descriptions.

## Integration boundary

The new policy classes are pure Kotlin contracts with unit tests. They are not yet wired into the task inbox or completion transition. The current AgentPlanRunner continues to stop at VERIFYING; no task may be marked COMPLETED merely because an LLM says it succeeded. Next stages are Room persistence for verification evidence, a verifier registry backed by trusted native adapters, and a transaction that only permits VERIFYING to COMPLETED after policy acceptance.

## Source concepts

- NOMAD: specialist lanes, per-lane tool allowlists, budgets, and a separate reviewer.
- DeepSeek Harness: plugin capability seams, team/task-board concepts, and independent guard policies.
- SheV invariant: the Android Kotlin Agent Kernel remains the only authority for permissions, execution, persistence, and verification.

## Reversible native plugin lifecycle

- Added an in-process plugin registry for statically bundled Kotlin contributions.
- Activation requires an explicit user-approval signal, stable plugin ID/version, namespaced tool IDs, and an all-or-nothing registration pass.
- If any tool registration conflicts, already-added descriptors from that activation are rolled back.
- Deactivation removes every descriptor owned by the plugin, so the executor can no longer resolve those tools.
- This is deliberately not a dynamic script/plugin marketplace. No downloaded code, shell, reflection-based class loading, or permission grants are supported.
- Current limitation: active plugin state is process-local and there is no management UI or Room persistence yet. Those are required before user-facing plugin installation.
## Android Keystore credential storage

Added an AES-256-GCM secret store backed by Android Keystore:
- the encryption key is non-exportable and generated in Android Keystore;
- SharedPreferences stores only a versioned ciphertext envelope;
- each secret ID is authenticated as GCM additional data to prevent ciphertext swapping between server profiles;
- tampered, malformed or undecryptable values fail closed and return no credential;
- explicit removal is supported; no token values are logged or returned in diagnostics.

This is a storage primitive only. The MCP connection UI and credential lifecycle are not yet wired to it. OAuth refresh/expiry and backup/restore behavior still require explicit design. The Android instrumentation tests cover round-trip, ciphertext-at-rest, profile-binding and tamper rejection.
## Goal → milestone → task orchestration contract

Added a validated hierarchical goal graph:
- goal contains ordered milestones and task nodes;
- milestones may depend only on known milestones and must form an acyclic graph;
- tasks belong to exactly one known milestone and form their own acyclic dependency graph;
- cross-milestone task dependencies may point only to the same or an ancestor milestone, never a future/unrelated milestone;
- per-task and aggregate step/time/model-call budgets are bounded before scheduling.

This is a pure Kotlin validation contract with unit tests. Durable goal/task-board persistence, milestone state transitions, WorkManager scheduling and UI are still separate integration stages; no background work is launched by this model.
