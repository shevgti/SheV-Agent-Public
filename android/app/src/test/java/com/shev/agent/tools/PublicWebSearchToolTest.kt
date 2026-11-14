package com.shev.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicWebSearchToolTest {
    @Test
    fun rejectsTooShortQueryBeforeNetworkAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PublicWebSearchTool().search(" ") }
        }
    }

    @Test
    fun rejectsTooLongQueryBeforeNetworkAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PublicWebSearchTool().search("x".repeat(201)) }
        }
    }

    @Test
    fun rejectsUnboundedTimeoutConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            PublicWebSearchTool(connectTimeoutMillis = 60_000)
        }
    }
}
