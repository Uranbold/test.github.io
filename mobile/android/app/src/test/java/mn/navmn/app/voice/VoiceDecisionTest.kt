package mn.navmn.app.voice

import mn.navmn.app.i18n.Lang
import mn.navmn.app.permission.LocationAccess
import mn.navmn.app.permission.LocationAction
import mn.navmn.app.permission.PermissionStatus
import mn.navmn.app.preview.LocationProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** NAV-005 AC 38–39 (usable voice), navigation-ux §4.8 chime, AC 8–12 permission decisions. */
class VoiceDecisionTest {
    private class FakeTts(private val results: Map<String, Int>, private val actual: String?) : TtsProbe {
        var asked = ArrayList<Locale>()
        override fun setLanguage(locale: Locale): Int { asked += locale; return results[locale.toLanguageTag()] ?: -2 }
        override fun currentVoiceLanguage(): String? = actual
    }

    @Test
    fun mongolianVoiceUsableOnlyWhenTheEngineReallyHasIt() {
        assertEquals("mn-MN", VoiceSelector.select(FakeTts(mapOf("mn-MN" to 1), "mn"), true, 500, Lang.MN)?.toLanguageTag())
        assertEquals("mn", VoiceSelector.select(FakeTts(mapOf("mn-MN" to -1, "mn" to 0), "mn"), true, 500, Lang.MN)?.toLanguageTag())
        assertNull("missing data", VoiceSelector.select(FakeTts(mapOf("mn-MN" to -1, "mn" to -1), "mn"), true, 500, Lang.MN))
        assertNull("not supported", VoiceSelector.select(FakeTts(emptyMap(), null), true, 500, Lang.MN))
        assertNull("silent fallback to another language", VoiceSelector.select(FakeTts(mapOf("mn-MN" to 0), "en"), true, 500, Lang.MN))
        assertNull("init over 3 s", VoiceSelector.select(FakeTts(mapOf("mn-MN" to 0), "mn"), true, 3_001, Lang.MN))
        assertNull("init failed", VoiceSelector.select(FakeTts(mapOf("mn-MN" to 0), "mn"), false, 100, Lang.MN))
        assertNull("no engine", VoiceSelector.select(null, true, 100, Lang.MN))
        assertEquals(Locale.US, VoiceSelector.select(FakeTts(mapOf("en-US" to 0), "en"), true, 100, Lang.EN))
    }

    @Test
    fun chimeIsShortTwoToneAndBelowMinusThreeDbfs() {
        val s = ChimePcm.samples()
        val ms = s.size * 1000.0 / ChimePcm.SAMPLE_RATE
        assertTrue("duration $ms", ms in 300.0..400.0)
        val peak = s.maxOf { Math.abs(it.toInt()) } / 32767.0
        assertTrue("peak $peak", peak <= Math.pow(10.0, -3.0 / 20.0) + 1e-3 && peak > 0.6)
        // zero crossings in the first tone (120 ms at 880 Hz ≈ 211 crossings)
        val first = s.copyOfRange(0, (0.12 * ChimePcm.SAMPLE_RATE).toInt())
        val zc = (1 until first.size).count { (first[it - 1] < 0) != (first[it] < 0) }
        assertTrue("crossings $zc", zc in 190..225)
    }

    @Test
    fun locationAccessDecisions() {
        assertEquals(LocationAccess.Decision.ShowRationale, LocationAccess.decide(LocationAction.PREVIEW_ORIGIN, PermissionStatus.NOT_ASKED, true))
        assertEquals(LocationAccess.Decision.Proceed, LocationAccess.decide(LocationAction.PREVIEW_ORIGIN, PermissionStatus.PRECISE, true))
        assertEquals(LocationAccess.Decision.Problem(LocationProblem.SERVICES_OFF), LocationAccess.decide(LocationAction.START_GUIDANCE, PermissionStatus.PRECISE, false))
        assertEquals(LocationAccess.Decision.Problem(LocationProblem.APPROXIMATE), LocationAccess.decide(LocationAction.START_GUIDANCE, PermissionStatus.APPROXIMATE, true))
        assertEquals(LocationAccess.Decision.Proceed, LocationAccess.decide(LocationAction.MY_LOCATION, PermissionStatus.APPROXIMATE, true))
        assertEquals(LocationAccess.Decision.Problem(LocationProblem.DENIED), LocationAccess.decide(LocationAction.PREVIEW_ORIGIN, PermissionStatus.DENIED_PERMANENT, true))
        assertEquals(LocationAccess.Decision.Problem(LocationProblem.DENIED), LocationAccess.afterRequest(LocationAction.PREVIEW_ORIGIN, PermissionStatus.DENIED, true))
        assertEquals(LocationAccess.Decision.ShowRationale, LocationAccess.decide(LocationAction.PREVIEW_ORIGIN, PermissionStatus.DENIED, true))
    }
}
