package mn.navmn.app.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-005 AC 5–7, 9–10 preview state rules (NAV-004 rules). */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewControllerTest {
    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)
    private val fix = Fix(p1.lat, p1.lon, 5.0, null, null, null, 0, 0)
    private val ok = RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0)

    private fun TestScope.ctl(online: () -> Boolean = { true }, answer: suspend (RouteRequest) -> RouteOutcome): Pair<PreviewController, MutableList<RouteRequest>> {
        val sent = ArrayList<RouteRequest>()
        val c = PreviewController(this, { r -> sent += r; answer(r) }, { Lang.MN }, online, { testScheduler.currentTime }, { 0L })
        return c to sent
    }

    @Test
    fun oneRequestPerOriginModeAndToggle() = runTest {
        val (c, sent) = ctl { ok }
        c.open(Destination(p3, "Зайсан"))
        assertFalse(c.state.value!!.canStart)
        c.setOrigin(fix)
        runCurrent()
        assertEquals(1, sent.size)
        assertTrue(c.state.value!!.canStart)
        assertEquals(TravelMode.CAR, sent[0].mode)
        assertEquals(null, sent[0].heading)
        c.setAvoidUnpaved(true)
        runCurrent()
        assertEquals(2, sent.size)
        assertTrue(sent[1].avoidUnpaved)
        c.setMode(TravelMode.WALK)
        advanceTimeBy(299)
        assertEquals(2, sent.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, sent.size)
        assertEquals(TravelMode.WALK, sent[2].mode)
        assertFalse("avoid is car only", sent[2].avoidUnpaved)
    }

    @Test
    fun loadingAfter300ms() = runTest {
        val gate = CompletableDeferred<RouteOutcome>()
        val (c, _) = ctl { gate.await() }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.Pending, c.state.value!!.result)
        advanceTimeBy(301)
        assertEquals(PreviewResult.Loading, c.state.value!!.result)
        gate.complete(RouteOutcome.Unavailable)
        runCurrent()
        assertEquals(PreviewResult.Unavailable, c.state.value!!.result)
    }

    @Test
    fun samePointOfflineAndErrorStates() = runTest {
        val (same, sentSame) = ctl { ok }
        same.open(Destination(Geo.offset(p1, 0.0, 8.0), null))
        same.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.SamePoint, same.state.value!!.result)
        assertEquals(0, sentSame.size)

        var online = false
        val (off, sentOff) = ctl({ online }) { ok }
        off.open(Destination(p3, null))
        off.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.Offline, off.state.value!!.result)
        assertEquals(0, sentOff.size)
        online = true
        off.onNetworkRestored()
        runCurrent()
        assertEquals(1, sentOff.size)

        val cases = listOf(
            RouteOutcome.NoRoute to PreviewResult.NoRoute(false),
            RouteOutcome.OutOfCoverage to PreviewResult.OutOfArea,
            RouteOutcome.TooFar to PreviewResult.NoRoute(false),
            RouteOutcome.BadRequest to PreviewResult.Error,
            RouteOutcome.BadResponse to PreviewResult.Error,
        )
        for ((outcome, expected) in cases) {
            val (c, _) = ctl { outcome }
            c.open(Destination(p3, null))
            c.setOrigin(fix)
            runCurrent()
            assertEquals(expected, c.state.value!!.result)
            assertFalse(c.state.value!!.canStart)
        }
        val (walk, _) = ctl { RouteOutcome.TooFar }
        walk.open(Destination(p3, null))
        walk.setMode(TravelMode.WALK)
        walk.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.TooFar, walk.state.value!!.result)
    }

    @Test
    fun rateLimitedBlocksRetryForRetryAfter() = runTest {
        var n = 0
        val (c, sent) = ctl { if (n++ == 0) RouteOutcome.RateLimited(5) else ok }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.RateLimited(false), c.state.value!!.result)
        c.retry()
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(5_001)
        assertEquals(PreviewResult.RateLimited(true), c.state.value!!.result)
        c.retry()
        runCurrent()
        assertEquals(2, sent.size)
        assertTrue(c.state.value!!.canStart)
    }

    @Test
    fun locationProblemKeepsStartDisabledAndSendsNothing() = runTest {
        val (c, sent) = ctl { ok }
        c.open(Destination(p3, null))
        c.locationProblem(LocationProblem.APPROXIMATE)
        runCurrent()
        assertEquals(0, sent.size)
        assertFalse(c.state.value!!.canStart)
    }
}
