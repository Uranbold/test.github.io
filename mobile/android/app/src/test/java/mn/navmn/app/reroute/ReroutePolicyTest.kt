package mn.navmn.app.reroute

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.route.RouteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 44, 47–50 with a fake clock (ADR-0009 §4 P1–P10). */
class ReroutePolicyTest {
    private val origin = LatLon(47.9, 106.9)

    private fun ctx(now: Long, pos: LatLon = origin, online: Boolean = true, inEpisode: Boolean = true, gpsOk: Boolean = true, finished: Boolean = false) =
        ReroutePolicy.Context(now, inEpisode, online, gpsOk, finished, pos)

    /** Simulates a server that always answers [outcome] after [latencyMs]; returns request start times. */
    private fun simulate(outcome: RouteOutcome, durationMs: Long, latencyMs: Long = 200, step: Long = 100, move: Boolean = false): List<Long> {
        val p = ReroutePolicy()
        val starts = ArrayList<Long>()
        var pendingUntil = -1L
        var t = 0L
        while (t <= durationMs) {
            if (pendingUntil in 0..t) {
                p.onOutcome(t, outcome)
                pendingUntil = -1
            }
            val pos = if (move) Geo.offset(origin, 90.0, t / 1000.0 * 15.0) else origin // 15 m/s when moving
            if (p.mayStart(ctx(t, pos))) {
                p.onStarted(t, pos)
                starts += t
                pendingUntil = t + latencyMs
            }
            assertTrue("more than one in flight", !(p.inFlight && pendingUntil < 0))
            t += step
        }
        return starts
    }

    private fun assertPacing(starts: List<Long>) {
        for (i in 1 until starts.size) assertTrue("gap ${starts[i] - starts[i - 1]} < 5 s", starts[i] - starts[i - 1] >= 5_000)
        for (s in starts) {
            val inWindow = starts.count { it >= s && it - s <= 60_000 }
            assertTrue("$inWindow starts within 60 s from $s", inWindow <= 6)
        }
    }

    @Test
    fun always503KeepsPacingAndBackoff() {
        val starts = simulate(RouteOutcome.Unavailable, 300_000)
        assertPacing(starts)
        // back-off 5, 10, 20, 30, 30 … s after each failure (plus 200 ms latency)
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(5_200L, 10_200L, 20_200L, 30_200L, 30_200L), gaps.take(5))
    }

    @Test
    fun always400NoRouteWaitsFor200mAnd30s() {
        val still = simulate(RouteOutcome.NoRoute, 300_000)
        assertEquals("stationary: only the first request", 1, still.size)
        val moving = simulate(RouteOutcome.NoRoute, 300_000, move = true)
        assertPacing(moving)
        assertTrue(moving.size > 2)
        moving.zipWithNext { a, b -> assertTrue(b - a >= 30_000) }
    }

    @Test
    fun badRequestUsesTheSameRuleAndSecondaryError() {
        val p = ReroutePolicy()
        assertTrue(p.mayStart(ctx(0)))
        p.onStarted(0, origin)
        p.onOutcome(300, RouteOutcome.BadResponse)
        assertEquals(RerouteSecondary.ERROR, p.secondary)
        assertFalse(p.mayStart(ctx(40_000)))
        assertTrue(p.mayStart(ctx(40_000, Geo.offset(origin, 0.0, 250.0))))
    }

    @Test
    fun retryAfter3GivesNoRequestInTheWindowThenExactlyOne() {
        val p = ReroutePolicy()
        assertTrue(p.mayStart(ctx(0)))
        p.onStarted(0, origin)
        p.onOutcome(5_000, RouteOutcome.RateLimited(3))
        assertNull("AC 47: no secondary line during the wait", p.secondary)
        var t = 5_000L
        while (t < 8_000) {
            assertFalse("request at $t inside Retry-After", p.mayStart(ctx(t)))
            t += 100
        }
        assertTrue(p.mayStart(ctx(8_000)))
        p.onStarted(8_000, origin)
        assertFalse(p.mayStart(ctx(8_100)))
    }

    @Test
    fun retryAfterAlsoRespectsMinimumGap() {
        val p = ReroutePolicy()
        p.mayStart(ctx(0))
        p.onStarted(0, origin)
        p.onOutcome(100, RouteOutcome.RateLimited(1))
        assertFalse(p.mayStart(ctx(1_200)))
        assertTrue(p.mayStart(ctx(5_000)))
    }

    @Test
    fun offlineSendsNothingAndResumesWhenTheNetworkReturns() {
        val p = ReroutePolicy()
        for (t in 0L..30_000L step 500) assertFalse(p.mayStart(ctx(t, online = false)))
        assertEquals(RerouteSecondary.OFFLINE, p.secondary)
        p.onNetworkRestored()
        assertTrue(p.mayStart(ctx(30_500)))
        assertNull(p.secondary)
    }

    @Test
    fun networkReturnClearsBackoff() {
        val p = ReroutePolicy()
        p.mayStart(ctx(0))
        p.onStarted(0, origin)
        p.onOutcome(12_000, RouteOutcome.Unavailable)
        p.mayStart(ctx(12_500))
        p.onStarted(17_000, origin)
        p.onOutcome(29_000, RouteOutcome.Unavailable) // back-off 10 s → 39 s
        assertFalse(p.mayStart(ctx(30_000, online = false)))
        p.onNetworkRestored()
        assertTrue(p.mayStart(ctx(31_000)))
    }

    @Test
    fun gpsLostEndedOrNotInEpisodeSendNothing() {
        val p = ReroutePolicy()
        assertFalse(p.mayStart(ctx(0, gpsOk = false)))
        assertFalse(p.mayStart(ctx(0, finished = true)))
        assertFalse(p.mayStart(ctx(0, inEpisode = false)))
        assertTrue(p.mayStart(ctx(0)))
    }

    @Test
    fun episodeEndResetsBackoffButKeepsRateLimit() {
        val p = ReroutePolicy()
        p.mayStart(ctx(0))
        p.onStarted(0, origin)
        p.onOutcome(500, RouteOutcome.Unavailable)
        p.onEpisodeEnd()
        assertTrue(p.mayStart(ctx(5_000)))
        p.onStarted(5_000, origin)
        p.onOutcome(5_200, RouteOutcome.RateLimited(20))
        p.onEpisodeEnd()
        assertFalse(p.mayStart(ctx(12_000)))
        assertTrue(p.mayStart(ctx(25_200)))
    }

    @Test
    fun okClearsSecondaryAndBackoff() {
        val p = ReroutePolicy()
        p.mayStart(ctx(0))
        p.onStarted(0, origin)
        p.onOutcome(500, RouteOutcome.Unavailable)
        assertEquals(RerouteSecondary.UNAVAILABLE, p.secondary)
        assertTrue(p.mayStart(ctx(5_500)))
        p.onStarted(5_500, origin)
        assertNull("cleared when a new request starts", p.secondary)
    }
}
