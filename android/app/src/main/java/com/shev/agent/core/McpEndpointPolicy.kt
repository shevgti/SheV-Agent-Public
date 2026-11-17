package com.shev.agent.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Blocks remote catalog entries that resolve to loopback, private, link-local,
 * documentation, multicast or otherwise non-global destinations. User-hosted
 * profiles are an explicit opt-in for private-network endpoints.
 *
 * This is a preflight guard, not DNS pinning: the HTTP engine may resolve the
 * hostname again. Redirects are disabled in RemoteMcpClient to prevent a server
 * from redirecting an approved public endpoint to a private address.
 */
object McpEndpointPolicy {
    suspend fun validate(profile: McpServerProfile) = withContext(Dispatchers.IO) {
        if (profile.trust == McpServerTrust.USER_HOSTED) return@withContext
        val host = URI(profile.endpoint).host
            ?: throw IllegalArgumentException("MCP endpoint has no host")
        val addresses = InetAddress.getAllByName(host)
        validateResolvedAddresses(profile, addresses.toList())
    }

    internal fun validateResolvedAddresses(
        profile: McpServerProfile,
        addresses: List<InetAddress>
    ) {
        require(addresses.isNotEmpty()) { "MCP endpoint did not resolve to an address" }
        if (profile.trust == McpServerTrust.USER_HOSTED) return
        require(addresses.all(::isGloballyRoutable)) {
            "Official and community MCP endpoints must resolve only to public IP addresses"
        }
    }

    private fun isGloballyRoutable(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        val bytes = address.address
        if (address is Inet4Address) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false // Shared address space (RFC 6598).
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && (b == 0 || b == 168) -> false
                a == 198 && (b in 18..19 || b == 51 && c == 100) -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }

        // IPv4-mapped IPv6 must be checked using the embedded IPv4 address.
        if (bytes.size == 16 &&
            bytes.take(10).all { it.toInt() == 0 } &&
            bytes[10].toInt() == -1 &&
            bytes[11].toInt() == -1
        ) {
            return isGloballyRoutable(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }

        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if (first == 0xff || (first and 0xfe) == 0xfc) return false // Multicast / ULA.
            // Only global-unicast 2000::/3 is admitted; exclude documentation 2001:db8::/32.
            if ((first and 0xe0) != 0x20) return false
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            val fourth = bytes[3].toInt() and 0xff
            if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return false
            return true
        }
        return false
    }
}
