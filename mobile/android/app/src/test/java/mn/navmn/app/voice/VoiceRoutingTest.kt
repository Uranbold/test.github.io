package mn.navmn.app.voice

import mn.navmn.app.i18n.Lang
import mn.navmn.app.support.TestVoice
import mn.navmn.app.voice.pack.PackPolicy
import mn.navmn.app.voice.pack.PackReason
import mn.navmn.app.voice.pack.PackState
import mn.navmn.app.voice.pack.TestPacks
import mn.navmn.app.voice.pack.VoicePackValidator
import mn.navmn.app.voice.VoiceRouting.Route
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** NAV-016 M8 (ADR-0010 decision 1, D80, AC 32, 38, 41–43): Mongolian = pack or chime, never TTS; English unchanged. */
class VoiceRoutingTest {
    private val valid = VoicePackValidator.validate(TestPacks.build().packFiles(), TestVoice.inventory, PackPolicy.OPTIONAL)
    private val mnTts = Locale.forLanguageTag("mn-MN")

    @Test
    fun mongolianIsThePackOrTheChimeNeverTts() {
        assertEquals(Route.PACK, VoiceRouting.route(Lang.MN, valid, mnTts, sessionFallback = false))
        assertEquals("AC 32: a device mn voice is ignored", Route.PACK, VoiceRouting.route(Lang.MN, valid, null, false))
        assertEquals("AC 43: after an error, the rest of the session", Route.CHIME, VoiceRouting.route(Lang.MN, valid, mnTts, true))
        for (s in listOf(PackState.Pending, PackState.Invalid(PackReason.NO_PACK), PackState.Invalid(PackReason.FILE_HASH_MISMATCH))) {
            assertEquals("AC 41: $s", Route.CHIME, VoiceRouting.route(Lang.MN, s, mnTts, false))
        }
    }

    @Test
    fun englishKeepsTtsWithTheChimeFallback() {
        assertEquals("AC 38", Route.TTS, VoiceRouting.route(Lang.EN, valid, Locale.US, false))
        assertEquals(Route.CHIME, VoiceRouting.route(Lang.EN, valid, null, false))
        assertEquals(Route.CHIME, VoiceRouting.route(Lang.EN, PackState.Invalid(PackReason.NO_PACK), null, false))
        assertEquals("the pack is never used for English", Route.TTS, VoiceRouting.route(Lang.EN, PackState.Invalid(PackReason.NO_PACK), Locale.US, false))
    }
}
