package io.github.openrgbremote.sdk.discovery

import io.github.openrgbremote.sdk.protocol.ProtocolConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Progress and results of a [SubnetScanner] run. */
public sealed interface ScanEvent {
    public data class Progress(val scanned: Int, val total: Int) : ScanEvent
    public data class Found(val result: ProbeResult) : ScanEvent
}

/**
 * Probes many hosts in parallel for an OpenRGB SDK server.
 *
 * With 64 parallel probes and a 400 ms connect timeout a /24 takes roughly 2 s on Wi-Fi:
 * unused addresses time out, while existing PCs without OpenRGB reject immediately.
 */
public class SubnetScanner(
    private val probe: OpenRgbProbe = OpenRgbProbe(),
    private val parallelism: Int = 64,
) {
    public fun scan(
        hosts: List<String>,
        port: Int = ProtocolConstants.DEFAULT_PORT,
        connectTimeoutMillis: Int = 400,
    ): Flow<ScanEvent> = channelFlow {
        val semaphore = Semaphore(parallelism)
        val done = AtomicInteger(0)
        send(ScanEvent.Progress(0, hosts.size))
        for (host in hosts) {
            launch {
                val result = semaphore.withPermit { probe.probe(host, port, connectTimeoutMillis) }
                if (result != null) send(ScanEvent.Found(result))
                send(ScanEvent.Progress(done.incrementAndGet(), hosts.size))
            }
        }
    }
}
