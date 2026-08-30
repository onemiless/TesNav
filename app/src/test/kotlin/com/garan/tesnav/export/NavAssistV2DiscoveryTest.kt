package com.garan.tesnav.export

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavAssistV2DiscoveryTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val nonce = "00112233445566778899aabbccddeeff"

    @Test
    fun `request and offer proof vectors match C3 implementation`() {
        assertEquals(
            "9d578b071534a597bb803bfe9372204164351983f241847dac1e5953d1255712",
            HmacSha256.signLowerHex(NavAssistV2DiscoveryWire.requestProofMaterial(nonce), token),
        )
        assertEquals(
            "d507c868871964322a3660c828cb6e55918525e7e21c1bbaa00d751bfbae2cf9",
            HmacSha256.signLowerHex(NavAssistV2DiscoveryWire.offerProofMaterial(nonce), token),
        )
        assertArrayEquals(
            ("{\"messageType\":\"navassist_discovery_request\",\"schemaVersion\":2," +
                "\"nonce\":\"$nonce\",\"proof\":\"9d578b071534a597bb803bfe9372204164351983f241847dac1e5953d1255712\"}")
                .toByteArray(StandardCharsets.UTF_8),
            NavAssistV2DiscoveryWire.request(nonce, token),
        )
    }

    @Test
    fun `accepts one authenticated private source and ignores unauthenticated packets`() {
        val discovery = discoveryWith(
            datagram("192.168.53.232", offer()),
            datagram("192.168.53.100", offer(proof = "0".repeat(64))),
            datagram("8.8.8.8", offer()),
        )

        assertEquals(NavAssistV2DiscoveryResult.Found("192.168.53.232"), discovery.discover(token))
    }

    @Test
    fun `two distinct authenticated hosts fail closed after the whole round`() {
        val discovery = discoveryWith(
            datagram("192.168.53.232", offer()),
            datagram("192.168.53.233", offer()),
            datagram("192.168.53.232", offer()),
        )

        assertEquals(NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts, discovery.discover(token))
    }

    @Test
    fun `duplicate authenticated offers from one host are one candidate`() {
        val discovery = discoveryWith(
            datagram("10.0.0.2", offer()),
            datagram("10.0.0.2", offer()),
        )

        assertEquals(NavAssistV2DiscoveryResult.Found("10.0.0.2"), discovery.discover(token))
    }

    @Test
    fun `strict offer parser rejects contract deviations`() {
        val invalid = listOf(
            offer(nonce = "ffeeddccbbaa99887766554433221100"),
            offer(schemaVersion = 1),
            offer(port = 7767),
            offer(path = "/other"),
            offer(proof = "A".repeat(64)),
            offer(extra = ",\"host\":\"192.168.53.232\""),
            offer().replaceFirst("\"proof\":", "\"proof\":\"duplicate\",\"proof\":"),
            offer().replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""),
            offer().replace("\"port\":7766", "\"port\":\"7766\""),
            offer().replace("{", "{\"nested\":{},"),
        )

        invalid.forEach { payload ->
            val discovery = discoveryWith(datagram("192.168.53.232", payload))
            assertEquals("payload=$payload", NavAssistV2DiscoveryResult.NotFound, discovery.discover(token))
        }
    }

    @Test
    fun `oversize datagrams invalid UTF8 and non IPv4 sources are ignored`() {
        val discovery = discoveryWith(
            NavAssistV2Datagram(InetAddress.getByName("192.168.53.232"), ByteArray(513) { 'a'.code.toByte() }),
            NavAssistV2Datagram(InetAddress.getByName("192.168.53.232"), byteArrayOf(0xc3.toByte(), 0x28)),
            datagram("::1", offer()),
        )

        assertEquals(NavAssistV2DiscoveryResult.NotFound, discovery.discover(token))
    }

    @Test
    fun `each discovery call creates a new request nonce`() {
        val nonces = ArrayDeque(
            listOf(
                "00112233445566778899aabbccddeeff",
                "ffeeddccbbaa99887766554433221100",
            ),
        )
        val requests = mutableListOf<String>()
        val discovery = UdpNavAssistV2EndpointDiscovery(
            transport = NavAssistV2DiscoveryTransport { request ->
                requests += request.toString(StandardCharsets.UTF_8)
                emptyList()
            },
            nonceFactory = { nonces.removeFirst() },
        )

        discovery.discover(token)
        discovery.discover(token)

        assertEquals(2, requests.distinct().size)
        assertTrue(requests[0].contains("00112233445566778899aabbccddeeff"))
        assertTrue(requests[1].contains("ffeeddccbbaa99887766554433221100"))
    }

    private fun discoveryWith(vararg datagrams: NavAssistV2Datagram) = UdpNavAssistV2EndpointDiscovery(
        transport = NavAssistV2DiscoveryTransport { datagrams.toList() },
        nonceFactory = { nonce },
    )

    private fun datagram(host: String, payload: String) = NavAssistV2Datagram(
        sourceAddress = InetAddress.getByName(host),
        payload = payload.toByteArray(StandardCharsets.UTF_8),
    )

    private fun offer(
        nonce: String = this.nonce,
        schemaVersion: Int = 2,
        port: Int = 7766,
        path: String = "/v2/snapshot",
        proof: String = HmacSha256.signLowerHex(NavAssistV2DiscoveryWire.offerProofMaterial(this.nonce), token),
        extra: String = "",
    ): String = "{\"messageType\":\"navassist_discovery_offer\",\"schemaVersion\":$schemaVersion," +
        "\"nonce\":\"$nonce\",\"port\":$port,\"path\":\"$path\",\"proof\":\"$proof\"$extra}"
}
