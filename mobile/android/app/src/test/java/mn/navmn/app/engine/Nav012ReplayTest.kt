package mn.navmn.app.engine

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.Tracks
import mn.navmn.app.voiceplan.PromptClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NAV-012 through the guidance core and the REAL Ferrostar navigation session (host library), on a virtual clock:
 *  - restore from the stored route bytes (B-A1): the bytes go through the unchanged NAV-005 pipeline
 *    (`createOsrmResponseParser` → `createNavigationSession`), the first good fix places the session on the route with
 *    0 requests and exactly one prompt, no depart prompt (AC 18–20); off the stored route → reroute (AC 19); no good
 *    fix within 10 s → GPS lost, 0 requests (AC 19);
 *  - calls (AC 36–38): 0 prompts during a call, the playing prompt stops, one catch-up after the call, 5 s guard.
 */
class Nav012ReplayTest {
    private val p3 = LatLon(47.8858, 106.9173)
    private val mn = TestStrings.of(Lang.MN)

    @Before
    fun need() = HostFerrostar.require()

    private fun car() = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, Lang.MN, destination = p3)

    private fun departLike(text: String) = text.endsWith("зүг рүү явна уу")

    @Test
    fun restoreOnTheStoredRouteZeroRequestsOneCatchUpNoDepart() {
        val r = car()
        val line = r.initial.plan.geometry
        val length = Geo.length(line)
        val fixes = Tracks.along(line, 10.0, t0 = 2_000, fromM = length * 0.87) // on step 1 (step 0 is 3.58 km)
        r.run(fixes, tailMs = 10_000, restoredAt = 0)
        // Before the first good fix: the restoring banner, no puck, the restore notice (AC 18).
        val first = r.states.first()
        assertEquals(Banner.Restoring, first.banner)
        assertTrue(first.restoring)
        assertNull(first.puck)
        assertTrue(first.resumedNoticeVisible)
        assertEquals(mn[StringKey.STATUS_LOADING], r.banners.first().second)
        // B-A1: on the stored route → 0 route requests for the whole remaining trip (AC 19, 20).
        assertEquals(0, r.requests.size)
        assertEquals(1, r.events.count { it.second == GuidanceEvent.Arrived })
        // Exactly one prompt for the next manoeuvre within 3 s of the first good fix; no depart prompt.
        val firstFixAt = fixes.first().elapsedMs
        val early = r.spoken.filter { it.first in firstFixAt..firstFixAt + 3_000 }
        assertEquals(early.map { it.second.text }.toString(), 1, early.size)
        assertEquals(PromptClass.MANEUVER, early.single().second.cls)
        assertFalse(r.spokenTexts().any(::departLike))
        // The first banner after the restore is a manoeuvre; the start step comes from the geometry (87 % along is on
        // step 1, «Дүнжингаравын гудамж», after the left turn).
        val placed = r.states.first { !it.restoring }
        assertTrue(placed.banner is Banner.Maneuver)
        val start = r.log.firstNotNullOf { Regex("restore start step (\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals(1, start)
        assertEquals(start + 1, (placed.banner as Banner.Maneuver).index)
        assertFalse(r.states.last { it.phase != GuidancePhase.ARRIVED }.resumedNoticeVisible)
    }

    @Test
    fun restoreOffTheStoredRouteStartsAnOffRouteEpisodeAndReroutes() {
        val r = car()
        val line = r.initial.plan.geometry
        val mid = Geo.along(line, Geo.length(line) * 0.5)
        val away = Geo.offset(mid, 90.0, 400.0)
        val fixes = (0 until 8).map { Tracks.fix(Geo.offset(away, 0.0, it * 5.0), 1_000L + it * 1_000L, 0.0, 5.0) }
        r.run(fixes, tailMs = 3_000, restoredAt = 0)
        assertTrue(r.log.any { it == "restore off the stored route" })
        assertTrue(r.spokenTexts().contains(mn[StringKey.VOICE_OFF_ROUTE]))
        assertTrue("reroute with the stored options", r.requests.isNotEmpty())
        val req = r.requests.first().second
        assertEquals(p3, req.destination)
        assertEquals(TravelMode.CAR, req.mode)
        assertFalse(r.spokenTexts().any(::departLike))
    }

    @Test
    fun noGoodFixWithinTenSecondsIsGpsLostThenOnePromptOnTheFirstFix() {
        val r = car()
        val line = r.initial.plan.geometry
        val poor = (1..12).map { Tracks.fix(line.first(), it * 1_000L, null, null, accuracy = 80.0) }
        val good = Tracks.along(line, 10.0, t0 = 15_000, fromM = Geo.length(line) * 0.3, toM = Geo.length(line) * 0.35)
        r.run(poor + good, tailMs = 2_000, restoredAt = 0)
        val lost = r.states.first { it.gpsLost }
        assertEquals("the banner stays «Ачаалж байна…»", Banner.Restoring, lost.banner)
        assertEquals(0, r.requests.size)
        val texts = r.spokenTexts()
        assertEquals(1, texts.count { it == mn[StringKey.NAV_GPS_LOST] })
        assertEquals(1, texts.count { it == mn[StringKey.NAV_GPS_RESTORED] })
        // The GPS catch-up and the restore prompt are the same prompt, never two.
        val firstGood = good.first().elapsedMs
        assertEquals(texts.toString(), 1, r.spoken.count { it.second.cls == PromptClass.MANEUVER && it.first in firstGood..firstGood + 3_000 })
    }

    // ------------------------------------------------------------------------------------------- calls

    /** Times of manoeuvre prompts in a call-free run (the baseline schedule). */
    private fun baseline(): List<Pair<Long, Pair<Int, Int>?>> {
        val r = car()
        r.run(Tracks.along(r.initial.plan.geometry, 10.0), tailMs = 5_000)
        return r.spoken.filter { it.second.cls == PromptClass.MANEUVER && it.second.text.let { t -> !departLike(t) } }.map { it.first to it.second.maneuver }
    }

    @Test
    fun promptsAreSkippedDuringACallAndOneCatchUpFollows() {
        val (tMain, maneuver) = baseline().first()
        val callStart = tMain - 3_000
        val callEnd = tMain + 4_000
        val r = car()
        r.run(Tracks.along(r.initial.plan.geometry, 10.0), tailMs = 5_000, callSignals = mapOf(callStart to true, callEnd to false))
        // AC 37: 0 utterances and 0 chimes during the call (plus the 1 s end debounce).
        assertTrue(r.spoken.none { it.first in callStart..callEnd + 999 })
        // AC 38: exactly one catch-up for that manoeuvre within 2 s after the call ended, not doubled within 5 s.
        val after = r.spoken.filter { it.first in callEnd..callEnd + 2_000 }
        assertEquals(after.map { it.second.text }.toString(), 1, after.size)
        assertEquals(maneuver, after.single().second.maneuver)
        val t = after.single().first
        assertEquals(1, r.spoken.count { it.second.maneuver == maneuver && it.first in t..t + 5_000 })
        assertTrue(r.log.contains("call started") && r.log.contains("call ended"))
    }

    @Test
    fun noCatchUpWhenNothingWasSkipped() {
        val r = car()
        // A call long before any manoeuvre trigger: nothing for the next manoeuvre is skipped, so no catch-up.
        r.run(Tracks.along(r.initial.plan.geometry, 10.0), tailMs = 5_000, callSignals = mapOf(8_000L to true, 9_000L to false))
        assertTrue(r.spoken.none { it.first in 10_000L..12_000L })
    }

    @Test
    fun aCallStopsThePlayingPrompt() {
        val r = car()
        r.run(Tracks.along(r.initial.plan.geometry, 10.0).take(2), tailMs = 0)
        assertTrue("depart prompt playing", r.core.queue.current != null)
        r.core.onCallSignal(true)
        assertNull(r.core.queue.current)
        assertTrue(r.core.callGate.inCall)
    }
}
