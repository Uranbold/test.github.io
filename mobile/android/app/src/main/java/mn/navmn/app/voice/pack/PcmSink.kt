package mn.navmn.app.voice.pack

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays one prompt's PCM (mono 16-bit) in one audio session. */
interface PcmSink {
    /** Starts playback; false if the output could not be created or started (then nothing plays). */
    fun start(pcm: ShortArray, sampleRateHz: Int): Boolean

    /** false once the output failed during playback (pack-format §8 `PLAYBACK_FAILED`). */
    fun healthy(): Boolean

    fun stop()
}

fun interface PcmSinkFactory {
    fun create(attributes: AudioAttributes): PcmSink
}

/**
 * NAV-016 (ADR-0010 §6): one `AudioTrack` in `MODE_STATIC` per prompt, the same output path as the chime, so the joins
 * are sample-exact. Usage and content type come from [attributes] (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, SPEECH).
 */
class AudioTrackSink(private val attributes: AudioAttributes) : PcmSink {
    private var track: AudioTrack? = null

    override fun start(pcm: ShortArray, sampleRateHz: Int): Boolean = runCatching {
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track = t
        check(t.state == AudioTrack.STATE_INITIALIZED)
        check(t.write(pcm, 0, pcm.size) == pcm.size)
        t.play()
        true
    }.getOrElse {
        stop()
        false
    }

    override fun healthy(): Boolean {
        val t = track ?: return false
        return t.state == AudioTrack.STATE_INITIALIZED && t.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    override fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    companion object {
        val FACTORY = PcmSinkFactory { AudioTrackSink(it) }
    }
}
