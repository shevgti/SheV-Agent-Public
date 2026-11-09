# SheV Agent Bridge Protocol v1

Date: 2026-11-09
Status: **Draft contract; Stage 0 parser/validator implemented, Android import/export UI and executor wiring pending.**

## Purpose

Define a small, versioned, transport-independent envelope so ChatGPT can prepare a task and the Android app can safely validate and execute it locally. This protocol intentionally supports proposals, not remote authority.

## Task proposal

```json
{
  "protocol": "shev-agent.task",
  "version": 1,
  "request_id": "uuid-generated-by-supervisor",
  "created_at": "2026-11-09T20:00:00Z",
  "expires_at": "2026-11-09T20:10:00Z",
  "intent": "Проверить состояние проекта FotoPoryadok",
  "tool": {
    "id": "project.health.read",
    "schema_version": 1,
    "arguments": {
      "project_id": "fotoporyadok"
    }
  },
  "requested_capabilities": ["READ_PROJECT_METADATA"],
  "risk": "READ_ONLY",
  "expected_result": "Состояние проекта, последние проверки и обнаруженные ошибки"
}
```

The example is illustrative. The tool ID and capability must exist in the local registry; a proposal cannot create a tool or permission.

## Execution result

```json
{
  "protocol": "shev-agent.result",
  "version": 1,
  "request_id": "same-request-id",
  "status": "COMPLETED",
  "tool_id": "project.health.read",
  "started_at": "2026-11-09T20:00:02Z",
  "finished_at": "2026-11-09T20:00:03Z",
  "approval": "NOT_REQUIRED",
  "result": {
    "summary": "Проверка завершена",
    "evidence": []
  },
  "audit_event_id": "local-event-id"
}
```

## Validation rules

- Maximum imported JSON: 32 KiB in Stage 0.
- Maximum result JSON: 64 KiB; truncate large outputs and return an explicit truncation marker.
- Require exact protocol name and supported integer version.
- Reject expired proposals, timestamps too far in the future, duplicate request IDs and malformed UUIDs.
- Maintain a local replay cache; imported request IDs are single-use, including failed attempts.
- Reject unknown top-level fields and unknown tool IDs. Tool schemas are local source of truth.
- Validate every argument against the local tool schema; never trust a schema supplied by ChatGPT.
- Ignore any requested grant duration, approval flag, system prompt, shell command, callback URL or network destination in an envelope. These fields are not part of v1.
- Derive capability and risk from the local tool descriptor, not from the incoming `requested_capabilities` or `risk` labels. Those labels are explanatory only and must match the local descriptor or the request is rejected.
- User approval is performed in the Android UI and is not encoded as an assistant-supplied boolean.
- Do not include access tokens, passwords, pairing secrets, private keys, raw personal files or full conversation history.

## Initial tool allow-list

Start with read-only tools only:
- `agent.health.read` — app/runtime readiness and version; no personal content.
- `projects.list` — list explicitly registered workspaces, without reading their contents.
- `project.metadata.read` — metadata for one selected project.
- `memory.search` — search only the local SheV-Memory index, returning bounded snippets and source references.

Later, add draft-only tools:
- `project.patch.propose` — create a reviewable patch; never apply automatically.
- `memory.note.propose` — prepare a proposed memory entry; user reviews before save.

Not in v1:
- arbitrary shell or terminal commands;
- unrestricted filesystem access;
- deleting files or data;
- GitHub merge, release, publish, secret or permission changes;
- sending messages, payments or purchases;
- changing Android settings or granting OS permissions;
- arbitrary URL fetches or network destinations.

## Approval model

- Read-only operations: may run after import if the local tool is explicitly enabled and its scope is valid.
- Draft operations: show diff/content and require the user to choose Save/Apply.
- Any write, external network action or action outside the selected workspace: require a fresh, per-invocation confirmation.
- Critical/destructive operations are excluded from v1 entirely.

## Error statuses

`INVALID_ENVELOPE`, `UNSUPPORTED_VERSION`, `EXPIRED`, `REPLAYED`, `UNKNOWN_TOOL`, `SCHEMA_MISMATCH`, `SCOPE_DENIED`, `APPROVAL_REQUIRED`, `CANCELLED`, `TIMEOUT`, `EXECUTION_FAILED`, `OUTPUT_TRUNCATED`.

## Transport evolution

Stage 0 uses Android Sharesheet/clipboard/QR and has no network listener. A later MCP adapter may map MCP `tools/list` and `tools/call` to this same local dispatcher. The dispatcher, schema validation, capability policy, approval and audit must remain identical regardless of transport. Never implement a second authorization path for MCP.
