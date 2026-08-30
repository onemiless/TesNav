package com.garan.tesnav.export

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import kotlin.math.min

/** Authenticated, single-round result. Discovery never guesses or chooses the first responder. */
sealed interface NavAssistV2DiscoveryResult {
    data class Found(val sourceHost: String) : NavAssistV2DiscoveryResult
    data object NotFound : NavAssistV2DiscoveryResult
    data object MultipleAuthenticatedHosts : NavAssistV2DiscoveryResult
    data class Failed(val reason: String) : NavAssistV2DiscoveryResult
}

fun interface NavAssistV2EndpointDiscovery {
    fun discover(token: String): NavAssistV2DiscoveryResult
}

internal data class NavAssistV2Datagram(
    val sourceAddress: InetAddress,
    val payload: ByteArray,
)

internal fun interface NavAssistV2DiscoveryTransport {
    fun exchange(request: ByteArray): List<NavAssistV2Datagram>
}

/**
 * UDP discovery for one C3XL on the phone's directly connected private network.
 * Both probe and offer are authenticated; an offer-supplied host is never trusted.
 */
class UdpNavAssistV2EndpointDiscovery internal constructor(
    private val transport: NavAssistV2DiscoveryTransport = JvmUdpNavAssistV2DiscoveryTransport(),
    private val nonceFactory: () -> String = ::secureDiscoveryNonce,
) : NavAssistV2EndpointDiscovery {
    override fun discover(token: String): NavAssistV2DiscoveryResult {
        if (token.toByteArray(StandardCharsets.UTF_8).size < NavAssistV2Protocol.MIN_TOKEN_UTF8_BYTES) {
            return NavAssistV2DiscoveryResult.Failed("NavAssist token 未配置")
        }
        val nonce = nonceFactory()
        if (!NONCE_REGEX.matches(nonce)) {
            return NavAssistV2DiscoveryResult.Failed("发现 nonce 生成失败")
        }

        return runCatching {
            val request = NavAssistV2DiscoveryWire.request(nonce, token)
            val hosts = transport.exchange(request).mapNotNull { datagram ->
                NavAssistV2DiscoveryWire.authenticatedOfferHost(datagram, nonce, token)
            }.toSet()
            when (hosts.size) {
                0 -> NavAssistV2DiscoveryResult.NotFound
                1 -> NavAssistV2DiscoveryResult.Found(hosts.single())
                else -> NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts
            }
        }.getOrElse {
            NavAssistV2DiscoveryResult.Failed("C3XL 局域网发现失败")
        }
    }

    internal companion object {
        val NONCE_REGEX = Regex("^[0-9a-f]{32}$")
    }
}

internal object NavAssistV2DiscoveryWire {
    private const val REQUEST_TYPE = "navassist_discovery_request"
    private const val OFFER_TYPE = "navassist_discovery_offer"
    private val PROOF_REGEX = Regex("^[0-9a-f]{64}$")
    private val REQUEST_KEYS = setOf("messageType", "schemaVersion", "nonce", "proof")
    private val OFFER_KEYS = setOf("messageType", "schemaVersion", "nonce", "port", "path", "proof")

    fun request(nonce: String, token: String): ByteArray {
        require(UdpNavAssistV2EndpointDiscovery.NONCE_REGEX.matches(nonce)) { "invalid discovery nonce" }
        val proof = HmacSha256.signLowerHex(requestProofMaterial(nonce), token)
        return buildString {
            append("{\"messageType\":\"")
            append(REQUEST_TYPE)
            append("\",\"schemaVersion\":")
            append(NavAssistV2Protocol.SCHEMA_VERSION)
            append(",\"nonce\":\"")
            append(nonce)
            append("\",\"proof\":\"")
            append(proof)
            append("\"}")
        }.toByteArray(StandardCharsets.UTF_8)
    }

    fun authenticatedOfferHost(
        datagram: NavAssistV2Datagram,
        expectedNonce: String,
        token: String,
    ): String? {
        if (datagram.payload.isEmpty() || datagram.payload.size > NavAssistV2Discovery.MAX_DATAGRAM_BYTES) return null
        val source = datagram.sourceAddress as? Inet4Address ?: return null
        if (!source.isSiteLocalAddress || source.isAnyLocalAddress || source.isLoopbackAddress || source.isMulticastAddress) return null
        val fields = StrictFlatJson.parse(datagram.payload) ?: return null
        if (fields.keys != OFFER_KEYS) return null
        if (fields.string("messageType") != OFFER_TYPE) return null
        if (fields.number("schemaVersion") != NavAssistV2Protocol.SCHEMA_VERSION.toLong()) return null
        if (fields.string("nonce") != expectedNonce || !UdpNavAssistV2EndpointDiscovery.NONCE_REGEX.matches(expectedNonce)) return null
        if (fields.number("port") != NavAssistV2Discovery.SNAPSHOT_PORT.toLong()) return null
        if (fields.string("path") != NavAssistV2Protocol.ENDPOINT_PATH) return null
        val proof = fields.string("proof") ?: return null
        if (!PROOF_REGEX.matches(proof)) return null
        val expected = HmacSha256.signLowerHex(offerProofMaterial(expectedNonce), token)
        if (!MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), proof.toByteArray(StandardCharsets.US_ASCII))) {
            return null
        }
        return source.hostAddress
    }

    internal fun requestProofMaterial(nonce: String): String =
        "$REQUEST_TYPE\n${NavAssistV2Protocol.SCHEMA_VERSION}\n$nonce"

    internal fun offerProofMaterial(nonce: String): String =
        "$OFFER_TYPE\n${NavAssistV2Protocol.SCHEMA_VERSION}\n$nonce\n" +
            "${NavAssistV2Discovery.SNAPSHOT_PORT}\n${NavAssistV2Protocol.ENDPOINT_PATH}"
}

