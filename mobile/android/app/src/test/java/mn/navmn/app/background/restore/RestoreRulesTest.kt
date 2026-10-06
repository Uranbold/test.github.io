package mn.navmn.app.background.restore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * NAV-012 AC 16, 17, 21, 22, 24 (ADR-0013 §2, §3): the restore record format, the store, the pure decision, the
 * restart table for every API row, and the write/heartbeat/delete policy with a fake clock and a temp directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_790_000_000_000L
    private val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
    private val trip = Trip(LatLon(47.8858, 106.9173), "Зайсан толгой", TravelMode.CAR, avoidUnpaved = true)

    private fun meta(heartbeat: Long = t0, restores: List<Long> = emptyList()) = RestoreMeta(
        schema = RestoreMeta.SCHEMA,
        destination = RestoreDestination(47.88581, 106.91731, "Зайсан толгой"),
        costing = "auto",
        avoidUnpaved = true,
        language = "mn",
        startedAtWallMs = t0 - 60_000,
        heartbeatWallMs = heartbeat,
        routeSha256 = "00",
        restoresWallMs = restores,
    )

    // ------------------------------------------------------------------------------------------- decision (§3.5)

    @Test
    fun decisionRowsInOrder() {
        val present = StoredRecord.Present(meta())
        assertEquals(RestoreRules.Decision.None, RestoreRules.decide(StoredRecord.Absent, t0, sessionAlive = false, locationOk = true))
        assertEquals(RestoreRules.Decision.None, RestoreRules.decide(present, t0, sessionAlive = true, locationOk = true))
        assertEquals(RestoreRules.Decision.DeleteSilently(RestoreRules.Reason.UNREADABLE), RestoreRules.decide(StoredRecord.Unreadable, t0, false, true))
        // AC 21: older than 30 min → deleted; exactly 30 min still restores.
        assertTrue(RestoreRules.decide(present, t0 + 30 * 60_000L, false, true) is RestoreRules.Decision.Restore)
        assertEquals(RestoreRules.Decision.DeleteSilently(RestoreRules.Reason.EXPIRED), RestoreRules.decide(present, t0 + 30 * 60_000L + 1, false, true))
        // Clock moved back: a heartbeat > 5 min in the future counts as expired.
        assertEquals(RestoreRules.Decision.DeleteSilently(RestoreRules.Reason.CLOCK_IN_FUTURE), RestoreRules.decide(present, t0 - 5 * 60_000L - 1, false, true))
        assertTrue(RestoreRules.decide(present, t0 - 4 * 60_000L, false, true) is RestoreRules.Decision.Restore)
        // AC 23: location missing → the record is kept.
        assertTrue(RestoreRules.decide(present, t0 + 1_000, false, locationOk = false) is RestoreRules.Decision.NeedLocation)
    }

    @Test
    fun loopLimitTwoRestoresInTenMinutes() {
        val now = t0 + 60_000
        val once = StoredRecord.Present(meta(restores = listOf(now - 5 * 60_000L)))
        val twice = StoredRecord.Present(meta(restores = listOf(now - 9 * 60_000L, now - 60_000L)))
        val oldTwice = StoredRecord.Present(meta(restores = listOf(now - 11 * 60_000L, now - 60_000L)))
        assertTrue(RestoreRules.decide(once, now, false, true) is RestoreRules.Decision.Restore)
        assertEquals(RestoreRules.Decision.DeleteSilently(RestoreRules.Reason.LOOP_LIMIT), RestoreRules.decide(twice, now, false, true))
        assertTrue(RestoreRules.decide(oldTwice, now, false, true) is RestoreRules.Decision.Restore)
        val after = RestoreRules.afterRestore(meta(restores = listOf(now - 11 * 60_000L, now - 60_000L)), now)
        assertEquals(listOf(now - 60_000L, now), after.restoresWallMs)
        assertEquals(now, after.heartbeatWallMs)
        assertEquals(t0 + 30 * 60_000L, RestoreRules.windowEnd(meta()))
    }

    // ------------------------------------------------------------------------------------------- restart table (§2)

    @Test
    fun restartTableEveryRow() {
        val restore = RestoreRules.Decision.Restore(meta())
        val need = RestoreRules.Decision.NeedLocation(meta())
        val delete = RestoreRules.Decision.DeleteSilently(RestoreRules.Reason.EXPIRED)
        for (sdk in 26..36) {
            for (notifications in listOf(true, false)) {
                assertEquals(RestartPlan.Action.STOP, RestartPlan.action(sdk, RestoreRules.Decision.None, notifications))
                assertEquals(RestartPlan.Action.DELETE_AND_STOP, RestartPlan.action(sdk, delete, notifications))
                val expected = when {
                    sdk <= 29 -> RestartPlan.Action.RESUME_SILENTLY
                    notifications -> RestartPlan.Action.POST_INTERRUPTED
                    else -> RestartPlan.Action.KEEP_AND_STOP
                }
                assertEquals("sdk $sdk notifications $notifications", expected, RestartPlan.action(sdk, restore, notifications))
                assertEquals(
                    if (notifications) RestartPlan.Action.POST_INTERRUPTED else RestartPlan.Action.KEEP_AND_STOP,
                    RestartPlan.action(sdk, need, notifications),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------- store (§3.1)

    @Test
    fun storeRoundTripHashCheckAndUnreadable() {
        val dir = File(tmp.root, "restore")
        val store = RestoreStore(dir)
        assertEquals(StoredRecord.Absent, store.read())
        val bytes = route.source!!
        val m = meta().copy(routeSha256 = RestoreCodec.sha256(bytes))
        store.writeAll(m, bytes)
        assertEquals(StoredRecord.Present(m), store.read())
        assertArrayEquals(bytes, store.readRoute(m))
        assertEquals(setOf("meta.json", "route.bin"), dir.list()!!.toSet()) // no temp file left
        // Hash mismatch (torn or replaced route) → no route.
        assertNull(store.readRoute(m.copy(routeSha256 = "ff")))
        // Unknown schema (app update) and garbage → unreadable, never a crash (AC 24).
        File(dir, "meta.json").writeText(String(RestoreCodec.encode(m)).replace("\"schema\":1", "\"schema\":2"))
        assertEquals(StoredRecord.Unreadable, store.read())
        File(dir, "meta.json").writeText("{not json")
        assertEquals(StoredRecord.Unreadable, store.read())
        File(dir, "meta.json").delete()
        assertEquals(StoredRecord.Unreadable, store.read()) // route.bin without metadata
        store.delete()
        assertFalse(dir.exists())
        // A heartbeat after the delete never resurrects the record.
        store.writeMeta(m)
        assertFalse(dir.exists())
    }

    @Test
    fun recordHoldsOnlyTheAc16Fields() {
        val json = String(RestoreCodec.encode(meta()))
        val keys = Regex("\"([A-Za-z0-9]+)\":").findAll(json).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("schema", "destination", "lat", "lon", "text", "costing", "avoidUnpaved", "language", "startedAtWallMs", "heartbeatWallMs", "routeSha256", "restoresWallMs", "routeSource"),
            keys,
        )
    }

    // ------------------------------------------------------------------------------------------- manager (§3.2)

    private class Harness(dir: File, start: Long) {
        var now = start
        val scope = TestScope(StandardTestDispatcher())
        val store = RestoreStore(dir)
        val manager = RestoreManager(store, null, { now }, CoroutineScope(scope.coroutineContext))
    }

    @Test
    fun writeAtStartHeartbeatRewriteOnNewRouteDeleteOnEnd() {
        val dir = File(tmp.root, "restore")
        val h = Harness(dir, t0)
        h.manager.onGuidanceStarted(trip, route, Lang.MN)
        h.scope.runCurrent()
        val written = (h.store.read() as StoredRecord.Present).meta
        assertEquals(47.8858, written.destination.lat, 0.0)
        assertEquals("Зайсан толгой", written.destination.text)
        assertEquals("auto", written.costing)
        assertTrue(written.avoidUnpaved)
        assertEquals("mn", written.language)
        assertEquals(t0, written.startedAtWallMs)
        assertArrayEquals(route.source, h.store.readRoute(written))
        // Heartbeat every 30 s (AC 16: ≤ 60 s), metadata only.
        h.now = t0 + 30_000
        h.scope.advanceTimeBy(30_001)
        assertEquals(t0 + 30_000, (h.store.read() as StoredRecord.Present).meta.heartbeatWallMs)
        h.now = t0 + 60_000
        h.scope.advanceTimeBy(30_000)
        assertEquals(t0 + 60_000, (h.store.read() as StoredRecord.Present).meta.heartbeatWallMs)
        // New route → both files rewritten (AC 16).
        val reroute = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("g2-reroute-car-mn.json"), 1) as RouteOutcome.Ok).route
        h.manager.onNewRoute(reroute, Lang.EN)
        h.scope.runCurrent()
        val after = (h.store.read() as StoredRecord.Present).meta
        assertEquals("en", after.language)
        assertArrayEquals(reroute.source, h.store.readRoute(after))
        // Normal end → deleted synchronously; a queued write never brings it back (AC 17).
        h.manager.onNewRoute(route, Lang.MN)
        h.manager.onNormalEnd()
        h.scope.advanceTimeBy(120_000)
        assertFalse(dir.exists())
        assertEquals(RestoreRules.Decision.None, h.manager.decide(sessionAlive = false, locationOk = true))
    }

    /** AC 17 / 47: after a normal end a storage scan finds 0 coordinates, 0 route bodies and 0 destination texts. */
    @Test
    fun privacyScanAfterNormalEndAndExpiry() {
        val root = tmp.newFolder("files")
        val dir = File(root, "restore")
        val h = Harness(dir, t0)
        h.manager.onGuidanceStarted(trip, route, Lang.MN)
        h.scope.runCurrent()
        assertTrue("trip data present only while guiding", scan(root).isNotEmpty())
        h.manager.onNormalEnd()
        assertEquals(emptyList<String>(), scan(root))
        // Expiry: a record found after the window is deleted by the launcher's decision.
        h.manager.onGuidanceStarted(trip, route, Lang.MN)
        h.scope.runCurrent()
        val h2 = Harness(dir, t0 + 31 * 60_000L)
        val d = h2.manager.decide(sessionAlive = false, locationOk = true)
        assertTrue(d is RestoreRules.Decision.DeleteSilently)
        h2.manager.delete()
        assertEquals(emptyList<String>(), scan(root))
    }

    private fun scan(root: File): List<String> {
        val coordinate = Regex("-?\\d{1,3}\\.\\d{4,}")
        return root.walkTopDown().filter { it.isFile }.flatMap { f ->
            val text = f.readBytes().decodeToString()
            buildList {
                if (coordinate.containsMatchIn(text)) add("${f.name}: coordinate")
                if (text.contains("\"routes\"")) add("${f.name}: route body")
                if (text.contains("Зайсан")) add("${f.name}: destination text")
            }
        }.toList()
    }

    @Test
    fun loadParsesTheStoredRouteOrDeletesSilently() {
        val dir = File(tmp.root, "restore")
        val processor = RouteProcessor(FakeRouteParser())
        val client = mn.navmn.app.route.RouteClient("http://127.0.0.1:9", okhttp3.OkHttpClient(), { false }, processor)
        var now = t0
        val scope = TestScope(StandardTestDispatcher())
        val manager = RestoreManager(RestoreStore(dir), client, { now }, CoroutineScope(scope.coroutineContext))
        manager.onGuidanceStarted(trip, route, Lang.MN)
        scope.runCurrent()
        // A new process: no live session; the record restores.
        val fresh = RestoreManager(RestoreStore(dir), client, { now + 5 * 60_000L }, CoroutineScope(scope.coroutineContext))
        val d = fresh.decide(sessionAlive = false, locationOk = true) as RestoreRules.Decision.Restore
        val loaded = fresh.load(d.meta)
        assertNotNull(loaded)
        loaded!!
        assertEquals(route.plan.steps.size, loaded.route.plan.steps.size)
        assertEquals(trip, loaded.trip)
        assertEquals(Lang.MN, loaded.lang)
        // Tampered route → unreadable → deleted silently.
        File(dir, "route.bin").writeText("{}")
        assertNull(fresh.load(d.meta))
        assertFalse(dir.exists())
    }

    // ------------------------------------------------------------------------------------------- route source (7c)

    /** NAV-012 AC 16, 54, 55: the route source round-trips; a record without it (earlier build) reads as `gateway`. */
    @Test
    fun routeSourceRoundTripAndOlderRecordReadsAsGateway() {
        for (source in listOf(RouteSource.DEVICE, RouteSource.GATEWAY)) {
            val m = meta().copy(routeSource = source)
            val back = RestoreCodec.decode(RestoreCodec.encode(m))
            assertEquals(m, back)
            assertEquals(source == RouteSource.DEVICE, back!!.routeFromDevice)
        }
        // A meta.json written before change 7c: no routeSource key. Still schema 1, readable, restorable (not deleted).
        val old = String(RestoreCodec.encode(meta())).replace(",\"routeSource\":\"gateway\"", "")
        assertFalse(old.contains("routeSource"))
        val decoded = RestoreCodec.decode(old.encodeToByteArray())
        assertNotNull(decoded)
        assertEquals(RouteSource.GATEWAY, decoded!!.routeSource)
        assertFalse(decoded.routeFromDevice)
        val dir = File(tmp.root, "restore-old")
        dir.mkdirs()
        File(dir, RestoreStore.META).writeText(old)
        val read = RestoreStore(dir).read()
        assertTrue(read is StoredRecord.Present)
        assertTrue(RestoreRules.decide(read, t0 + 60_000, sessionAlive = false, locationOk = true) is RestoreRules.Decision.Restore)
        // An unknown value never marks the route as computed on the device.
        assertFalse(meta().copy(routeSource = "satellite").routeFromDevice)
        // The flag adds no coordinate, route body or text to the record (AC 17 / 47 scans).
        val json = String(RestoreCodec.encode(meta().copy(routeSource = RouteSource.DEVICE)))
        assertTrue(json.contains("\"routeSource\":\"device\""))
    }

    /** AC 16, 54: written from `ParsedRoute.onDevice` at «Эхлэх», follows every new route, and comes back on load. */
    @Test
    fun routeSourceIsWrittenFollowsReroutesAndIsRestored() {
        val dir = File(tmp.root, "restore")
        val processor = RouteProcessor(FakeRouteParser())
        val client = mn.navmn.app.route.RouteClient("http://127.0.0.1:9", okhttp3.OkHttpClient(), { false }, processor)
        val scope = TestScope(StandardTestDispatcher())
        val manager = RestoreManager(RestoreStore(dir), client, { t0 }, CoroutineScope(scope.coroutineContext))
        val device = route.asOnDevice()
        manager.onGuidanceStarted(trip, device, Lang.MN)
        scope.runCurrent()
        assertEquals(RouteSource.DEVICE, (RestoreStore(dir).read() as StoredRecord.Present).meta.routeSource)
        // A gateway reroute → gateway; a device reroute → device (within the same 2 s write, AC 16).
        val reroute = (processor.process(Fixtures.route("g2-reroute-car-mn.json"), 1) as RouteOutcome.Ok).route
        manager.onNewRoute(reroute, Lang.MN)
        scope.runCurrent()
        assertEquals(RouteSource.GATEWAY, (RestoreStore(dir).read() as StoredRecord.Present).meta.routeSource)
        manager.onNewRoute(reroute.asOnDevice(), Lang.MN)
        scope.runCurrent()
        val stored = (RestoreStore(dir).read() as StoredRecord.Present).meta
        assertEquals(RouteSource.DEVICE, stored.routeSource)
        // A new process restores it as an on-device route (the OF24 indicator, AC 54) ...
        val fresh = RestoreManager(RestoreStore(dir), client, { t0 + 60_000 }, CoroutineScope(scope.coroutineContext))
        val loaded = fresh.load((fresh.decide(sessionAlive = false, locationOk = true) as RestoreRules.Decision.Restore).meta)!!
        assertTrue(loaded.route.onDevice)
        assertEquals(reroute.plan.steps.size, loaded.route.plan.steps.size)
        // ... and a gateway record as a gateway route (no indicator).
        manager.onNewRoute(reroute, Lang.MN)
        scope.runCurrent()
        val gw = fresh.load((fresh.decide(sessionAlive = false, locationOk = true) as RestoreRules.Decision.Restore).meta)!!
        assertFalse(gw.route.onDevice)
    }
}
