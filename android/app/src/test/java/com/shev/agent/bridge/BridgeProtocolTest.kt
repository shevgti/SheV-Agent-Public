package com.shev.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class BridgeProtocolTest {
    private val requestId = "123e4567-e89b-12d3-a456-426614174000"

    private fun taskJson(
        tool: String = "agent.health.read",
        created: String = "2026-11-09T20:00:00Z",
        expires: String = "2026-11-09T20:10:00Z"
    ) = """
        {
          "protocol": "shev-agent.task",
          "version": 1,
          "request_id": "$requestId",
          "created_at": "$created",
          "expires_at": "$expires",
          "intent": "Проверить состояние приложения",
          "tool": {"id": "$tool", "schema_version": 1, "arguments": {}},
          "requested_capabilities": ["READ_APP_STATUS"],
          "risk": "READ_ONLY",
          "expected_result": "Состояние приложения"
        }
    """.trimIndent()

    private val now = java.time.Instant.parse("2026-11-09T20:05:00Z").toEpochMilli()

    @Test fun acceptsAllowlistedReadOnlyTask() {
        val result = BridgeProtocol.parseAndValidate(taskJson(), nowEpochMs = now)
        assertEquals(BridgeTaskStatus.ACCEPTED, result.status)
        assertNotNull(result.task)
    }

    @Test fun rejectsUnsupportedVersion() {
        val result = BridgeProtocol.parseAndValidate(taskJson().replace("\"version\": 1", "\"version\": 99"), nowEpochMs = now)
        assertEquals(BridgeTaskStatus.UNSUPPORTED_VERSION, result.status)
    }

    @Test fun expiredTaskIsRejected() {
        val result = BridgeProtocol.parseAndValidate(taskJson(expires = "2026-11-09T20:04:00Z"), nowEpochMs = now)
        assertEquals(BridgeTaskStatus.EXPIRED, result.status)
    }

    @Test fun unknownToolIsRejected() {
        val result = BridgeProtocol.parseAndValidate(taskJson(tool = "shell.exec"), nowEpochMs = now)
        assertEquals(BridgeTaskStatus.INVALID, result.status)
    }

    @Test fun draftToolRequiresApproval() {
        val result = BridgeProtocol.parseAndValidate(taskJson(tool = "project.patch.propose"), nowEpochMs = now)
        assertEquals(BridgeTaskStatus.APPROVAL_REQUIRED, result.status)
    }

    @Test fun duplicateRequestIsRejected() {
        val result = BridgeProtocol.parseAndValidate(taskJson(), nowEpochMs = now, alreadySeenRequestId = { it == requestId })
        assertEquals(BridgeTaskStatus.DUPLICATE, result.status)
    }

    @Test fun malformedJsonIsRejected() {
        val result = BridgeProtocol.parseAndValidate("{not-json", nowEpochMs = now)
        assertEquals(BridgeTaskStatus.INVALID, result.status)
        assertNull(result.task)
    }
}
