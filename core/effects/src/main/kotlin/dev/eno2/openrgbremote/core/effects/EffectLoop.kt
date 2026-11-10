package dev.eno2.openrgbremote.core.effects

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** What the loop renders: the effect and its parameters can change while it runs. */
data class EffectSpec(val effect: EffectId, val params: EffectParams)

/** A device to render for. [key] identifies it to the output function. */
class RenderTarget<K>(val key: K, val layout: LedLayout)

data class LoopStats(
    val targetFps: Int = 0,
    /** Frames actually produced during the last second. */
    val fps: Int = 0,
    /** Average time to render and hand over one frame for all targets, in milliseconds. */
    val frameMillis: Float = 0f,
    val targets: Int = 0,
)

/**
 * Frame clock of the effect engine.
 *
 * Each tick renders every target and passes the frame to `output`, which must not block for
 * long: the app hands frames to a conflating per-device streamer, so a slow server receives
 * fewer frames rather than a growing backlog (that is the backpressure). If rendering itself
 * falls behind (e.g. the phone is busy), missed ticks are skipped instead of replayed in a burst.
 */
class EffectLoop<K>(private val nanoTime: () -> Long = System::nanoTime) {

    private val _stats = MutableStateFlow(LoopStats())
    val stats: StateFlow<LoopStats> = _stats.asStateFlow()

    suspend fun run(
        spec: StateFlow<EffectSpec>,
        targets: StateFlow<List<RenderTarget<K>>>,
        fps: StateFlow<Int>,
        audio: StateFlow<AudioLevels>,
        output: suspend (K, IntArray) -> Unit,
    ) {
        var effectTime = 0.0
        var last = nanoTime()
        var nextFrame = last
        var windowStart = last
        var framesInWindow = 0
        var busyNanos = 0L
        val buffers = HashMap<K, IntArray>()

        while (coroutineContext.isActive) {
            val targetFps = fps.value.coerceIn(MIN_FPS, MAX_FPS)
            val frameNanos = 1_000_000_000L / targetFps
            // Show the target right away; the measured rate follows after the first second.
            if (_stats.value.targetFps != targetFps || _stats.value.targets != targets.value.size) {
                _stats.value = _stats.value.copy(targetFps = targetFps, targets = targets.value.size)
            }
            val now = nanoTime()
            val current = spec.value
            val speed = current.params.speed.coerceIn(EffectParams.MIN_SPEED, EffectParams.MAX_SPEED)
            effectTime += (now - last) / 1e9 * speed
            last = now

            val list = targets.value
            for (target in list) {
                val out = buffers[target.key]?.takeIf { it.size == target.layout.size } ?: IntArray(target.layout.size)
                EffectRenderer.render(current.effect, effectTime, target.layout, current.params, audio.value, out)
                // A fresh copy per frame: the receiver may keep it while we render the next one.
                output(target.key, out.copyOf())
                buffers[target.key] = out
            }
            buffers.keys.retainAll(list.map { it.key }.toSet())

            val done = nanoTime()
            busyNanos += done - now
            framesInWindow++
            val elapsed = done - windowStart
            if (elapsed >= 1_000_000_000L) {
                // Normalize by the real window length; it is rarely exactly one second.
                val measured = (framesInWindow * 1e9 / elapsed).roundToInt()
                _stats.value = LoopStats(targetFps, measured, busyNanos / 1e6f / framesInWindow, list.size)
                windowStart = done
                framesInWindow = 0
                busyNanos = 0
            }

            nextFrame += frameNanos
            if (done - nextFrame > frameNanos) nextFrame = done // fell behind: skip, do not burst
            val wait = (nextFrame - done) / 1_000_000
            if (wait > 0) delay(wait) else delay(1)
        }
    }

    companion object {
        const val MIN_FPS = 10
        const val MAX_FPS = 60
    }
}
