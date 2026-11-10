package dev.eno2.openrgbremote.core.effects

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EffectLoopTest {

    private fun color(effect: EffectId, time: Double, layout: LedLayout, params: EffectParams = EffectParams()) =
        IntArray(layout.size).also { EffectRenderer.render(effect, time, layout, params, AudioLevels.SILENCE, it) }

    @Test
    fun `renders at the target rate for every target and stops when cancelled`() = runTest {
        val loop = EffectLoop<String> { testScheduler.currentTime * 1_000_000 }
        val frames = mutableMapOf<String, Int>()
        val spec = MutableStateFlow(EffectSpec(EffectId.RAINBOW_WAVE, EffectParams()))
        val targets = MutableStateFlow(listOf(RenderTarget("a", LedLayout.linear(10)), RenderTarget("b", LedLayout.linear(3))))
        val fps = MutableStateFlow(30)
        val job = launch { loop.run(spec, targets, fps, MutableStateFlow(AudioLevels.SILENCE)) { key, colors -> frames[key] = (frames[key] ?: 0) + 1; assertTrue(colors.size == if (key == "a") 10 else 3) } }

        advanceTimeBy(2_000)
        assertTrue(frames.getValue("a") in 59..61, "about 30 fps: $frames")
        assertEquals(frames["a"], frames["b"])
        assertEquals(30, loop.stats.value.targetFps)
        assertTrue(loop.stats.value.fps in 29..31)
        assertEquals(2, loop.stats.value.targets)

        fps.value = 60
        targets.value = targets.value.take(1)
        val before = frames.getValue("a")
        val beforeB = frames.getValue("b")
        advanceTimeBy(1_000)
        assertTrue(frames.getValue("a") - before in 59..61, "fps change applies without restart")
        assertTrue(frames.getValue("b") - beforeB <= 1, "removed target gets no more frames (at most the one in flight)")

        job.cancel()
        val stopped = frames.getValue("a")
        advanceTimeBy(1_000)
        assertEquals(stopped, frames.getValue("a"))
    }

    @Test
    fun `speed scales effect time and parameter changes apply live`() = runTest {
        val loop = EffectLoop<Int> { testScheduler.currentTime * 1_000_000 }
        val layout = LedLayout.linear(8)
        var last = IntArray(0)
        val spec = MutableStateFlow(EffectSpec(EffectId.GRADIENT_FLOW, EffectParams(speed = 2f, colors = listOf(0xFF0000, 0x0000FF))))
        val job = launch { loop.run(spec, MutableStateFlow(listOf(RenderTarget(1, layout))), MutableStateFlow(20), MutableStateFlow(AudioLevels.SILENCE)) { _, c -> last = c } }

        advanceTimeBy(1_001) // the frame rendered at t = 1.0 s real time
        assertEquals(color(EffectId.GRADIENT_FLOW, 2.0, layout, spec.value.params).toList(), last.toList(), "2x speed: effect time 2 s")

        spec.value = spec.value.copy(params = spec.value.params.copy(speed = 1f, brightness = 0f))
        advanceTimeBy(500)
        assertTrue(last.all { it == 0 }, "brightness change applied")
        job.cancel()
    }
}
