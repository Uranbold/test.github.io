package mn.navmn.app.routing

import com.squareup.moshi.JsonDataException
import com.valhalla.valhalla.ErrorResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.routing.ipc.RoutingWire
import mn.navmn.app.routing.service.EngineFailure
import mn.navmn.app.routing.service.EngineHost
import mn.navmn.app.routing.service.OnDeviceConfig
import mn.navmn.app.routing.service.RawEngine
import mn.navmn.app.routing.service.RawEngineFactory
import mn.navmn.app.routing.service.ValhallaEngine
import mn.navmn.app.routing.service.ValhallaErrors
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** NAV-021 AC 3, 6, 17, 23–25, 29, R3, R11: the pure parts of on-device routing (no device, no native engine). */
class OnDeviceUnitsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val processor = RouteProcessor(FakeRouteParser())
    private val req = RouteRequest(LatLon(47.9189, 106.9176), LatLon(47.8858, 106.9173), TravelMode.CAR, false, Lang.MN)

    // ------------------------------------------------------------------------------------------------ AC 6

    @Test
    fun classificationTable() {
        fun c(a: EngineAnswer, purpose: RoutePurpose = RoutePurpose.REROUTE) = OnDeviceClassifier.classify(a, processor, 1, purpose)
        val ok = c(EngineAnswer.Osrm(Fixtures.route("p1-p3-car-mn.json")))
        assertTrue(ok is RouteOutcome.Ok && ok.route.onDevice && ok.route.plan.generation == 1)
        assertEquals(RouteOutcome.NoRoute, c(EngineAnswer.Failed(EngineError.NO_ROUTE)))
        assertEquals(RouteOutcome.OutOfCoverage, c(EngineAnswer.Failed(EngineError.NO_SEGMENT)))
        assertEquals(RouteOutcome.TooFar, c(EngineAnswer.Failed(EngineError.DISTANCE_EXCEEDED)))
        for (e in listOf(EngineError.ENGINE_ERROR, EngineError.TIMEOUT, EngineError.PROCESS_DIED, EngineError.UNAVAILABLE)) {
            assertEquals(e.name, RouteOutcome.Unavailable, c(EngineAnswer.Failed(e)))
        }
        assertEquals(RouteOutcome.Cancelled, c(EngineAnswer.Failed(EngineError.CANCELLED)))
        // OSRM-shaped error bodies (the server's shape) classify the same way.
        assertEquals(RouteOutcome.NoRoute, c(EngineAnswer.Osrm("""{"code":"NoRoute","message":"x"}""".encodeToByteArray())))
        assertEquals(RouteOutcome.OutOfCoverage, c(EngineAnswer.Osrm("""{"code":"NoSegment","message":"x"}""".encodeToByteArray())))
        assertEquals(RouteOutcome.TooFar, c(EngineAnswer.Osrm("""{"code":"DistanceExceeded","message":"x"}""".encodeToByteArray())))
        assertEquals(RouteOutcome.Unavailable, c(EngineAnswer.Osrm("""{"code":"InvalidValue"}""".encodeToByteArray())))
        assertEquals(RouteOutcome.Unavailable, c(EngineAnswer.Osrm("not json".encodeToByteArray())))
        assertEquals(RouteOutcome.Unavailable, c(EngineAnswer.Osrm("""{"code":"Ok","routes":[]}""".encodeToByteArray())))
    }

    @Test
    fun previewOnDeviceKeepsEveryAlternativeMarked() {
        val o = OnDeviceClassifier.classify(EngineAnswer.Osrm(Fixtures.route("p1-p3-car-mn.json")), processor, 0, RoutePurpose.PREVIEW)
        assertTrue(o is RouteOutcome.Ok)
        assertTrue((o as RouteOutcome.Ok).routes.all { it.onDevice })
    }

    @Test
    fun valhallaErrorCodesFollowTheServerTable() {
        assertEquals(EngineError.NO_ROUTE, ValhallaErrors.kindOf(442))
        assertEquals(EngineError.NO_ROUTE, ValhallaErrors.kindOf(170))
        for (code in listOf(171, 443, 444)) assertEquals(EngineError.NO_SEGMENT, ValhallaErrors.kindOf(code))
        assertEquals(EngineError.DISTANCE_EXCEEDED, ValhallaErrors.kindOf(154))
        for (code in listOf(-1, 100, 155, 172, 503)) assertEquals(EngineError.ENGINE_ERROR, ValhallaErrors.kindOf(code))
        // The shipped library's exception message is ErrorResponse.toString().
        assertEquals(171, ValhallaErrors.codeFromMessage(ErrorResponse(171, "No suitable edges near location").toString()))
        assertEquals(-1, ValhallaErrors.codeFromMessage(ErrorResponse(-1, "unknown exception").toString()))
        assertNull(ValhallaErrors.codeFromMessage("something else"))
    }

    /** The library wraps the adapter with failOnUnknown(): only the exact envelope parses; every OSRM body fails. */
    @Test
    fun errorEnvelopeAdapterIdentifiesOnlyTheEnvelope() {
        val adapter = ValhallaEngine.MOSHI.adapter(ErrorResponse::class.java).failOnUnknown()
        assertEquals(ErrorResponse(442, "No path could be found for input"), adapter.fromJson("""{"code":442,"message":"No path could be found for input"}"""))
        val osrm = Fixtures.route("p1-p3-car-mn.json").decodeToString()
        assertTrue(runCatching { adapter.fromJson(osrm) }.exceptionOrNull() is JsonDataException)
        assertTrue(runCatching { adapter.fromJson("""{"code":"NoRoute","message":"x"}""") }.exceptionOrNull() is JsonDataException)
        assertTrue(runCatching { adapter.fromJson("""{"code":1}""") }.exceptionOrNull() is JsonDataException)
    }

    // ------------------------------------------------------------------------------------------------ AC 25: pipe

    @Test
    fun aLongResponseCrossesThePipeIntactWithItsSha256() {
        // > 1 MB, like Choibalsan → Ölgii with banners and voice (AC 25): never a binder transaction, so no size limit.
        val body = ByteArray(3_500_000) { (it * 31 + 7).toByte() }
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        val writer = thread { output.use { RoutingWire.write(it, EngineAnswer.Osrm(body)) } }
        val got = RoutingWire.read(input)
        writer.join()
        assertTrue(got is EngineAnswer.Osrm)
        assertArrayEquals(sha(body), sha((got as EngineAnswer.Osrm).bytes))
    }

    @Test
    fun aTruncatedFrameMeansTheRoutingProcessDied() {
        val full = java.io.ByteArrayOutputStream().also { RoutingWire.write(it, EngineAnswer.Osrm(ByteArray(1000))) }.toByteArray()
        for (cut in listOf(0, 3, 8, 500)) {
            assertEquals(EngineAnswer.Failed(EngineError.PROCESS_DIED), RoutingWire.read(full.copyOf(cut).inputStream()))
        }
        val err = java.io.ByteArrayOutputStream().also { RoutingWire.write(it, EngineAnswer.Failed(EngineError.NO_SEGMENT)) }.toByteArray()
        assertEquals(EngineAnswer.Failed(EngineError.NO_SEGMENT), RoutingWire.read(err.inputStream()))
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    // ------------------------------------------------------------------------------------------------ AC 22–24

    @Test
    fun threeDeathsInTenMinutesDisableUntilANewRoutingFile() {
        val h = RoutingHealth()
        h.onDeath(0, "v1")
        h.onDeath(300_000, "v1")
        assertTrue(h.enabled("v1")) // 2 deaths: the next request rebinds
        h.onDeath(599_999, "v1")
        assertFalse(h.enabled("v1")) // AC 24: 3 within 10 min
        assertTrue("a new routing file install re-enables", h.enabled("v2"))
        assertTrue(h.enabled("v2"))
        assertEquals(3, h.deathCount)
    }

    @Test
    fun deathsSpreadOverMoreThanTenMinutesNeverDisable() {
        val h = RoutingHealth()
        for (i in 0 until 10) h.onDeath(i * 301_000L, "v1")
        assertTrue(h.enabled("v1"))
    }

    @Test
    fun timeoutAndProcessDeathAreUnavailableAndTheNextRequestIsAnsweredAgain() {
        val engine = FakeEngine()
        val routing = testRouting(tmp.root)
        val ex = Executors.newSingleThreadExecutor()
        try {
            val r = OnDeviceRouteRequester(engine, processor, ex, { routing })
            fun once(): RouteOutcome {
                val q = LinkedBlockingQueue<RouteOutcome>()
                r.start(req, 1) { q.put(it) }
                return q.poll(5, TimeUnit.SECONDS)!!
            }
            engine.answer = { EngineAnswer.Failed(EngineError.TIMEOUT) }
            assertEquals(RouteOutcome.Unavailable, once()) // AC 23
            engine.answer = { EngineAnswer.Failed(EngineError.PROCESS_DIED) }
            assertEquals(RouteOutcome.Unavailable, once()) // AC 22: in-flight request
            engine.answer = { EngineAnswer.Osrm(Fixtures.route("p1-p3-car-mn.json")) }
            assertTrue(once() is RouteOutcome.Ok) // the next request is answered on the device
            assertEquals(OnDeviceRouteRequester.ENGINE_TIMEOUT_MS, 10_000L)
        } finally {
            ex.shutdownNow()
        }
    }

    @Test
    fun noRoutingFileIsUnavailableWithoutTouchingTheEngine() {
        val engine = FakeEngine()
        val q = LinkedBlockingQueue<RouteOutcome>()
        OnDeviceRouteRequester(engine, processor, Executors.newSingleThreadExecutor(), { null }).start(req, 1) { q.put(it) }
        assertEquals(RouteOutcome.Unavailable, q.poll(5, TimeUnit.SECONDS))
        assertTrue(engine.calls.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ R3 config

    /** ADR-0017 A1 item 5: the pinned AAR's default.json plus exactly the listed overrides (backend Gate 2 asserts the same). */
    @Test
    fun configIsTheAarDefaultWithExactlyTheA1Overrides() {
        val default = OnDeviceConfig.defaultJson() // read from the shipped valhalla-mobile 0.6.3 AAR on the classpath
        val built = Json.parseToJsonElement(OnDeviceConfig.build(default, "/data/x/packs/v/routing.tar")).jsonObject
        val orig = Json.parseToJsonElement(default).jsonObject
        val m = built.getValue("mjolnir").jsonObject
        assertEquals("/data/x/packs/v/routing.tar", m.getValue("tile_extract").jsonPrimitive.content)
        assertEquals(33_554_432L, m.getValue("max_cache_size").jsonPrimitive.long)
        for (k in listOf("tile_dir", "traffic_extract", "admin", "timezone", "landmarks")) assertEquals(k, "", m.getValue(k).jsonPrimitive.content)
        val om = orig.getValue("mjolnir").jsonObject
        val changed = (om.keys + m.keys).filter { om[it] != m[it] }.toSet()
        assertEquals(setOf("tile_extract", "max_cache_size", "tile_dir", "traffic_extract", "admin", "timezone", "landmarks"), changed)
        for (k in orig.keys - "mjolnir") assertEquals(k, orig[k], built[k]) // service_limits and the rest untouched
        assertEquals(orig.keys, built.keys)
    }

    // ------------------------------------------------------------------------------------------------ R3 engine host

    private class Raw(val path: String, val reply: (String) -> String) : RawEngine {
        var closed = false
        override fun routeRaw(requestJson: String) = reply(requestJson)
        override fun close() {
            closed = true
        }
    }

    @Test
    fun oneEnginePerRoutingFileVersionAndErrorsMapToKinds() {
        val opened = ArrayList<Raw>()
        var reply: (String) -> String = { """{"code":"Ok"}""" }
        val host = EngineHost(tmp.newFolder("cfg"), RawEngineFactory { p -> Raw(p) { reply(it) }.also { opened += it } }, { OnDeviceConfig.defaultJson() })
        val v1 = testRouting(tmp.root, "20261004T193412Z")
        val v2 = testRouting(tmp.root, "20261011T193412Z")
        assertTrue(host.route(v1.tar.path, v1.version, "{}") is EngineAnswer.Osrm)
        assertTrue(host.route(v1.tar.path, v1.version, "{}") is EngineAnswer.Osrm)
        assertEquals(1, host.opened)
        assertTrue(File(opened[0].path).readText().contains(v1.tar.path.replace("/", "\\/")) || File(opened[0].path).readText().contains(v1.tar.path))
        host.route(v2.tar.path, v2.version, "{}")
        assertEquals(2, host.opened)
        assertTrue("the old version's engine is closed", opened[0].closed)
        reply = { throw EngineFailure(171) }
        assertEquals(EngineAnswer.Failed(EngineError.NO_SEGMENT), host.route(v2.tar.path, v2.version, "{}"))
        reply = { throw IllegalStateException("boom") }
        assertEquals(EngineAnswer.Failed(EngineError.ENGINE_ERROR), host.route(v2.tar.path, v2.version, "{}"))
        assertEquals(EngineAnswer.Failed(EngineError.ENGINE_ERROR), host.route(File(tmp.root, "missing.tar").path, "20261018T000000Z", "{}"))
        // NAV-022 self-test: a fresh engine, closed afterwards; the cached engine is not replaced.
        reply = { """{"code":"Ok"}""" }
        val before = opened.size
        assertTrue(host.selfTest(v1.tar.path, v1.version, "{}") is EngineAnswer.Osrm)
        assertTrue(opened[before].closed)
    }

    // ------------------------------------------------------------------------------------------------ R4, AC 29

    private fun active(packs: File, routing: String?) {
        packs.mkdirs()
        File(packs, "active.json").writeText(
            """{"schema":1,"region":"mn","manifest_etag":"\"x\"","files":{"tiles":{"version":"20260927T193105Z","path":"20260927T193105Z/basemap.pmtiles"}${routing?.let { ",\"routing\":$it" } ?: ""}}}""",
        )
    }

    @Test
    fun packFilesReadTheNav022Format() {
        val packs = tmp.newFolder("packs")
        val pf = PackFiles(packs)
        assertNull("no active.json: inert", pf.current())
        val tar = File(packs, "20261004T193412Z/routing.tar").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4)) }
        active(packs, """{"version":"20261004T193412Z","path":"20261004T193412Z/routing.tar","bytes":4,"sha256":"","format":{"graph_builder":"valhalla 3.9.0"}}""")
        val r = pf.current()!!
        assertEquals("20261004T193412Z", r.version)
        assertEquals(tar.canonicalFile, r.tar)
        tar.delete()
        assertNull("the tar disappeared", pf.current())
    }

    @Test
    fun packFilesRefuseUnknownGraphBuildersAndUnsafePathsWithALogLineWithoutCoordinates() {
        val packs = tmp.newFolder("packs")
        File(packs, "20261004T193412Z/routing.tar").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4)) }
        val log = ArrayList<String>()
        val pf = PackFiles(packs, { log += it })
        fun parse(builder: String?, path: String = "20261004T193412Z/routing.tar", version: String = "20261004T193412Z") = pf.parse(
            """{"schema":1,"files":{"routing":{"version":"$version","path":"$path"${builder?.let { ",\"format\":{\"graph_builder\":\"$it\"}" } ?: ""}}}}""",
        )
        assertTrue(parse("valhalla 3.9.0") != null)
        assertNull(parse("valhalla 3.10.0"))
        assertNull(parse(null))
        assertEquals(listOf("routing file refused: graph_builder not on the allow-list", "routing file refused: graph_builder not on the allow-list"), log)
        assertNull(parse("valhalla 3.9.0", path = "../outside.tar"))
        assertNull(parse("valhalla 3.9.0", path = "/etc/passwd"))
        assertNull(parse("valhalla 3.9.0", version = "latest"))
        assertNull(pf.parse("""{"schema":2,"files":{}}"""))
        assertEquals(setOf("valhalla 3.9.0"), GraphBuilderAllowList.VALUES)
        assertTrue(log.none { Regex("-?\\d{1,3}\\.\\d{4,}").containsMatchIn(it) })
    }

    // ------------------------------------------------------------------------------------------------ AC 4 hook

    @Test
    fun theRoutingProcessIsRecognisedByItsName() {
        assertTrue(ProcessRole.isRoutingProcessName("mn.navmn.app:routing"))
        assertTrue(ProcessRole.isRoutingProcessName("mn.navmn.app.debug:routing"))
        assertFalse(ProcessRole.isRoutingProcessName("mn.navmn.app.debug"))
        assertFalse(ProcessRole.isRoutingProcessName(null))
    }
}
