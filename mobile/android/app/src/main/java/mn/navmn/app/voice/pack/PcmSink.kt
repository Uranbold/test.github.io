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
 * The `AudioTrack` calls [AudioTrackSink] makes, as a seam: Robolectric's AudioTrack shadow returns 0 from a static
 * write, so the sink's call order is tested against a fake that follows the framework state machine
 * (AudioTrackSinkTest).
 */
internal interface StaticTrack {
    /** AudioTrack.getState(): STATE_NO_STATIC_DATA after creation in MODE_STATIC, STATE_INITIALIZED after the write. */
    val state: Int
    val playState: Int
    fun write(pcm: ShortArray): Int
    fun play()
    fun stop()
    fun release()
}

private class FrameworkTrack(private val t: AudioTrack) : StaticTrack {
    override val state: Int get() = t.state
    override val playState: Int get() = t.playState
    override fun write(pcm: ShortArray): Int = t.write(pcm, 0, pcm.size)
    override fun play() = t.play()
    override fun stop() = t.stop()
    override fun release() = t.release()
}

/**
 * NAV-016 (ADR-0010 §6): one `AudioTrack` in `MODE_STATIC` per prompt, the same output path as the chime, so the joins
 * are sample-exact. Usage and content type come from [attributes] (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, SPEECH).
 */
class AudioTrackSink internal constructor(private val open: (pcm: ShortArray, sampleRateHz: Int) -> StaticTrack) : PcmSink {
    constructor(attributes: AudioAttributes) : this({ pcm, rate ->
        FrameworkTrack(
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build(),
        )
    })

    private var track: StaticTrack? = null

    override fun start(pcm: ShortArray, sampleRateHz: Int): Boolean = runCatching {
        val t = open(pcm, sampleRateHz)
        track = t
        // A MODE_STATIC track is STATE_NO_STATIC_DATA until its data is written; only the write makes it
        // STATE_INITIALIZED. Checking for STATE_INITIALIZED before the write failed every prompt on a real device
        // (PLAYBACK_FAILED → the chime for the rest of the session; PO phone test 2026-10-13). So: reject a track that
        // did not initialise at all, write, then require STATE_INITIALIZED.
        check(t.state != AudioTrack.STATE_UNINITIALIZED)
        check(t.write(pcm) == pcm.size)
        check(t.state == AudioTrack.STATE_INITIALIZED)
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
