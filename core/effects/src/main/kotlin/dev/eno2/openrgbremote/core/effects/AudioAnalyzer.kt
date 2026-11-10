package dev.eno2.openrgbremote.core.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns PCM audio (mono, 16 bit) into [AudioLevels]: a Hann-windowed FFT, [AudioLevels.BANDS]
 * logarithmically spaced bands from [minHz] to [maxHz], automatic gain (the loudest recent signal
 * maps to 1) and attack/release smoothing so lights react quickly but do not flicker.
 *
 * Not thread-safe; feed it from one capture thread.
 */
class AudioAnalyzer(
    private val sampleRate: Int,
    private val fftSize: Int = 1024,
    private val minHz: Float = 40f,
    private val maxHz: Float = 12_000f,
) {
    init {
        require(fftSize >= 64 && fftSize and (fftSize - 1) == 0) { "FFT size must be a power of two" }
    }

    private val normalization = (fftSize / 4f) * (fftSize / 4f)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val buffer = FloatArray(fftSize)
    private var filled = 0
    private val smoothed = FloatArray(AudioLevels.BANDS)
    private var smoothedLevel = 0f
    private var peakDb = MIN_PEAK_DB

    /** Bin range [start, end) of each band. */
    private val bandBins: List<IntRange> = run {
        val binHz = sampleRate.toFloat() / fftSize
        val ratio = (maxHz / minHz).toDouble()
        List(AudioLevels.BANDS) { b ->
            val low = minHz * ratio.pow(b.toDouble() / AudioLevels.BANDS)
            val high = minHz * ratio.pow((b + 1).toDouble() / AudioLevels.BANDS)
            val start = (low / binHz).toInt().coerceIn(1, fftSize / 2 - 1)
            val end = (high / binHz).toInt().coerceIn(start + 1, fftSize / 2)
            start until end
        }
    }

    /** Index of the band containing [hz]; useful for tests and diagnostics. */
    fun bandOf(hz: Float): Int {
        val bin = (hz / (sampleRate.toFloat() / fftSize)).toInt()
        return bandBins.indexOfFirst { bin in it }
    }

    /** Feeds samples; returns new levels whenever a full FFT window was collected, else null. */
    fun process(samples: ShortArray, count: Int = samples.size): AudioLevels? {
        var result: AudioLevels? = null
        for (i in 0 until count) {
            buffer[filled++] = samples[i] / 32768f
            if (filled == fftSize) {
                result = analyze()
                // 50 % overlap: keep the second half for the next window.
                buffer.copyInto(buffer, 0, fftSize / 2, fftSize)
                filled = fftSize / 2
            }
        }
        return result
    }

    private fun analyze(): AudioLevels {
        var sumSquares = 0f
        for (i in 0 until fftSize) {
            re[i] = buffer[i] * window[i]
            im[i] = 0f
            sumSquares += buffer[i] * buffer[i]
        }
        fft(re, im)

        val bandDb = FloatArray(AudioLevels.BANDS)
        var loudest = -120f
        for (b in bandBins.indices) {
            var energy = 0f
            for (bin in bandBins[b]) energy = max(energy, re[bin] * re[bin] + im[bin] * im[bin])
            // Normalized so that a full-scale sine reads 0 dB (Hann window: peak |X| = N / 4).
            val db = 10f * log10(energy / normalization + 1e-12f)
            bandDb[b] = db
            loudest = max(loudest, db)
        }
        // Automatic gain: jump up to new peaks, decay slowly (about 6 dB per 3 s at 86 windows/s).
        peakDb = max(loudest, peakDb - 0.025f).coerceAtLeast(MIN_PEAK_DB)

        val bands = FloatArray(AudioLevels.BANDS)
        for (b in bands.indices) {
            val target = if (bandDb[b] < NOISE_GATE_DB) {
                0f // room noise and mic hiss never light anything up
            } else {
                ((bandDb[b] - (peakDb - DYNAMIC_RANGE_DB)) / DYNAMIC_RANGE_DB).coerceIn(0f, 1f)
            }
            val k = if (target > smoothed[b]) ATTACK else RELEASE
            smoothed[b] += (target - smoothed[b]) * k
            bands[b] = smoothed[b]
        }
        val rms = sqrt(sumSquares / fftSize)
        val levelTarget = ((20f * log10(rms + 1e-9f) + 60f) / 60f).coerceIn(0f, 1f)
        smoothedLevel += (levelTarget - smoothedLevel) * if (levelTarget > smoothedLevel) ATTACK else RELEASE
        return AudioLevels(bands, smoothedLevel)
    }

    private companion object {
        const val DYNAMIC_RANGE_DB = 45f

        /** The automatic gain never amplifies beyond this, so quiet rooms stay quiet (dBFS). */
        const val MIN_PEAK_DB = -40f

        /** Bands below this level count as silence (dBFS). */
        const val NOISE_GATE_DB = -62f
        const val ATTACK = 0.6f
        const val RELEASE = 0.15f

        /** In-place iterative radix-2 FFT. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2 * PI / length
                val wRe = cos(angle).toFloat()
                val wIm = sin(angle).toFloat()
                var start = 0
                while (start < n) {
                    var curRe = 1f
                    var curIm = 0f
                    for (k in 0 until length / 2) {
                        val a = start + k
                        val b = a + length / 2
                        val tRe = re[b] * curRe - im[b] * curIm
                        val tIm = re[b] * curIm + im[b] * curRe
                        re[b] = re[a] - tRe
                        im[b] = im[a] - tIm
                        re[a] += tRe
                        im[a] += tIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    start += length
                }
                length = length shl 1
            }
        }

    }
}
