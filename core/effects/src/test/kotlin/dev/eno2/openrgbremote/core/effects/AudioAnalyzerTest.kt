package dev.eno2.openrgbremote.core.effects

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioAnalyzerTest {
    private val rate = 44_100

    private fun tone(hz: Double, seconds: Double, amplitude: Double = 0.5): ShortArray =
        ShortArray((rate * seconds).toInt()) { (sin(2 * PI * hz * it / rate) * amplitude * Short.MAX_VALUE).toInt().toShort() }

    private fun analyze(samples: ShortArray, analyzer: AudioAnalyzer = AudioAnalyzer(rate)): AudioLevels =
        assertNotNull(analyzer.process(samples))

    @Test
    fun `a bass tone lights the bass band`() {
        val analyzer = AudioAnalyzer(rate)
        val levels = analyze(tone(100.0, 0.5), analyzer)
        val loudest = levels.bands.indices.maxBy { levels.bands[it] }
        assertEquals(analyzer.bandOf(100f), loudest)
        assertTrue(levels.bass > 0.6f && levels.treble < 0.2f, "bass=${levels.bass} treble=${levels.treble}")
    }

    @Test
    fun `a high tone lights the treble band`() {
        val analyzer = AudioAnalyzer(rate)
        val levels = analyze(tone(5_000.0, 0.5), analyzer)
        assertEquals(analyzer.bandOf(5_000f), levels.bands.indices.maxBy { levels.bands[it] })
        assertTrue(levels.treble > levels.bass)
    }

    @Test
    fun `silence and faint noise stay dark`() {
        assertTrue(analyze(ShortArray(rate / 2)).bands.all { it == 0f })
        val faint = ShortArray(rate / 2) { ((it * 7919) % 41 - 20).toShort() } // about -64 dBFS
        assertTrue(analyze(faint).bands.all { it < 0.05f })
    }

    @Test
    fun `levels fall back smoothly after a sound stops`() {
        val analyzer = AudioAnalyzer(rate)
        val loud = analyze(tone(100.0, 0.3), analyzer).bass
        val oneWindowLater = assertNotNull(analyzer.process(ShortArray(512))).bass
        assertTrue(oneWindowLater in 0.1f..<loud, "release is gradual: $loud -> $oneWindowLater")
        val muchLater = analyze(ShortArray(rate), analyzer).bass
        assertTrue(muchLater < 0.05f)
    }

    @Test
    fun `small chunks are buffered until a window is complete`() {
        val analyzer = AudioAnalyzer(rate)
        assertNull(analyzer.process(ShortArray(500)))
        assertNotNull(analyzer.process(ShortArray(600)))
    }
}
