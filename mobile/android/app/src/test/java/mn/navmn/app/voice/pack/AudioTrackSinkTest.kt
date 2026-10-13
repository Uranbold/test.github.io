package mn.navmn.app.voice.pack

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A [StaticTrack] that follows the framework `AudioTrack` state machine in MODE_STATIC (android.media.AudioTrack, API 26
 * to 36): STATE_NO_STATIC_DATA after creation, STATE_INITIALIZED only after a write of more than 0 frames,
 * PLAYSTATE_PLAYING after play(). [initFails]: the native track could not be created (STATE_UNINITIALIZED).
 */
internal class FrameworkLikeTrack(private val initFails: Boolean = false, private val shortWrite: Boolean = false) : StaticTrack {
    override var state: Int = if (initFails) AudioTrack.STATE_UNINITIALIZED else AudioTrack.STATE_NO_STATIC_DATA
        private set
    override var playState: Int = AudioTrack.PLAYSTATE_STOPPED
        private set
    var written: ShortArray? = null
    var plays = 0
    var released = false

    override fun write(pcm: ShortArray): Int {
        if (state == AudioTrack.STATE_UNINITIALIZED) return AudioTrack.ERROR_INVALID_OPERATION
        val n = if (shortWrite) pcm.size / 2 else pcm.size
        written = pcm.copyOf(n)
        if (state == AudioTrack.STATE_NO_STATIC_DATA && n > 0) state = AudioTrack.STATE_INITIALIZED
        return n
    }

    override fun play() {
        check(state == AudioTrack.STATE_INITIALIZED) { "play() called on uninitialized AudioTrack." }
        playState = AudioTrack.PLAYSTATE_PLAYING
        plays++
    }

    override fun stop() {
        playState = AudioTrack.PLAYSTATE_STOPPED
    }

    override fun release() {
        released = true
        state = AudioTrack.STATE_UNINITIALIZED
    }
}

/**
 * NAV-016 regression (PO phone test 2026-10-13, demo APKs FEMALE5v2 and MALE4v2: "only the chime sounds"). A MODE_STATIC
 * `AudioTrack` is STATE_NO_STATIC_DATA after build() and becomes STATE_INITIALIZED only through write(). The old
 * [AudioTrackSink] checked STATE_INITIALIZED before writing, so start() returned false for every prompt:
 * PLAYBACK_FAILED → the chime and the A1 notice, then the chime for the rest of the session.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AudioTrackSinkTest {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** A prompt-sized buffer at the pack rate: 1.5 s of a 440 Hz tone at 22,050 Hz. */
    private val pcm = ShortArray(33_075) { (8_000 * Math.sin(2 * Math.PI * 440 * it / 22_050.0)).toInt().toShort() }

    @Test
    fun theFrameworkStaticTrackHasNoStaticDataBeforeTheWrite() {
        // The premise of the defect, on the real framework AudioTrack Java code (Robolectric shadows only the native
        // layer; its native static write returns 0, so the write itself is covered by FrameworkLikeTrack below).
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(22_050)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            assertEquals(AudioTrack.STATE_NO_STATIC_DATA, t.state)
        } finally {
            t.release()
        }
    }

    @Test
    fun startWritesThenPlaysAStaticTrack() {
        val track = FrameworkLikeTrack()
        var openedAt = 0
        val sink = AudioTrackSink { p, rate ->
            assertEquals(pcm.size, p.size)
            openedAt = rate
            track
        }
        assertTrue("start() accepts a freshly created static track (was false: PLAYBACK_FAILED on every prompt)", sink.start(pcm, 22_050))
        assertEquals(22_050, openedAt)
        assertTrue("the whole prompt is written before play()", pcm.contentEquals(track.written))
        assertEquals(1, track.plays)
        assertTrue("a playing prompt is healthy", sink.healthy())
        sink.stop()
        assertTrue(track.released)
        assertFalse("a stopped sink is not healthy", sink.healthy())
    }

    @Test
    fun aTrackThatDidNotInitialiseFailsWithoutPlaying() {
        val track = FrameworkLikeTrack(initFails = true)
        val sink = AudioTrackSink { _, _ -> track }
        assertFalse(sink.start(pcm, 22_050))
        assertEquals(0, track.plays)
        assertTrue("released at once", track.released)
        assertFalse(sink.healthy())
    }

    @Test
    fun aShortWriteFailsWithoutPlaying() {
        val track = FrameworkLikeTrack(shortWrite = true)
        val sink = AudioTrackSink { _, _ -> track }
        assertFalse("a prompt is all or nothing (AC 36)", sink.start(pcm, 22_050))
        assertEquals(0, track.plays)
        assertTrue(track.released)
    }

    @Test
    fun aTrackThatCannotBeCreatedFails() {
        val sink = AudioTrackSink { _, _ -> throw UnsupportedOperationException("Cannot create AudioTrack") }
        assertFalse(sink.start(pcm, 22_050))
        assertFalse(sink.healthy())
    }
}
