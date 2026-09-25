@file:Suppress("MagicNumber")

package net.primal.android.core.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.primal.core.utils.runCatching

/** A short, low-volume three-note chime generated in memory for positive reaction feedback. */
object LikeChimePlayer {
    private const val SAMPLE_RATE = 44_100
    private const val BYTES_PER_SAMPLE = 2
    private const val CHIME_DURATION_SECONDS = 0.43
    private const val NOTE_DURATION_SECONDS = 0.2
    private const val FADE_IN_SECONDS = 0.018
    private const val FADE_OUT_SECONDS = 0.07
    private const val NOTE_AMPLITUDE = 0.22
    private const val PLAYBACK_VOLUME = 0.55f
    private const val RELEASE_DELAY_MILLIS = 470L

    private val playing = AtomicBoolean(false)
    private val samples: ShortArray by lazy(::createChimeSamples)

    suspend fun play(context: Context) {
        if (!context.areSoundEffectsEnabled() || !playing.compareAndSet(false, true)) return

        try {
            try {
                withContext(Dispatchers.Default) {
                    playSamples()
                }
            } catch (_: IllegalArgumentException) {
                // Some devices expose no compatible sonification output. Visual feedback remains.
            } catch (_: IllegalStateException) {
                // Audio output can disappear while an app is moving to the background.
            } catch (_: SecurityException) {
                // Device policy may disallow system sonification streams.
            } catch (_: UnsupportedOperationException) {
                // Keep reactions functional on audio implementations without static tracks.
            }
        } finally {
            playing.set(false)
        }
    }

    private suspend fun playSamples() {
        val sampleBytes = samples.size * BYTES_PER_SAMPLE
        val minimumBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(sampleBytes, minimumBufferSize))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return
            audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.setVolume(PLAYBACK_VOLUME)
            audioTrack.play()
            delay(RELEASE_DELAY_MILLIS)
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
        }
    }

    private fun createChimeSamples(): ShortArray {
        val notes = listOf(
            ChimeNote(startSeconds = 0.0, frequencyHz = 523.25),
            ChimeNote(startSeconds = 0.1, frequencyHz = 659.25),
            ChimeNote(startSeconds = 0.2, frequencyHz = 783.99),
        )
        val sampleCount = (SAMPLE_RATE * CHIME_DURATION_SECONDS).roundToInt()
        return ShortArray(sampleCount) { sampleIndex ->
            val timeSeconds = sampleIndex.toDouble() / SAMPLE_RATE
            val mixedSample = notes.sumOf { note -> note.sampleAt(timeSeconds) }
            (mixedSample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    private fun ChimeNote.sampleAt(timeSeconds: Double): Double {
        val noteTime = timeSeconds - startSeconds
        if (noteTime !in 0.0..NOTE_DURATION_SECONDS) return 0.0

        val fadeIn = (noteTime / FADE_IN_SECONDS).coerceIn(0.0, 1.0)
        val fadeOut = ((NOTE_DURATION_SECONDS - noteTime) / FADE_OUT_SECONDS).coerceIn(0.0, 1.0)
        val envelope = minOf(fadeIn, fadeOut)
        return sin(2.0 * PI * frequencyHz * noteTime) * envelope * NOTE_AMPLITUDE
    }

    private fun Context.areSoundEffectsEnabled(): Boolean =
        try {
            Settings.System.getInt(contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1
        } catch (_: SecurityException) {
            false
        }

    private data class ChimeNote(
        val startSeconds: Double,
        val frequencyHz: Double,
    )
}
