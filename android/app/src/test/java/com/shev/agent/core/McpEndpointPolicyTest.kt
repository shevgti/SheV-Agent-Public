package com.shev.agent.core

import java.net.InetAddress
import org.junit.Assert.assertThrows
import org.junit.Test

class McpEndpointPolicyTest {
    private fun profile(trust: McpServerTrust) = McpServerProfile(
        id = "test-server",
        displayName = "Test server",
        endpoint = "https://example.org/mcp",
        authentication = McpAuthenticationKind.NONE,
        trust = trust,
        enabled = true
    )

    @Test
    fun officialProfileRejectsPrivateAndLoopbackAddresses() {
        val official = profile(McpServerTrust.OFFICIAL)
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validateResolvedAddresses(
                official,
                listOf(InetAddress.getByName("192.168.1.10"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validateResolvedAddresses(
                official,
                listOf(InetAddress.getByName("127.0.0.1"))
            )
        }
    }

    @Test
    fun officialProfileRejectsDocumentationAndUniqueLocalIpv6Ranges() {
        val official = profile(McpServerTrust.OFFICIAL)
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validateResolvedAddresses(
                official,
                listOf(InetAddress.getByName("203.0.113.10"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validateResolvedAddresses(
                official,
                listOf(InetAddress.getByName("fc00::1"))
            )
        }
    }

    @Test
    fun officialProfileAcceptsPublicAddresses() {
        McpEndpointPolicy.validateResolvedAddresses(
            profile(McpServerTrust.OFFICIAL),
            listOf(InetAddress.getByName("8.8.8.8"))
        )
    }

    @Test
    fun privateNetworkIsAllowedOnlyForExplicitUserHostedProfile() {
        McpEndpointPolicy.validateResolvedAddresses(
            profile(McpServerTrust.USER_HOSTED),
            listOf(InetAddress.getByName("192.168.1.10"))
        )
    }

    @Test
    fun emptyDnsResultIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validateResolvedAddresses(
                profile(McpServerTrust.COMMUNITY),
                emptyList()
            )
        }
    }
}
