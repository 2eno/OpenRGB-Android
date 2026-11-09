package io.github.openrgbremote.sdk.client

import io.github.openrgbremote.sdk.testing.FakeOpenRgbServer
import io.github.openrgbremote.sdk.testing.GarbageServer
import io.github.openrgbremote.sdk.testing.closedPort
import io.github.openrgbremote.sdk.protocol.DeviceCodec
import io.github.openrgbremote.sdk.protocol.LittleEndianReader
import io.github.openrgbremote.sdk.protocol.PacketId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedFrameStreamerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        scope.cancel()
        closeables.forEach { runCatching { it.close() } }
    }

    private val config = OpenRgbClientConfig(heartbeatIntervalMillis = 0)

    @Test
    fun `slow server receives fewer frames but always the latest one`() = runBlocking {
        withTimeout(20_000) {
            // Each UpdateLEDs takes the server 30 ms, frames are produced every millisecond.
            val server = FakeOpenRgbServer(6, ackDelayMillis = 30).also { closeables += it }
            val client = OpenRgbClient("127.0.0.1", server.port, config).also { closeables += it }
            client.connect()
            val streamer = LedFrameStreamer(client, scope, maxInFlight = 1, ackTimeoutMillis = 1_000)

            repeat(200) { frame ->
                streamer.submit(100, IntArray(8) { frame })
                delay(1)
            }
            val last = IntArray(8) { 199 }
            var received: IntArray? = null
            for (attempt in 0 until 200) {
                received = server.packets(PacketId.RGBCONTROLLER_UPDATELEDS).lastOrNull()
                    ?.let { DeviceCodec.decodeColors(LittleEndianReader(it.payload, 4)).toIntArray() }
                if (received.contentEquals(last)) break
                delay(25)
            }
            assertTrue(received.contentEquals(last), "latest frame must arrive")
            delay(100) // let the sender update its counters after the final write

            val stats = streamer.stats.value
            assertEquals(200, stats.submitted)
            assertTrue(stats.sent < 100, "conflation should skip most frames, sent=${stats.sent}")
            assertTrue(stats.dropped > 100, "dropped=${stats.dropped}")
            assertEquals(stats.sent.toInt(), server.packets(PacketId.RGBCONTROLLER_UPDATELEDS).size)
            streamer.stop()
        }
    }

    @Test
    fun `streams to several devices independently`() = runBlocking {
        withTimeout(20_000) {
            val server = FakeOpenRgbServer(4).also { closeables += it }
            val client = OpenRgbClient("127.0.0.1", server.port, config.copy(protocolVersionTimeoutMillis = 300)).also { closeables += it }
            client.connect()
            val streamer = LedFrameStreamer(client, scope)
            streamer.submit(0, IntArray(8) { 0x0000FF })
            streamer.submit(1, IntArray(4) { 0x00FF00 })
            for (attempt in 0 until 200) {
                if (server.packets(PacketId.RGBCONTROLLER_UPDATELEDS).size == 2) break
                delay(25)
            }
            assertEquals(setOf(0, 1), server.packets(PacketId.RGBCONTROLLER_UPDATELEDS).map { it.deviceId }.toSet())
            streamer.stop()
        }
    }
}
