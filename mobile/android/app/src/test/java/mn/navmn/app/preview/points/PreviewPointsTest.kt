package mn.navmn.app.preview.points

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.preview.PreviewController
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.route.alternatives.ThreeRoutes
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-018 AC 2, 9, 11–15, 21 on [PreviewController] (ADR-0015 §7): points, swap, ordering, 429, body, start gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPointsTest {
    private val p1 = LatLon(47.9189, 106.9176)
    private val p2 = LatLon(47.9213, 106.9182)
    private val p3 = LatLon(47.8858, 106.9173)
    private val fix = Fix(p1.lat, p1.lon, 5.0, 90.0, 5.0, 10.0, 0, 0)
    private val ok = RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0)
    private val three = PreviewRoutes.process(RouteProcessor(FakeRouteParser()), ThreeRoutes.bytes(), 0)

    private fun TestScope.ctl(online: () -> Boolean = { true }, answer: suspend (RouteRequest) -> RouteOutcome): Pair<PreviewController, MutableList<RouteRequest>> {
        val sent = ArrayList<RouteRequest>()
        val c = PreviewController(this, { r -> sent += r; answer(r) }, { Lang.MN }, online, { testScheduler.currentTime }, { 0L })
        return c to sent
    }

    @Test
    fun swapTwiceRestoresThePointsAndSendsTwoRequests() = runTest {
        val (c, sent) = ctl { ok }
        val dest = Destination(p3, "Зайсан толгой")
        c.open(dest)
        c.setOrigin(fix)
        runCurrent()
        assertEquals(1, sent.size)
        val start = c.state.value!!.origin
        assertTrue(c.swap())
        runCurrent()
        assertEquals("AC 11: exactly one request per swap", 2, sent.size)
        assertEquals(dest, c.state.value!!.origin)
        assertEquals("AC 11: «Миний байршил» keeps its fix (not refreshed)", RoutePoint.MyLocation(fix), c.state.value!!.destination)
        assertEquals(p3, sent[1].origin)
        assertEquals(p1, sent[1].destination)
        assertFalse("AC 15: «Миний байршил» swapped to the destination → chosen start", c.state.value!!.canStart)
        assertTrue(c.state.value!!.showStartHint)
        assertTrue(c.swap())
        runCurrent()
        assertEquals(3, sent.size)
        assertEquals(start, c.state.value!!.origin)
        assertEquals(dest, c.state.value!!.destination)
        assertTrue("AC 15: swap back → «Эхлэх» enabled, O1 gone", c.state.value!!.canStart)
        assertFalse(c.state.value!!.showStartHint)
    }

    @Test
    fun swapIsUnavailableWhileTheStartIsEmpty() = runTest {
        val (c, sent) = ctl { ok }
        c.open(Destination(p3, null))
        c.locationProblem(LocationProblem.DENIED)
        assertTrue("AC 2: empty start field", c.state.value!!.originEmpty)
        assertFalse(c.swap())
        runCurrent()
        assertEquals(0, sent.size)
    }

    @Test
    fun chosenStartAfterALocationProblemSendsOneRequestAndDisablesStart() = runTest {
        val (c, sent) = ctl { ok }
        c.open(Destination(p3, "Зайсан толгой"))
        c.locationProblem(LocationProblem.APPROXIMATE)
        runCurrent()
        assertEquals(0, sent.size)
        c.setOrigin(RoutePoint.Place(p2, "Гандан хийд"))
        assertFalse("AC 2: the location message is removed at once", c.state.value!!.result is PreviewResult.Location)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(p2, sent[0].origin)
        assertTrue(c.state.value!!.result is PreviewResult.Route)
        assertFalse("AC 15", c.state.value!!.canStart)
        assertTrue("AC 15: O1", c.state.value!!.showStartHint)
        // «Миний байршил» again (AC 3, 15): one request, «Эхлэх» enabled.
        c.setOrigin(fix)
        runCurrent()
        assertEquals(2, sent.size)
        assertTrue(c.state.value!!.canStart)
    }

    @Test
    fun myLocationStartAfterMovingTurnsTheSwappedMyLocationDestinationIntoASelectedPoint() = runTest {
        // PO 2026-10-04, answer 5: swap «Миний байршил» to the destination, move > 10 m, pick «Миний байршил» as the start.
        val (c, sent) = ctl { ok }
        c.open(Destination(p3, "Зайсан толгой"))
        c.setOrigin(fix)
        runCurrent()
        assertTrue(c.swap())
        runCurrent()
        assertEquals(RoutePoint.MyLocation(fix), c.state.value!!.destination)
        val moved = Fix(p2.lat, p2.lon, 5.0, 90.0, 5.0, 10.0, 60_000, 0)
        assertTrue("moved more than 10 m", Geo.distance(p1, p2) > PointRules.SAME_POINT_M)
        c.setOrigin(moved)
        runCurrent()
        assertEquals("the old fix as a map point («Сонгосон цэг»)", RoutePoint.MapPoint(p1), c.state.value!!.destination)
        assertEquals(RoutePoint.MyLocation(moved), c.state.value!!.origin)
        assertEquals("one request for the new start", 3, sent.size)
        assertEquals(p2, sent[2].origin)
        assertEquals(p1, sent[2].destination)
        assertTrue("a device start again: «Эхлэх» enabled", c.state.value!!.canStart)
    }

    @Test
    fun samePointAnyKindsSendsNothing() = runTest {
        val (c, sent) = ctl { ok }
        c.open(Destination(p3, null))
        c.setOrigin(RoutePoint.Place(Geo.offset(p3, 10.0, 9.0), "Зайсан"))
        runCurrent()
        assertEquals("AC 9", PreviewResult.SamePoint, c.state.value!!.result)
        c.setDestination(RoutePoint.MapPoint(Geo.offset(p3, 10.0, 4.0)))
        runCurrent()
        assertEquals(PreviewResult.SamePoint, c.state.value!!.result)
        assertEquals(0, sent.size)
        c.setDestination(RoutePoint.MapPoint(p1))
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun anOlderResponseNeverReplacesANewerOneAndOneIsInFlight() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        var n = 0
        val (c, sent) = ctl {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                // AC 12: QA delays the first response by 1 s.
                if (n++ == 0) delay(1_000)
                ok
            } finally {
                inFlight--
            }
        }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        advanceTimeBy(200)
        c.swap()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals("AC 12: at most one request in flight", 1, maxInFlight)
        assertEquals("the swapped request is the one shown", p3, c.state.value!!.origin!!.point)
        assertTrue(c.state.value!!.result is PreviewResult.Route)
    }

    @Test
    fun pointsUpdateDuringA429WaitWithoutRequests() = runTest {
        var n = 0
        val (c, sent) = ctl { if (n++ == 0) RouteOutcome.RateLimited(5) else ok }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        assertEquals(PreviewResult.RateLimited(false), c.state.value!!.result)
        c.swap()
        c.setDestination(RoutePoint.Place(p2, "Гандан хийд"))
        c.setOrigin(RoutePoint.MapPoint(p3))
        runCurrent()
        assertEquals("AC 12: 0 requests during Retry-After", 1, sent.size)
        assertEquals(RoutePoint.Place(p2, "Гандан хийд"), c.state.value!!.destination)
        assertEquals(RoutePoint.MapPoint(p3), c.state.value!!.origin)
        assertEquals(PreviewResult.RateLimited(false), c.state.value!!.result)
        advanceTimeBy(5_001)
        assertEquals(PreviewResult.RateLimited(true), c.state.value!!.result)
        c.retry()
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(p3, sent[1].origin)
        assertEquals(p2, sent[1].destination)
    }

    @Test
    fun bodyHasBothPointsUnroundedAlternates2AndNoHeading() = runTest {
        val (c, sent) = ctl { ok }
        val start = LatLon(47.9212345, 106.9187654)
        c.open(Destination(p3, "Зайсан толгой"))
        c.setOrigin(RoutePoint.MapPoint(start))
        runCurrent()
        val body = RouteBody.json(sent.single())
        assertTrue(body, body.startsWith("{\"locations\":[{\"lat\":47.921235,\"lon\":106.918765},{\"lat\":47.885800,\"lon\":106.917300}]"))
        assertTrue(body.contains("\"alternates\":2"))
        assertFalse("AC 13: no heading in a preview request", body.contains("heading"))
        // A device start whose fix has a bearing and speed still sends no heading.
        c.setOrigin(fix)
        runCurrent()
        assertNull(sent.last().heading)
        assertFalse(RouteBody.json(sent.last()).contains("heading"))
    }

    @Test
    fun selectingAnotherRouteSendsNothing() = runTest {
        val (c, sent) = ctl { three }
        c.open(Destination(p3, null))
        c.setOrigin(RoutePoint.Place(p2, "Гандан хийд"))
        runCurrent()
        val r = c.state.value!!.result as PreviewResult.Route
        c.select(1)
        assertEquals("AC 21: 0 requests", 1, sent.size)
        assertSame(r.routes[1], (c.state.value!!.result as PreviewResult.Route).route)
    }

    @Test
    fun aDelayedFirstResponseWithAPendingSwapIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        var n = 0
        val (c, _) = ctl { if (n++ == 0) { gate.await(); RouteOutcome.Unavailable } else ok }
        c.open(Destination(p3, null))
        c.setOrigin(fix)
        runCurrent()
        c.swap()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue("the older (cancelled) answer never shows", c.state.value!!.result is PreviewResult.Route)
    }
}
