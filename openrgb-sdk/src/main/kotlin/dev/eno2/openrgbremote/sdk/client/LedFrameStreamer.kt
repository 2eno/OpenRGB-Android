package dev.eno2.openrgbremote.sdk.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Counters of a [LedFrameStreamer], useful for an FPS/latency overlay. */
public data class StreamStats(
    val submitted: Long = 0,
    val sent: Long = 0,
    /** Frames replaced by a newer one before they could be sent (conflation). */
    val dropped: Long = 0,
    /** Times the ACK window did not open in time (protocol 6+). */
    val ackTimeouts: Long = 0,
    val errors: Long = 0,
)

/**
 * Streams LED frames to devices with backpressure.
 *
 * - **Conflation:** each device has a single-slot mailbox. [submit] never blocks; a frame that was
 *   not yet sent is replaced by the newer one. A slow server therefore receives fewer frames instead
 *   of an ever-growing backlog, which keeps the latency bounded.
 * - **ACK window (protocol 6+):** before sending, the worker waits until fewer than [maxInFlight]
 *   UpdateLEDs packets are unacknowledged.
 * - **Protocol < 6:** no ACKs exist; the blocking socket write is the only feedback. The TCP send
 *   buffer can hide some latency, which is a documented limitation.
 */
public class LedFrameStreamer(
    private val client: OpenRgbClient,
    private val scope: CoroutineScope,
    private val maxInFlight: Int = 2,
    private val ackTimeoutMillis: Long = 500,
) {
    private class Lane(val mailbox: Channel<IntArray>, val job: Job)

    private val lanes = ConcurrentHashMap<Int, Lane>()
    private val _stats = MutableStateFlow(StreamStats())
    public val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    /** Queues [colors] (`0xRRGGBB` per LED) for [deviceId], replacing a pending older frame. */
    public fun submit(deviceId: Int, colors: IntArray) {
        val lane = lanes.computeIfAbsent(deviceId) { startLane(it) }
        _stats.update { it.copy(submitted = it.submitted + 1) }
        lane.mailbox.trySend(colors)
    }

    /** Stops all lanes; pending frames are discarded. */
    public fun stop() {
        lanes.values.forEach {
            it.mailbox.close()
            it.job.cancel()
        }
        lanes.clear()
    }

    private fun startLane(deviceId: Int): Lane {
        val mailbox = Channel<IntArray>(
            capacity = Channel.CONFLATED,
            onUndeliveredElement = { _stats.update { s -> s.copy(dropped = s.dropped + 1) } },
        )
        val job = scope.launch {
            for (frame in mailbox) {
                if (!client.awaitLedWindow(deviceId, maxInFlight, ackTimeoutMillis)) {
                    _stats.update { it.copy(ackTimeouts = it.ackTimeouts + 1) }
                }
                try {
                    if (client.updateLeds(deviceId, frame)) {
                        _stats.update { it.copy(sent = it.sent + 1) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _stats.update { it.copy(errors = it.errors + 1) }
                    if (!client.isConnected) break
                }
            }
        }
        return Lane(mailbox, job)
    }
}
