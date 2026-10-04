package mn.navmn.app.demo.replay

import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.qa.planOf
import mn.navmn.app.qa.repoFile
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.FakeRouteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-019 AC 4, 6, 8 (ADR-0016 §8.3, §12): the demo route catalogue reads the NAV-017 manifest from the asset layout the
 * Gradle task `syncDemoAssets` builds (`demo/routes.manifest.json`, `demo/files/<repo path>`): exactly the three picker
 * routes R1–R3 in manifest order with their names, modes and recorded summaries; a broken route or track fails only
 * that entry.
 */
class DemoCatalogueTest {
    private val entries = DemoCatalogue.load(RepoAssets::read)
    private val processor = RouteProcessor(FakeRouteParser())

    @Test
    fun threePickerRoutesInManifestOrder() {
        assertEquals(listOf("r1", "r2", "r3"), entries.map { it.id })
        assertEquals(listOf(TravelMode.CAR, TravelMode.WALK, TravelMode.CAR), entries.map { it.mode })
        assertEquals(listOf("Сүхбаатарын талбай", "Сүхбаатарын талбай", null), entries.map { it.originName }) // scan:data
        assertEquals(listOf("Зайсан Голден Вилл", "Хаан банк", "Золтамир"), entries.map { it.destinationName }) // scan:data
        assertEquals(
            listOf(
                "demo/files/mobile/android/app/src/test/resources/routes/p1-p3-car-mn.json",
                "demo/files/mobile/android/app/src/test/resources/routes/p1-p2-walk-mn.json",
                "demo/files/mobile/android/app/src/test/resources/routes/g8-roundabout-car-mn.json",
            ),
            entries.map { it.routeAsset },
        )
        assertEquals(listOf("G1", "G5", "G8").map { "demo/files/tests/gpx/nav005/$it.gpx" }, entries.map { it.trackAsset })
    }

    @Test
    fun summariesAreTheRecordedResponsesAndEveryAssetParses() {
        for (e in entries) {
            val plan = planOf(RepoAssets.read(e.routeAsset))
            assertEquals(e.id, plan.distance, e.distanceM!!, 0.0)
            assertEquals(e.id, plan.duration, e.durationS!!, 0.0)
            assertTrue(e.id, ReplayTrack.parse(RepoAssets.read(e.trackAsset)).size >= 2)
        }
    }

    @Test
    fun theTestOnlyArrivalTrackIsNotOffered() {
        val manifest = DemoCatalogue.parseManifest(RepoAssets.read(DemoCatalogue.MANIFEST_ASSET))
        assertTrue(manifest.routes.any { it.id == "g4" && !it.picker })
        assertTrue(entries.none { it.id == "g4" })
    }

    @Test
    fun openingAnEntryGivesTheRecordedRouteTrackAndPoints() {
        val r3 = entries.first { it.id == "r3" }
        val opened = DemoCatalogue.open(r3, RepoAssets::read) { processor.process(it, 0) }.getOrThrow()
        assertTrue("R3 start has no OSM feature: «Сонгосон цэг»", opened.origin is RoutePoint.MapPoint)
        assertEquals("Золтамир", (opened.destination as RoutePoint.Place).name) // scan:data
        assertEquals(opened.outcome.route.plan.end, opened.destination.point)
        assertEquals(787_000L, opened.track.durationMs)
    }

    /** AC 8: a corrupted route or track fails that entry only (the picker shows «Алдаа гарлаа» under it). */
    @Test
    fun aCorruptedAssetFailsOnlyThatEntry() {
        val r1 = entries.first { it.id == "r1" }
        val badTrack: (String) -> ByteArray = { if (it == r1.trackAsset) "<gpx>broken".encodeToByteArray() else RepoAssets.read(it) }
        assertTrue(DemoCatalogue.open(r1, badTrack) { processor.process(it, 0) }.isFailure)
        val badRoute: (String) -> ByteArray = { if (it == r1.routeAsset) "{\"code\":".encodeToByteArray() else RepoAssets.read(it) }
        assertTrue(DemoCatalogue.open(r1, badRoute) { processor.process(it, 0) }.isFailure)
        assertTrue(DemoCatalogue.open(r1, RepoAssets::read) { RouteOutcome.BadResponse }.isFailure)
        // The list still loads with a corrupted route (the entry has no summary; a tap reports the error).
        val list = DemoCatalogue.load(badRoute)
        assertEquals(3, list.size)
        assertNull(list.first { it.id == "r1" }.distanceM)
        assertTrue(DemoCatalogue.open(entries.first { it.id == "r2" }, badRoute) { processor.process(it, 0) }.isSuccess)
        // An unreadable manifest is an error of its own (UX P1 "Manifest unreadable").
        assertTrue(runCatching { DemoCatalogue.load { "not json".encodeToByteArray() } }.isFailure)
    }

    @Test
    fun manifestIsTheWebDemoManifest() {
        // ADR-0016 §8.3: the app reads the NAV-017 manifest byte for byte (no Android copy under mobile/).
        assertTrue(RepoAssets.read(DemoCatalogue.MANIFEST_ASSET).contentEquals(repoFile("web/src/demo/routes.manifest.json").readBytes()))
    }
}
