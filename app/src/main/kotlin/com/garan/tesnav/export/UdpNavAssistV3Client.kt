package com.garan.tesnav.export

import com.google.gson.JsonParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.math.min

internal fun interface NavAssistV3UdpClient {
    /** Broadcast one canonical v3 snapshot and return the acknowledging C3XL address. */
    fun send(body: ByteArray, sessionId: String, sequence: Long): String?
}

internal class JvmUdpNavAssistV3Client : NavAssistV3UdpClient {
    override fun send(body: ByteArray, sessionId: String, sequence: Long): String? {
        require(body.isNotEmpty() && body.size <= MAX_SNAPSHOT_BYTES) { "UDP snapshot is too large" }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val sent = broadcastTargets().count { target ->
                runCatching { socket.send(DatagramPacket(body, body.size, target, UDP_PORT)) }.isSuccess
            }
            if (sent == 0) error("no usable broadcast interface")

            val deadlineNs = System.nanoTime() + RECEIVE_WINDOW_MS * NANOS_PER_MILLISECOND
            while (true) {
                val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MILLISECOND
                if (remainingMs <= 0L) return null
                socket.soTimeout = min(remainingMs, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                val buffer = ByteArray(MAX_ACK_BYTES + 1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    return null
                }
                if (packet.length > MAX_ACK_BYTES) continue
                val source = packet.address as? Inet4Address ?: continue
                if (source.isAnyLocalAddress || source.isLoopbackAddress || source.isMulticastAddress) continue
                val ack = runCatching {
                    JsonParser.parseString(packet.data.decodeToString(packet.offset, packet.offset + packet.length)).asJsonObject
                }.getOrNull() ?: continue
                if (ack.keySet() != ACK_KEYS || ack["messageType"]?.asString != ACK_TYPE ||
                    ack["schemaVersion"]?.asInt != NavAssistV2Protocol.SCHEMA_VERSION ||
                    ack["sessionId"]?.asString != sessionId || ack["sequence"]?.asLong != sequence) continue
                return source.hostAddress
            }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        interfaces.asSequence()
            .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
            .flatMap { network -> runCatching { network.interfaceAddresses }.getOrDefault(emptyList()).asSequence() }
            .mapNotNull { it.broadcast as? Inet4Address }
            .take(MAX_BROADCAST_TARGETS)
            .forEach(targets::add)
        if (targets.isEmpty()) targets += InetAddress.getByName("255.255.255.255")
        return targets.take(MAX_BROADCAST_TARGETS)
    }

    private companion object {
        const val UDP_PORT = 4213
        const val MAX_SNAPSHOT_BYTES = 8 * 1024
        const val MAX_ACK_BYTES = 512
        const val RECEIVE_WINDOW_MS = 350L
        const val MAX_BROADCAST_TARGETS = 8
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val ACK_TYPE = "navassist_udp_ack"
        val ACK_KEYS = setOf("messageType", "schemaVersion", "sessionId", "sequence")
    }
}
