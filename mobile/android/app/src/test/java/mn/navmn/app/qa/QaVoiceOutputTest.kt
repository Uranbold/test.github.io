package mn.navmn.app.qa

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.i18n.Lang
import mn.navmn.app.voice.VoiceOutput
import mn.navmn.app.voiceplan.PromptClass
import mn.navmn.app.voiceplan.SpokenPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration

/**
 * NAV-005 QA: voice output on Android framework shadows (AC 36, 39). Robolectric's TextToSpeech has no Mongolian
 * voice, so every prompt takes the D23 chime path, which is the expected behaviour on most phones (R1).
 * Real audio, ducking with music and audibility are device checks (AC 73). Test plan ids TC-V*.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class QaVoiceOutputTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun prompt(id: Long, text: String = "300 метрт баруун тийш эргэнэ үү") = SpokenPrompt(id, text, Lang.MN, PromptClass.MANEUVER, 0 to 1, 0)

    private fun ready(): Pair<VoiceOutput, ArrayList<Long>> {
        val v = VoiceOutput(ctx)
        val done = ArrayList<Long>()
        v.onDone = { done += it }
        v.prepare()
        idle()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener?.onInit(TextToSpeech.SUCCESS) }
        idle()
        return v to done
    }

    @Test
    fun tcV01_focusGrantedChimeWithNavigationAttributesAndRelease() {
        val (v, done) = ready()
        var fallback = 0
        v.onFallback = { fallback++ }
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        v.play(prompt(1))
        idle()
        val req = shadowOf(audio).lastAudioFocusRequest
        assertNotNull("AC 36: audio focus requested", req)
        assertEquals("AC 36: transient focus with ducking", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, req!!.durationHint)
        assertEquals("AC 36: usage", AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, req.audioFocusRequest.audioAttributes.usage)
        // No Mongolian voice → D23 chime, never another voice (AC 39).
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNull("AC 39: Mongolian text spoken by a non-Mongolian voice", tts?.let { shadowOf(it).lastSpokenText })
        assertEquals("AC 39: fallback notice trigger", 1, fallback)
        // The chime is ≤ 1 s: the prompt ends and focus is released within 1 s (AC 36, 39).
        idle(1_000)
        assertEquals("AC 39: chime ended within 1 s", listOf(1L), done)
        assertNotNull("AC 36: focus released after the prompt", shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun tcV02_focusDeniedPromptSkippedNotQueued() {
        val (v, done) = ready()
        var fallback = 0
        v.onFallback = { fallback++ }
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        v.play(prompt(7))
        idle()
        assertEquals("AC 36: skipped prompt reported done at once (not queued)", listOf(7L), done)
        assertEquals("AC 36: no chime/voice when focus is denied", 0, fallback)
        // the next prompt with focus granted plays normally; the skipped one is never replayed
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        v.play(prompt(8))
        idle(1_000)
        assertEquals(listOf(7L, 8L), done)
        assertEquals(1, fallback)
    }

    @Test
    fun tcV03_stopEndsTheCurrentPromptAndReleasesFocus() {
        val (v, done) = ready()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        v.play(prompt(3))
        idle(50)
        v.stop() // «Дуусгах», mute, off-route (AC 19, 37)
        idle()
        assertTrue("AC 19/37: stopped prompt reported done", done.contains(3L))
        assertNotNull("AC 36: focus released on stop", shadowOf(audio).lastAbandonedAudioFocusRequest)
        idle(1_000)
        assertEquals("no second completion after stop", 1, done.count { it == 3L })
    }
}
