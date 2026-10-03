package mn.navmn.app.background.restore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.engine.GuidanceEvent
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.Geo
import mn.navmn.app.i18n.Lang
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.PreviewController
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.route.alternatives.ThreeRoutes
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * NAV-012 AC 16, 18–20 with NAV-011 AC 19 (ADR-0012 §5.2, ADR-0013 §3.1/§3.4), with the REAL Ferrostar core: guidance
 * started on alternative 2 of a 3-route preview stores exactly that alternative's single-route slice as `route.bin`,
 * and a restore in a new process follows that alternative with 0 route requests.
 *
 * Path: [PreviewController] (preview response with 3 routes, «Маршрут сонгох» → [PreviewController.select]) →
 * `PreviewResult.Route.route` (what «Эхлэх» hands to `GuidanceSession.start`) → [RestoreManager.onGuidanceStarted]
 * (the call `GuidanceSession.start` makes) → new-process [RestoreManager.decide] / [RestoreManager.load] → restored
 * [mn.navmn.app.engine.GuidanceCore] fed from the stored `route.bin` through the same pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSelectedAlternativeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun need() = HostFerrostar.require()

    private val t0 = 1_790_000_000_000L
    private val processor = RouteProcessor(FerrostarRouteParser())
    private val body = ThreeRoutes.bytes()
    private val root = Json.parseToJsonElement(body.decodeToString()).jsonObject

    @Test
    fun guidanceOnAlternative2Of3StoresItsSliceAndRestoresOnItWith0Requests() {
        val alt = 1 // alternative 2 of 3 (0-based position in the parsed routes list)

        // Preview: one request, 3 routes, route 1 selected; selecting alternative 2 sends nothing (NAV-011 AC 17).
        val sent = ArrayList<RouteRequest>()
        val previewScope = TestScope(StandardTestDispatcher())
        val preview = PreviewController(previewScope, { r -> sent += r; PreviewRoutes.process(processor, body, 0) }, { Lang.MN }, { true }, { previewScope.testScheduler.currentTime }, { t0 })
        val first = (PreviewRoutes.process(processor, body, 0) as mn.navmn.app.route.RouteOutcome.Ok).routes
        val origin = first[0].plan.geometry.first()
        preview.open(Destination(first[alt].plan.end, "Зайсан"))
        preview.setOrigin(Tracks.fix(origin, 0, null, 0.0))
        previewScope.advanceUntilIdle()
        val listed = preview.state.value!!.result as PreviewResult.Route
        assertEquals(3, listed.k)
        assertEquals(0, listed.selected)
        preview.select(alt)
        previewScope.advanceUntilIdle()
        val chosen = preview.state.value!!.result as PreviewResult.Route
        assertEquals(alt, chosen.selected)
        assertEquals("1 preview request, none for the selection", 1, sent.size)
        val selected = chosen.route
        assertEquals(first[alt].plan.geometry, selected.plan.geometry)
        assertNotEquals("alternative 2 is not route 1", first[0].plan.geometry, selected.plan.geometry)

        // «Эхлэх»: the record is written from the selected route (GuidanceSession.start → onGuidanceStarted).
        val dir = File(tmp.root, RestoreStore.DIR)
        val server = MockWebServer()
        server.start()
        try {
            val client = RouteClient(server.url("/").toString(), OkHttpClient(), { true }, processor)
            val io = TestScope(StandardTestDispatcher())
            val trip = Trip(selected.plan.end, "Зайсан", TravelMode.CAR, avoidUnpaved = false)
            RestoreManager(RestoreStore(dir), client, { t0 }, CoroutineScope(io.coroutineContext)).onGuidanceStarted(trip, selected, Lang.MN)
            io.runCurrent()
            val stored = File(dir, RestoreStore.ROUTE).readBytes()
            val expectedSlice = PreviewRoutes.slice(root, alt)
            assertArrayEquals("route.bin is alternative 2's single-route slice (ADR-0012 §5.2, ADR-0013 §3.1)", expectedSlice, stored)
            for (other in listOf(0, 2)) {
                assertFalse("route.bin is not route ${other + 1}'s slice", PreviewRoutes.slice(root, other).contentEquals(stored))
            }
            assertFalse("route.bin is not the whole 3-route response", body.contentEquals(stored))
            val storedRoutes = Json.parseToJsonElement(stored.decodeToString()).jsonObject.getValue("routes")
            assertEquals(1, (storedRoutes as kotlinx.serialization.json.JsonArray).size)

            // Process death, new process 5 min later: the decision restores; load parses the stored alternative.
            val fresh = RestoreManager(RestoreStore(dir), client, { t0 + 5 * 60_000L }, CoroutineScope(io.coroutineContext))
            val decision = fresh.decide(sessionAlive = false, locationOk = true)
            assertTrue("decision $decision", decision is RestoreRules.Decision.Restore)
            val loaded = fresh.load((decision as RestoreRules.Decision.Restore).meta)
            assertNotNull(loaded)
            loaded!!
            assertEquals("the restored route is alternative 2", selected.plan.geometry, loaded.route.plan.geometry)
            assertEquals(selected.plan.steps.size, loaded.route.plan.steps.size)
            assertEquals(selected.plan.steps.size, loaded.route.native.stepCount)
            assertEquals(trip, loaded.trip)
            assertArrayEquals(expectedSlice, loaded.route.source)
            assertEquals("0 HTTP requests to restore", 0, server.requestCount)

            // Restored guidance on the stored route.bin (same pipeline as load): drive alternative 2 from 30 % to the
            // end. It must stay on the route (no off-route episode), send 0 route requests and arrive once.
            val r = Replay(stored, TravelMode.CAR, Lang.MN)
            assertEquals(loaded.route.plan.geometry, r.initial.plan.geometry)
            val line = r.initial.plan.geometry
            val length = Geo.length(line)
            val fixes = Tracks.along(line, 10.0, t0 = 1_000, fromM = length * 0.3)
            r.run(fixes, tailMs = 10_000, restoredAt = 0)
            val problems = ArrayList<String>()
            if (r.log.none { it.startsWith("restore start step") }) problems += "no restore start step: ${r.log.filter { it.startsWith("restore") }}"
            if (r.log.any { it.startsWith("restore off the stored route") }) problems += "restore treated alternative 2 as off-route"
            if (r.states.any { it.phase == GuidancePhase.OFF_ROUTE }) problems += "off-route episode while driving alternative 2"
            if (r.requests.isNotEmpty()) problems += "${r.requests.size} route requests after the restore"
            val arrivals = r.events.count { it.second == GuidanceEvent.Arrived }
            if (arrivals != 1) problems += "$arrivals arrivals (expected 1 at the end of alternative 2)"
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
            assertEquals("0 HTTP requests in total", 0, server.requestCount)
        } finally {
            server.close()
        }
    }
}
