package dev.eno2.openrgbremote.core.data.effects

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eno2.openrgbremote.core.effects.AudioAnalyzer
import dev.eno2.openrgbremote.core.effects.AudioLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import javax.inject.Inject

/**
 * Records the microphone and turns it into [AudioLevels] on the device. Audio never leaves the
 * phone and is not stored: only 16 loudness values per window reach the effect.
 *
 * Android only lets apps record the microphone, not what other apps play (that needs screen
 * capture consent), so the music effect reacts to sound in the room.
 */
class MicrophoneAudioSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioLevelSource {

    override val isAvailable: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun levels(): Flow<AudioLevels> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return emptyFlow()
        }
        return flow {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return@flow
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 4096))
            } catch (_: SecurityException) {
                return@flow // permission revoked in the meantime
            }
            try {
                if (record.state != AudioRecord.STATE_INITIALIZED) return@flow
                record.startRecording()
                val analyzer = AudioAnalyzer(SAMPLE_RATE)
                val chunk = ShortArray(512)
                while (currentCoroutineContext().isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) break
                    analyzer.process(chunk, read)?.let { emit(it) }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }.flowOn(Dispatchers.IO)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
