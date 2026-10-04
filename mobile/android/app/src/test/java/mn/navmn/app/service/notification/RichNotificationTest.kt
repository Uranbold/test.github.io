package mn.navmn.app.service.notification

import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Progress
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * NAV-012 AC 1–2, 12 (screen spec › Notification content per state) and AC 2, 4, 5 posting rules (ADR-0013 §4).
 * Pure JVM tests on the shipped string resources.
 */
class RichNotificationTest {
    private val mn = TestStrings.of(Lang.MN)
    private val en = TestStrings.of(Lang.EN)
    private val ub = ZoneId.of("Asia/Ulaanbaatar")
    private val base = 1_790_000_000_000L // 2026-09-21 14:13:20 UTC

    private fun state(banner: Banner, gpsLost: Boolean = false, muted: Boolean = false, generation: Int = 0) = GuidanceState(
        phase = if (banner is Banner.Arrival) GuidancePhase.ARRIVED else GuidancePhase.NAVIGATING,
        generation = generation,
        banner = banner,
        progress = Progress(4_000.0, 600.0, base),
        puck = null,
        route = emptyList(),
        trip = Trip(LatLon(47.8858, 106.9173), "Зайсан толгой", TravelMode.CAR, false),
        gpsLost = gpsLost,
        gpsRestoredVisible = false,
        offline = false,
        voiceNoticeVisible = false,
        muted = muted,
        speedMps = 10.0,
        restoring = banner is Banner.Restoring,
    )

    private val left = Banner.Maneuver(KeyResult(ManeuverKey.TURN_LEFT), 304.0, "Дүнжингаравын гудамж", null, false, index = 1)

    @Test
    fun maneuverDistanceTitleInstructionTextStreetAndEta() {
        val n = RichNotification.of(state(left), Lang.MN, mn, ub)
        assertEquals(RichNotification.Kind.MANEUVER, n.kind)
        assertEquals(RichNotification.LargeIcon.Maneuver(ManeuverKey.TURN_LEFT), n.largeIcon)
        assertEquals("300 м", n.title)
        assertEquals("Зүүн тийш эргэнэ үү", n.text)
        assertEquals("Зүүн тийш эргэнэ үү\nДүнжингаравын гудамж", n.bigText)
        assertEquals("Хүрэх цаг 22:23", n.subText) // base 14:13:20 UTC + 600 s = 22:23:20 in UB (UTC+8)
        assertEquals("Дууг хаах", n.voiceAction)
        assertEquals("Дуусгах", n.endAction)
        assertFalse(n.rerouteColour)
        // Never the destination name or coordinates (AC 12, NAV-005 AC 67).
        for (f in listOf(n.title, n.text, n.bigText, n.subText)) {
            assertFalse(f!!.contains("Зайсан"))
            assertFalse(Regex("-?\\d{1,3}\\.\\d{4,}").containsMatchIn(f))
        }
        // No street → no second line.
        assertEquals("Зүүн тийш эргэнэ үү", RichNotification.of(state(left.copy(street = "")), Lang.MN, mn, ub).bigText)
    }

    @Test
    fun mutedShowsUnmuteActionEnglishFollowsTheLanguage() {
        assertEquals("Дууг нээх", RichNotification.of(state(left, muted = true), Lang.MN, mn, ub).voiceAction)
        val e = RichNotification.of(state(left), Lang.EN, en, ub)
        assertEquals("300 m", e.title)
        assertEquals("Turn left", e.text)
        assertEquals("Mute", e.voiceAction)
        assertEquals("End", e.endAction)
        assertTrue(e.subText!!.startsWith("Arrive at"))
    }

    @Test
    fun statusStatesUseTheStatusAsTitle() {
        val r = RichNotification.of(state(Banner.Rerouting(RerouteSecondary.UNAVAILABLE)), Lang.MN, mn, ub)
        assertEquals("Маршрутыг дахин тооцоолж байна", r.title)
        assertNull(r.text)
        assertNull(r.subText)
        assertNull(r.largeIcon)
        assertTrue(r.rerouteColour)

        val g = RichNotification.of(state(left.copy(stale = true), gpsLost = true), Lang.MN, mn, ub)
        assertEquals("GPS дохио тасарлаа", g.title)
        assertEquals("Зүүн тийш эргэнэ үү", g.text)
        assertNull(g.subText)
        assertNull(g.largeIcon)

        val loading = RichNotification.of(state(Banner.Restoring), Lang.MN, mn, ub)
        assertEquals("Ачаалж байна…", loading.title)
        assertTrue(loading.rerouteColour)
        assertEquals("Дууг хаах", loading.voiceAction)

        val a = RichNotification.of(state(Banner.Arrival(KeyResult(ManeuverKey.ARRIVE_RIGHT), "")), Lang.MN, mn, ub)
        assertNull(a.title)
        assertEquals("Таны очих газар баруун талд байна", a.text)
        assertEquals(RichNotification.LargeIcon.Flag, a.largeIcon)
        assertNull(a.voiceAction)
        assertNull(a.endAction)
    }

    // ------------------------------------------------------------------------------------------- posting policy

    private fun at(d: Double, index: Int = 1) = RichNotification.of(state(left.copy(distanceM = d, index = index)), Lang.MN, mn, ub)

    @Test
    fun stateChangeWithinOneSecondDistanceEveryTwoSecondsAtMostOncePerSecond() {
        val p = NotificationPostPolicy()
        assertTrue(p.decide(at(500.0), 0))
        assertFalse("same content", p.decide(at(500.0), 300))
        assertFalse("distance only, < 2 s", p.decide(at(480.0), 1_000))
        assertFalse(p.decide(at(470.0), 1_900))
        assertTrue("distance at 2 s", p.decide(at(460.0), 2_000))
        // A new manoeuvre 300 ms later waits for the 1 s floor, then posts (≤ 1 s after the change).
        assertFalse(p.decide(at(900.0, index = 2), 2_300))
        assertTrue(p.decide(at(900.0, index = 2), 3_000))
        // Muting changes the action label: posted as a state change (not throttled to 2 s).
        val muted = RichNotification.of(state(left.copy(distanceM = 900.0, index = 2), muted = true), Lang.MN, mn, ub)
        assertTrue(p.decide(muted, 4_000))
    }

    @Test
    fun android14DismissalRepostsOnlyAtTheNextInstructionChange() {
        val p = NotificationPostPolicy()
        assertTrue(p.decide(at(500.0), 0))
        p.onDismissed()
        assertTrue(p.dismissed)
        assertFalse("distance updates never re-post", p.decide(at(300.0), 5_000))
        assertFalse(p.decide(at(100.0), 10_000))
        val gpsLost = RichNotification.of(state(left, gpsLost = true), Lang.MN, mn, ub)
        assertTrue("next instruction change → posted once", p.decide(gpsLost, 11_000))
        assertFalse(p.dismissed)
        assertFalse(p.decide(gpsLost, 12_000))
    }

    @Test
    fun languageSwitchRepostsTheSameState() {
        val p = NotificationPostPolicy()
        assertTrue(p.decide(at(500.0), 0))
        p.invalidate()
        assertTrue(p.decide(RichNotification.of(state(left.copy(distanceM = 500.0)), Lang.EN, en, ub), 1_000))
    }
}
