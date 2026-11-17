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