internal object NavAssistV2Discovery {
    const val UDP_PORT = 7765
    const val SNAPSHOT_PORT = 7766
    const val MAX_DATAGRAM_BYTES = 512
    const val RECEIVE_WINDOW_MS = 750
    const val MAX_BROADCAST_TARGETS = 8
    const val MAX_RESPONSES = 64
}

internal class JvmUdpNavAssistV2DiscoveryTransport : NavAssistV2DiscoveryTransport {
    override fun exchange(request: ByteArray): List<NavAssistV2Datagram> {
        require(request.size <= NavAssistV2Discovery.MAX_DATAGRAM_BYTES) { "discovery request too large" }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val sent = broadcastTargets().count { target ->
                runCatching {
                    socket.send(DatagramPacket(request, request.size, target, NavAssistV2Discovery.UDP_PORT))
                }.isSuccess
            }
            if (sent == 0) error("no usable broadcast interface")

            val responses = mutableListOf<NavAssistV2Datagram>()
            val deadlineNs = System.nanoTime() + NavAssistV2Discovery.RECEIVE_WINDOW_MS * NANOS_PER_MILLISECOND
            while (responses.size < NavAssistV2Discovery.MAX_RESPONSES) {
                val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MILLISECOND
                if (remainingMs <= 0L) break
                socket.soTimeout = min(remainingMs, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                val buffer = ByteArray(NavAssistV2Discovery.MAX_DATAGRAM_BYTES + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (packet.length <= NavAssistV2Discovery.MAX_DATAGRAM_BYTES) {
                    responses += NavAssistV2Datagram(
                        sourceAddress = packet.address,
                        payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                    )
                }
            }
            return responses
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        interfaces.asSequence()
            .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
            .flatMap { network -> runCatching { network.interfaceAddresses }.getOrDefault(emptyList()).asSequence() }
            .filter { binding -> (binding.address as? Inet4Address)?.isSiteLocalAddress == true }
            .mapNotNull { it.broadcast as? Inet4Address }
            .take(NavAssistV2Discovery.MAX_BROADCAST_TARGETS - targets.size)
            .forEach(targets::add)
        return targets.take(NavAssistV2Discovery.MAX_BROADCAST_TARGETS)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun secureDiscoveryNonce(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Minimal strict parser for this flat discovery object; rejects duplicate keys and all composite JSON values. */
private object StrictFlatJson {
    sealed interface Value {
        data class StringValue(val value: String) : Value
        data class NumberValue(val value: Long) : Value
    }

    fun parse(payload: ByteArray): Map<String, Value>? {
        val text = decodeUtf8(payload) ?: return null
        if (text.any { it.code !in 0x20..0x7e }) return null
        return Parser(text).parse()
    }

    private fun decodeUtf8(payload: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    }.getOrNull()

    private class Parser(private val input: String) {
        private var position = 0

        fun parse(): Map<String, Value>? {
            skipWhitespace()
            if (!take('{')) return null
            val fields = linkedMapOf<String, Value>()
            skipWhitespace()
            if (take('}')) return fields.takeIf { atEndAfterWhitespace() }
            while (true) {
                skipWhitespace()
                val key = parseString() ?: return null
                if (fields.containsKey(key)) return null
                skipWhitespace()
                if (!take(':')) return null
                skipWhitespace()
                val value = if (peek() == '"') {
                    parseString()?.let(Value::StringValue)
                } else {
                    parseNumber()?.let(Value::NumberValue)
                } ?: return null
                fields[key] = value
                skipWhitespace()
                if (take('}')) return fields.takeIf { atEndAfterWhitespace() }
                if (!take(',')) return null
            }
        }

        private fun parseString(): String? {
            if (!take('"')) return null
            val start = position
            while (position < input.length && input[position] != '"') {
                val character = input[position]
                if (character == '\\' || character.code !in 0x20..0x7e) return null
                position++
            }
            if (position >= input.length) return null
            val value = input.substring(start, position)
            position++
            return value
        }

        private fun parseNumber(): Long? {
            val start = position
            if (peek() == '-') position++
            val digitStart = position
            while (peek()?.isDigit() == true) position++
            if (position == digitStart) return null
            val raw = input.substring(start, position)
            if (raw == "-0" || raw.startsWith("0") && raw.length > 1 || raw.startsWith("-0") && raw.length > 2) return null
            return raw.toLongOrNull()
        }

        private fun atEndAfterWhitespace(): Boolean {
            skipWhitespace()
            return position == input.length
        }

        private fun skipWhitespace() {
            while (peek() in setOf(' ', '\t', '\r', '\n')) position++
        }

        private fun take(expected: Char): Boolean {
            if (peek() != expected) return false
            position++
            return true
        }

        private fun peek(): Char? = input.getOrNull(position)
    }
}

private fun Map<String, StrictFlatJson.Value>.string(key: String): String? =
    (get(key) as? StrictFlatJson.Value.StringValue)?.value

private fun Map<String, StrictFlatJson.Value>.number(key: String): Long? =
    (get(key) as? StrictFlatJson.Value.NumberValue)?.value
