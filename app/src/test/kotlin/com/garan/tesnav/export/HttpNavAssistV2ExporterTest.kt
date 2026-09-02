package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpNavAssistV2ExporterTest {
    private val identity = AndroidKeystoreNavAssistIdentity.generatedForTest()
    private val deviceId = "d".repeat(32)

    @Test
    fun `runtime UDP mode broadcasts canonical snapshots without discovery or credentials`() {
        val sent = CountDownLatch(1)
        val discoveryCalls = AtomicInteger()
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(baseUrl = ""),
            stateProvider = { NavigationState() },
            identity = identity,
            endpointDiscovery = NavAssistV2EndpointDiscovery {
                discoveryCalls.incrementAndGet()
                NavAssistV2DiscoveryResult.NotFound
            },
            pinnedDeviceProvider = { null },
            httpClient = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) =
                    error("UDP mode must not POST")
                override fun close() = Unit
            },
            useUnauthenticatedUdp = true,
            udpClient = NavAssistV3UdpClient { body, sessionId, sequence ->
                assertTrue(body.decodeToString().contains("\"sessionId\":\"$sessionId\""))
                assertTrue(sequence > 0)
                sent.countDown()
                "192.168.102.187"
            },
        )

        try {
            exporter.start()
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals("udp://192.168.102.187:4213", exporter.resolvedEndpoint.value)
            assertEquals(0, discoveryCalls.get())
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `discovered endpoint becomes online only after a successful POST`() {
        val postEntered = CountDownLatch(1)
        val allowPost = CountDownLatch(1)
        val client = object : NavAssistV2HttpClient {
            override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                assertEquals(identity.keyId, appKeyId)
                postEntered.countDown()
                assertTrue(allowPost.await(2, TimeUnit.SECONDS))
            }

            override fun close() = Unit
        }
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId) },
            client = client,
        )

        try {
            exporter.start()
            assertTrue(postEntered.await(2, TimeUnit.SECONDS))
            assertEquals(NavAssistV2ConnectionStatus.DISCOVERED, exporter.status.value)
            assertEquals(ExportConnectionState.STARTING, exporter.connectionState.value)
            allowPost.countDown()
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals(ExportConnectionState.CONNECTED, exporter.connectionState.value)
        } finally {
            allowPost.countDown()
            exporter.stop()
        }
    }

    @Test
    fun `one HTTP failure retries the authenticated endpoint before rediscovery`() {
        val discoveries = AtomicInteger()
        val posts = AtomicInteger()
        val online = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                discoveries.incrementAndGet()
                NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    if (posts.incrementAndGet() == 1) error("first POST fails")
                    online.countDown()
                }

                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(online.await(3, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertEquals(1, discoveries.get())
            assertTrue(posts.get() >= 2)
            assertEquals("http://192.168.53.232:7766/v3/snapshot", exporter.resolvedEndpoint.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `repeated HTTP failures rediscover after a network change`() {
        val discoveries = AtomicInteger()
        val posts = AtomicInteger()
        val online = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                val suffix = if (discoveries.incrementAndGet() == 1) 232 else 233
                NavAssistV2DiscoveryResult.Found("192.168.53.$suffix", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    posts.incrementAndGet()
                    if (endpoint.host.endsWith(".232")) error("old network")
                    online.countDown()
                }

                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(online.await(3, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.ONLINE }
            assertTrue(discoveries.get() >= 2)
            assertTrue(posts.get() >= 3)
            assertEquals("http://192.168.53.233:7766/v3/snapshot", exporter.resolvedEndpoint.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `navigation start can force rediscovery of a previously healthy endpoint`() {
        val discoveries = AtomicInteger()
        val posts = AtomicInteger()
        val firstOnline = CountDownLatch(1)
        val secondOnline = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                val count = discoveries.incrementAndGet()
                NavAssistV2DiscoveryResult.Found("192.168.53.${231 + count}", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    when (posts.incrementAndGet()) {
                        1 -> firstOnline.countDown()
                        2 -> secondOnline.countDown()
                    }
                }

                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(firstOnline.await(2, TimeUnit.SECONDS))
            exporter.requestRediscovery()
            assertTrue(secondOnline.await(2, TimeUnit.SECONDS))
            await { discoveries.get() >= 2 }
            assertEquals("http://192.168.53.233:7766/v3/snapshot", exporter.resolvedEndpoint.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `multiple authenticated devices never select an endpoint`() {
        val discoveryCalled = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                discoveryCalled.countDown()
                NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) = error("must not POST")
                override fun close() = Unit
            },
        )

        try {
            exporter.start()
            assertTrue(discoveryCalled.await(2, TimeUnit.SECONDS))
            await { exporter.status.value == NavAssistV2ConnectionStatus.MULTIPLE_DEVICES }
            assertEquals(null, exporter.resolvedEndpoint.value)
            assertEquals(ExportConnectionState.ERROR, exporter.connectionState.value)
        } finally {
            exporter.stop()
        }
    }

    @Test
    fun `stopping during discovery prevents a stale pairing tail POST`() {
        val discoveryEntered = CountDownLatch(1)
        val releaseDiscovery = CountDownLatch(1)
        val postCalled = CountDownLatch(1)
        val exporter = exporter(
            discovery = NavAssistV2EndpointDiscovery {
                discoveryEntered.countDown()
                assertTrue(releaseDiscovery.await(2, TimeUnit.SECONDS))
                NavAssistV2DiscoveryResult.Found("192.168.53.232", deviceId)
            },
            client = object : NavAssistV2HttpClient {
                override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
                    postCalled.countDown()
                }

                override fun close() = Unit
            },
        )

        exporter.start()
        assertTrue(discoveryEntered.await(2, TimeUnit.SECONDS))
        exporter.stop()
        releaseDiscovery.countDown()

        assertTrue("stopped exporter must not POST", !postCalled.await(500, TimeUnit.MILLISECONDS))
    }

    private fun exporter(
        discovery: NavAssistV2EndpointDiscovery,
        client: NavAssistV2HttpClient,
    ) = HttpNavAssistV2Exporter(
        config = NavAssistV2ExportConfig(baseUrl = ""),
        stateProvider = { NavigationState() },
        identity = identity,
        endpointDiscovery = discovery,
        pinnedDeviceProvider = { null },
        httpClient = client,
        discoveryRetryMs = 1L,
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met")
            Thread.yield()
        }
    }
}
