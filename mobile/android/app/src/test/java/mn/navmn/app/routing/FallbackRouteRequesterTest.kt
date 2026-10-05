package mn.navmn.app.routing

import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * NAV-021 AC 5, 8–13 (ADR-0017 §5, D163, D199): the online-first rule with a fake clock, a mock gateway and a fake
 * on-device engine. "0 HTTP requests" is the mock server's request count.
 */
class FallbackRouteRequesterTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val clock = FakeRoutingClock()
    private val engine = FakeEngine { clock.now() }
    private val executor = Executors.newSingleThreadExecutor()
    private var validated = true
    private var packInstalled = true
    private lateinit var policy: OnlineFirstPolicy
    private lateinit var routing: InstalledRouting
    private val logLines = java.util.Collections.synchronizedList(ArrayList<String>())

    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)
    private val reroute = RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = false, lang = Lang.MN)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        policy = OnlineFirstPolicy()
        routing = testRouting(tmp.root)
        engine.answer = { EngineAnswer.Osrm(Fixtures.route("p1-p3-car-mn.json")) }
    }

    @After
    fun tearDown() {
        server.close()
        executor.shutdownNow()
    }

    private fun requester(baseUrl: String = server.url("/").toString().trimEnd('/')): FallbackRouteRequester {
        val processor = RouteProcessor(FakeRouteParser())
        val online = RouteClient(baseUrl, RouteClient.httpClient(OkHttpClient()), { validated }, processor)
        val local = OnDeviceRouteRequester(engine, processor, executor, { if (packInstalled) routing else null })
        return FallbackRouteRequester(online, local, policy, clock, { validated }, { packInstalled }, { logLines += it })
    }

    private fun FallbackRouteRequester.startAsync(req: RouteRequest = reroute): LinkedBlockingQueue<RouteOutcome> {
        val q = LinkedBlockingQueue<RouteOutcome>()
        start(req, 1) { q.put(it) }
        return q
    }

    private fun LinkedBlockingQueue<RouteOutcome>.await(): RouteOutcome = requireNotNull(poll(15, TimeUnit.SECONDS)) { "no outcome" }

    private fun okBody() = MockResponse.Builder().code(200).body(okio.Buffer().write(Fixtures.route("p1-p3-car-mn.json"))).build()

    private fun respond(code: Int, body: String = "", retryAfter: String? = null) {
        val b = MockResponse.Builder().code(code).body(body)
        if (retryAfter != null) b.addHeader("Retry-After", retryAfter)
        server.enqueue(b.build())
    }

    private fun assertOnDeviceOk(o: RouteOutcome) {
        assertTrue(o.toString(), o is RouteOutcome.Ok)
        assertTrue((o as RouteOutcome.Ok).routes.all { it.onDevice })
    }

    // ------------------------------------------------------------------------------------------------ AC 9: 3.0 s

    @Test
    fun headersDelayedTenSecondsStartsTheOnDeviceCallAtExactlyThreeSeconds() {
        server.enqueue(okBody().newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val q = requester().startAsync()
        assertEquals(1, server.takeRequest(5, TimeUnit.SECONDS)?.let { 1 } ?: 0)
        clock.advanceTo(2_999)
        assertTrue("no on-device call before 3.0 s", engine.calls.isEmpty())
        clock.advanceTo(3_000)
        val o = q.await()
        assertOnDeviceOk(o)
        assertEquals(1, engine.calls.size)
        assertEquals(3_000L, engine.calls[0].atMs)
        assertEquals(1, server.requestCount)
        // The late online answer is discarded: nothing else is delivered.
        assertEquals(null, q.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun headersWithinTheBudgetAreHandledAsTodayWithZeroOnDeviceCalls() {
        server.enqueue(okBody())
        val o = requester().startAsync().await()
        assertTrue(o is RouteOutcome.Ok)
        assertFalse((o as RouteOutcome.Ok).route.onDevice)
        clock.advanceTo(10_000)
        assertTrue(engine.calls.isEmpty())
        assertEquals(0, clock.pendingTimers)
    }

    @Test
    fun authoritativeGatewayAnswersAreFinalWithZeroOnDeviceCalls() {
        val cases = listOf(
            """{"code":"NoRoute","message":"x"}""" to RouteOutcome.NoRoute,
            """{"code":"NoSegment","message":"x"}""" to RouteOutcome.OutOfCoverage,
            """{"code":"DistanceExceeded","message":"x"}""" to RouteOutcome.TooFar,
            """{"code":"InvalidValue","message":"x"}""" to RouteOutcome.BadRequest,
        )
        val r = requester()
        for ((body, expected) in cases) {
            respond(400, body)
            assertEquals(body, expected, r.startAsync().await())
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun gatewayUnavailableFallsBackInTheSameAttemptAndIsNotSticky() {
        val r = requester()
        for (code in listOf(502, 503, 504)) {
            respond(code)
            assertOnDeviceOk(r.startAsync().await())
        }
        assertEquals(3, engine.calls.size)
        assertEquals(3, server.requestCount)
        // 5xx is not a timeout/offline: the next request goes online first again.
        server.enqueue(okBody())
        val o = r.startAsync().await()
        assertFalse((o as RouteOutcome.Ok).route.onDevice)
        assertEquals(4, server.requestCount)
    }

    // ------------------------------------------------------------------------------------------------ AC 11 stickiness

    @Test
    fun connectionFailureFallsBackAndSticksForSixtySeconds() {
        val dead = MockWebServer().apply { start() }
        val deadUrl = dead.url("/").toString().trimEnd('/')
        dead.close()
        val r = requester(deadUrl)
        assertOnDeviceOk(r.startAsync().await()) // connection refused before headers → local
        assertEquals(1, engine.calls.size)
        clock.advanceTo(59_999)
        assertOnDeviceOk(r.startAsync().await()) // sticky: straight to the device
        assertEquals(2, engine.calls.size)
        assertEquals(OnlineFirstPolicy.Source.ON_DEVICE, policy.decide(59_999, true))
        assertEquals(OnlineFirstPolicy.Source.ONLINE_FIRST, policy.decide(60_000, true))
    }

    @Test
    fun timeoutSticksAndOnlyANewlyValidatedNetworkEndsItEarly() {
        server.enqueue(okBody().newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val r = requester()
        val q = r.startAsync()
        server.takeRequest(5, TimeUnit.SECONDS)
        clock.advanceTo(3_000)
        assertOnDeviceOk(q.await())
        clock.advanceTo(20_000)
        assertOnDeviceOk(r.startAsync().await())
        assertEquals(1, server.requestCount) // 0 online attempts while sticky
        policy.onNewlyValidated()
        server.enqueue(okBody())
        val o = r.startAsync().await()
        assertFalse((o as RouteOutcome.Ok).route.onDevice)
        assertEquals(2, server.requestCount)
    }

    // ------------------------------------------------------------------------------------------------ AC 12: 429

    @Test
    fun rateLimitedAnswersLocallyDuringRetryAfterThenOnlineFirstResumes() {
        val r = requester()
        respond(429, retryAfter = "7")
        assertOnDeviceOk(r.startAsync().await())
        clock.advanceTo(6_999)
        assertOnDeviceOk(r.startAsync().await())
        assertEquals(1, server.requestCount)
        policy.onNewlyValidated() // does not end a server-requested wait
        assertEquals(OnlineFirstPolicy.Source.ON_DEVICE, policy.decide(6_999, true))
        clock.advanceTo(7_000)
        server.enqueue(okBody())
        val o = r.startAsync().await()
        assertFalse((o as RouteOutcome.Ok).route.onDevice)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitedWithoutRetryAfterWaitsFiveSeconds() {
        val r = requester()
        respond(429)
        assertOnDeviceOk(r.startAsync().await())
        assertEquals(OnlineFirstPolicy.Source.ON_DEVICE, policy.decide(4_999, true))
        assertEquals(OnlineFirstPolicy.Source.ONLINE_FIRST, policy.decide(5_000, true))
    }

    // ------------------------------------------------------------------------------------------------ AC 8, 37

    @Test
    fun noValidatedNetworkAnswersOnTheDeviceAtOnceWithZeroHttpRequests() {
        validated = false
        val r = requester()
        for (purpose in RoutePurpose.entries) assertOnDeviceOk(r.startAsync(reroute.copy(purpose = purpose)).await())
        assertEquals(0, server.requestCount)
        assertEquals(listOf(0L, 0L), engine.calls.map { it.atMs })
    }

    // ------------------------------------------------------------------------------------------------ AC 14: both fail

    @Test
    fun theAttemptFailsOnlyWhenBothSourcesFail() {
        engine.answer = { EngineAnswer.Failed(EngineError.PROCESS_DIED) }
        val r = requester()
        respond(503)
        assertEquals(RouteOutcome.Unavailable, r.startAsync().await())
        respond(429, retryAfter = "9")
        assertEquals(RouteOutcome.RateLimited(9), r.startAsync().await())
        validated = false
        assertEquals(RouteOutcome.Unavailable, r.startAsync().await()) // AC 6: local engine failure → Unavailable
    }

    @Test
    fun localAuthoritativeAnswersPassThrough() {
        engine.answer = { EngineAnswer.Failed(EngineError.NO_SEGMENT) }
        validated = false
        assertEquals(RouteOutcome.OutOfCoverage, requester().startAsync().await())
    }

    // ------------------------------------------------------------------------------------------------ AC 13: no pack

    @Test
    fun withoutAnInstalledRoutingFileBehaviourIsExactlyToday() {
        packInstalled = false
        val r = requester()
        validated = false
        assertEquals(RouteOutcome.Offline, r.startAsync().await())
        assertEquals(0, server.requestCount)
        validated = true
        respond(503)
        assertEquals(RouteOutcome.Unavailable, r.startAsync().await())
        server.enqueue(okBody().newBuilder().headersDelay(4, TimeUnit.SECONDS).build())
        val q = r.startAsync()
        clock.advanceTo(10_000) // no budget timer exists without a pack
        assertTrue(q.await() is RouteOutcome.Ok)
        assertTrue(engine.calls.isEmpty())
        assertEquals(0, clock.pendingTimers)
    }

    // ------------------------------------------------------------------------------------------------ cancellation

    @Test
    fun cancelDeliversCancelledOnceAndStartsNoOnDeviceCall() {
        server.enqueue(okBody().newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val q = LinkedBlockingQueue<RouteOutcome>()
        val c = requester().start(reroute, 1) { q.put(it) }
        server.takeRequest(5, TimeUnit.SECONDS)
        c.cancel()
        assertEquals(RouteOutcome.Cancelled, q.await())
        clock.advanceTo(5_000)
        assertTrue(engine.calls.isEmpty())
        assertEquals(null, q.poll(300, TimeUnit.MILLISECONDS))
    }

    // ------------------------------------------------------------------------------------------------ AC 5 parity

    /**
     * AC 5: the on-device engine receives byte-for-byte the body the gateway got in the same attempt, for the NAV-005
     * AC 5 bodies (car with and without «Шороон замаас зайлсхийх», walk, English), the NAV-005 AC 43 reroute (heading)
     * and the NAV-011 preview (`alternates: 2`).
     */
    @Test
    fun theOnDeviceEngineGetsTheExactGatewayBody() {
        val cases = listOf(
            RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = false, lang = Lang.MN),
            RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = true, lang = Lang.MN),
            RouteRequest(p1, p3, TravelMode.WALK, avoidUnpaved = false, lang = Lang.EN),
            RouteRequest(p1, p3, TravelMode.BICYCLE, avoidUnpaved = false, lang = Lang.MN),
            RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = true, lang = Lang.MN, heading = 165),
            RouteRequest(p1, p3, TravelMode.CAR, avoidUnpaved = false, lang = Lang.MN, purpose = RoutePurpose.PREVIEW),
        )
        val r = requester()
        for ((i, req) in cases.withIndex()) {
            respond(503)
            r.startAsync(req).await()
            val gateway = server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()
            assertEquals("case $i", gateway, engine.calls[i].json)
        }
        assertTrue(engine.calls[4].json.contains("\"heading\":165"))
        assertTrue(engine.calls[5].json.contains("\"alternates\":2"))
        assertTrue(engine.calls[1].json.contains("\"costing_options\":{\"auto\":{\"exclude_unpaved\":true}}"))
    }

    // ------------------------------------------------------------------------------------------------ AC 36

    @Test
    fun debugLogLinesNeverContainCoordinatesOrBodies() {
        val r = requester()
        respond(503)
        r.startAsync().await()
        respond(429, retryAfter = "2")
        r.startAsync().await()
        validated = false
        r.startAsync().await()
        assertTrue(logLines.isNotEmpty())
        val coord = Regex("-?\\d{1,3}\\.\\d{4,}")
        for (l in logLines) {
            assertFalse(l, coord.containsMatchIn(l))
            assertFalse(l, l.contains("locations") || l.contains("costing"))
        }
    }
}
