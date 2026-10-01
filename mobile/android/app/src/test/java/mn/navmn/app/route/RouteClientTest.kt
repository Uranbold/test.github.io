package mn.navmn.app.route

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
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
import org.junit.Test

/** NAV-005 AC 5, 7, 47–49, 68 against a mock server (ADR-0009 §2 classification table). */
class RouteClientTest {
    private lateinit var server: MockWebServer
    private var online = true
    private val parser = FakeRouteParser()
    private val req = RouteRequest(LatLon(47.9189, 106.9176), LatLon(47.8858, 106.9173), TravelMode.CAR, false, Lang.MN)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client() = RouteClient(
        server.url("/").toString().trimEnd('/'),
        RouteClient.httpClient(OkHttpClient()),
        { online },
        RouteProcessor(parser),
    )

    private fun respond(code: Int, body: String = "", retryAfter: String? = null) {
        val b = MockResponse.Builder().code(code).body(body)
        if (retryAfter != null) b.addHeader("Retry-After", retryAfter)
        server.enqueue(b.build())
    }

    @Test
    fun okParsesAndPostsTheExactBodyWithCoordinatesOnlyInTheBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(Fixtures.route("p1-p3-car-mn.json"))).build())
        val outcome = client().fetch(req, 0)
        assertTrue(outcome.toString(), outcome is RouteOutcome.Ok)
        val ok = outcome as RouteOutcome.Ok
        assertEquals(4, ok.route.plan.steps.size)
        assertEquals(0, ok.route.plan.generation)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/route", recorded.url.encodedPath)
        assertEquals(null, recorded.url.query)
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        val body = recorded.body!!.utf8()
        assertEquals(RouteBody.json(req), body)
        assertEquals(8, Json.parseToJsonElement(body).jsonObject.size)
    }

    @Test
    fun statusClassification() = runBlocking {
        val cases = listOf(
            Triple(400, """{"code":"NoRoute","message":"x"}""", RouteOutcome.NoRoute),
            Triple(400, """{"code":"NoSegment","message":"x"}""", RouteOutcome.OutOfCoverage),
            Triple(400, """{"error_code":171,"error":"x"}""", RouteOutcome.OutOfCoverage),
            Triple(400, """{"code":"DistanceExceeded","message":"x"}""", RouteOutcome.TooFar),
            Triple(400, """{"code":"InvalidValue","message":"x"}""", RouteOutcome.BadRequest),
            Triple(413, "", RouteOutcome.BadRequest),
            Triple(502, "", RouteOutcome.Unavailable),
            Triple(503, "", RouteOutcome.Unavailable),
            Triple(504, "", RouteOutcome.Unavailable),
            Triple(418, "", RouteOutcome.BadRequest),
            Triple(200, "not json", RouteOutcome.BadResponse),
            Triple(200, """{"code":"NoRoute","routes":[]}""", RouteOutcome.BadResponse),
        )
        for ((code, body, expected) in cases) {
            respond(code, body)
            assertEquals("status $code $body", expected, client().fetch(req, 0))
        }
    }

    @Test
    fun retryAfter() = runBlocking {
        respond(429, """{"code":"RateLimited","message":"x"}""", "3")
        assertEquals(RouteOutcome.RateLimited(3), client().fetch(req, 0))
        respond(429, "", null)
        assertEquals(RouteOutcome.RateLimited(5), client().fetch(req, 0))
        respond(429, "", "0")
        assertEquals(RouteOutcome.RateLimited(5), client().fetch(req, 0))
        respond(429, "", "Wed, 21 Oct 2026 07:28:00 GMT")
        assertEquals(RouteOutcome.RateLimited(5), client().fetch(req, 0))
        respond(429, "", "-1")
        assertEquals(RouteOutcome.RateLimited(5), client().fetch(req, 0))
    }

    @Test
    fun offlineSendsNothing() = runBlocking {
        online = false
        assertEquals(RouteOutcome.Offline, client().fetch(req, 0))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun networkErrorIsUnavailable() = runBlocking {
        val c = client()
        server.close()
        assertEquals(RouteOutcome.Unavailable, c.fetch(req, 0))
    }

    @Test
    fun callTimeoutIsTwelveSecondsAndConnectFive() {
        val http = RouteClient.httpClient(OkHttpClient())
        assertEquals(12_000, http.callTimeoutMillis)
        assertEquals(5_000, http.connectTimeoutMillis)
        assertEquals(null, http.cache)
        assertFalse(http.retryOnConnectionFailure)
        assertTrue(http.interceptors.isEmpty() && http.networkInterceptors.isEmpty())
    }
}
