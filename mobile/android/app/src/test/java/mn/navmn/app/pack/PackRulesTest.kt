package mn.navmn.app.pack

import mn.navmn.app.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-022 P1: manifest parsing, files to fetch, space, sizes, data dates and the offer rules (fake clock). */
class PackRulesTest {
    private val v1 = "20260927T193105Z"
    private val v2 = "20261004T193412Z"
    private val files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v2), FixtureFile.search(v2))

    @Test fun parsesTheContractAndIgnoresUnknownFields() {
        val m = ManifestParser.parse(Manifests.json(files))
        assertEquals(1, m.packSchema)
        assertEquals(v2, m.packVersion)
        assertEquals("$v2/routing.tar.gz", m.file(PackKind.ROUTING)!!.path)
        assertEquals("valhalla 3.9.0", m.file(PackKind.ROUTING)!!.graphBuilder)
        assertEquals(1, m.file(PackKind.SEARCH)!!.searchSchema)
        assertEquals(3, m.file(PackKind.TILES)!!.pmtilesVersion)
        assertEquals("auto", m.selfTest!!.route!!.costing)
        assertEquals("https://127.0.0.1/odbl-1.0", m.licence.url)
    }

    @Test fun rejectsManifestsThatAreNotSafe() {
        val good = Manifests.json(files)
        for (bad in listOf(
            good.replace("\"region\":\"mn\"", "\"region\":\"xx\""),
            good.replace("$v2/routing.tar.gz", "../routing.tar.gz"),
            good.replace("$v2/routing.tar.gz", "$v1/routing.tar.gz"), // path outside its version directory
            good.replace(files[1].sha, "ABC"),
            "not json",
            "{}",
        )) {
            assertThrows(ManifestException::class.java) { ManifestParser.parse(bad) }
        }
    }

    @Test fun filesToFetchCompareChecksumsNeverVersions() {
        val m = ManifestParser.parse(Manifests.json(files))
        assertEquals(3, PackRules.filesToFetch(m, InstalledPack.NONE, PackKind.entries.toSet()).size)
        val installed = InstalledPack(
            mapOf(
                PackKind.TILES to InstalledFile(PackKind.TILES, v1, "$v1/basemap.pmtiles", 1, files[0].sha, "2026-09-26T20:21:03Z"),
                // A newer installed version with another checksum: the (older) manifest file is fetched (AC 25).
                PackKind.ROUTING to InstalledFile(PackKind.ROUTING, "20261011T000000Z", "20261011T000000Z/routing.tar", 1, "0".repeat(64), "x"),
            ),
        )
        val plan = PackRules.filesToFetch(m, installed, PackKind.entries.toSet())
        assertEquals(listOf(PackKind.ROUTING, PackKind.SEARCH), plan.map { it.packKind })
        assertEquals(listOf(PackKind.ROUTING), PackRules.filesToFetch(m, installed, PackRules.kindsFor(JobMode.AUTO, installed)).map { it.packKind })
    }

    @Test fun incompatibleFilesAreNotOffered() {
        val m = ManifestParser.parse(Manifests.json(listOf(FixtureFile.tiles(v1), FixtureFile.routing(v2, builder = "valhalla 4.0.0"), FixtureFile.search(v2, schema = 7))))
        assertEquals(listOf(PackKind.TILES), PackRules.filesToFetch(m, InstalledPack.NONE, PackKind.entries.toSet()).map { it.packKind })
        val unknown = ManifestParser.parse(Manifests.json(files, packSchema = 2))
        assertFalse(PackRules.schemaKnown(unknown))
        assertTrue(PackRules.filesToFetch(unknown, InstalledPack.NONE, PackKind.entries.toSet()).isEmpty())
    }

    @Test fun requiredSpaceIsBytesPlusLargestDownloadPlusMargin() {
        val m = ManifestParser.parse(Manifests.json(files))
        val plan = PackRules.filesToFetch(m, InstalledPack.NONE, PackKind.entries.toSet())
        val expected = files.sumOf { it.raw.size.toLong() } + files.maxOf { it.gz.size.toLong() } + 50_000_000
        assertEquals(expected, PackRules.requiredSpace(plan))
        assertEquals(0, PackRules.spaceToFree(plan, expected))
        assertEquals(1, PackRules.spaceToFree(plan, expected - 1))
        assertEquals(0, PackRules.requiredSpace(emptyList()))
    }

    @Test fun sizeDisplayRule() {
        fun mn(b: Long) = SizeFormat.format(b, Lang.MN, "МБ", "ГБ")
        assertEquals("120\u00A0МБ", mn(119_500_001)) // rounded up, no-break space
        assertEquals("1\u00A0МБ", mn(1))
        assertEquals("999\u00A0МБ", mn(999_000_000))
        assertEquals("1,0\u00A0ГБ", mn(999_000_001)) // 1,000 MB → ГБ with one decimal and a comma
        assertEquals("1,2\u00A0ГБ", mn(1_150_000_000))
        assertEquals("1.2\u00A0GB", SizeFormat.format(1_150_000_000, Lang.EN, "MB", "GB"))
        assertEquals("338\u00A0MB", SizeFormat.format(337_100_000, Lang.EN, "MB", "GB"))
    }

    @Test fun dataDateIsTheUlaanbaatarCalendarDate() {
        assertEquals("2026-09-27", PackRules.dataDate("2026-09-26T20:21:03Z")) // UTC+8
        assertEquals("2026-09-26", PackRules.dataDate("2026-09-26T15:59:59Z"))
        assertNull(PackRules.dataDate(""))
    }

    private fun installedAt(routingTs: String, searchTs: String) = InstalledPack(
        mapOf(
            PackKind.ROUTING to InstalledFile(PackKind.ROUTING, v2, "$v2/routing.tar", 1, "a".repeat(64), routingTs),
            PackKind.SEARCH to InstalledFile(PackKind.SEARCH, v2, "$v2/search.sqlite", 1, "b".repeat(64), searchTs),
        ),
    )

    private val day = PackRules.DAY_MS
    private val t0 = java.time.Instant.parse("2026-10-04T00:00:00Z").toEpochMilli()

    private fun input(now: Long, declined: Long? = null, unmetered: Boolean = false, guiding: Boolean = false, shown: Boolean = false, bytes: Long = 33_000_000) =
        StaleOfferRule.Input(
            now = now, foreground = true, validated = true, unmetered = unmetered, guiding = guiding,
            installed = installedAt("2026-10-04T00:00:00Z", "2026-10-03T00:00:00Z"),
            staleDownloadBytes = bytes, lastDeclinedAt = declined, shownThisSession = shown, firstOfferThisSession = false,
        )

    @Test fun staleOfferTimingWithAFakeClock() {
        // The older of routing / search is 2026-10-03: stale only after more than 14 × 24 h.
        val oldest = t0 - day
        assertFalse(StaleOfferRule.shouldOffer(input(oldest + 14 * day)))
        assertTrue(StaleOfferRule.shouldOffer(input(oldest + 14 * day + 1)))
        // Never on Wi-Fi, during guidance, twice in one session, or without files to fetch (AC 27, 30).
        val now = oldest + 20 * day
        assertFalse(StaleOfferRule.shouldOffer(input(now, unmetered = true)))
        assertFalse(StaleOfferRule.shouldOffer(input(now, guiding = true)))
        assertFalse(StaleOfferRule.shouldOffer(input(now, shown = true)))
        assertFalse(StaleOfferRule.shouldOffer(input(now, bytes = 0)))
        // AC 29: «Дараа» pauses it for 7 × 24 h, then it comes back.
        assertFalse(StaleOfferRule.shouldOffer(input(now + 7 * day - 1, declined = now)))
        assertTrue(StaleOfferRule.shouldOffer(input(now + 7 * day, declined = now)))
    }

    @Test fun firstLaunchDecision() {
        assertEquals(FirstLaunchRule.Decision.FETCH_AND_OFFER, FirstLaunchRule.decide(false, InstalledPack.NONE, validated = true, unmetered = true))
        assertEquals(FirstLaunchRule.Decision.SET_FLAG_NO_OFFER, FirstLaunchRule.decide(false, InstalledPack.NONE, validated = true, unmetered = false))
        assertEquals(FirstLaunchRule.Decision.SET_FLAG_NO_OFFER, FirstLaunchRule.decide(false, InstalledPack.NONE, validated = false, unmetered = false))
        assertEquals(FirstLaunchRule.Decision.NONE, FirstLaunchRule.decide(true, InstalledPack.NONE, validated = true, unmetered = true))
        assertEquals(FirstLaunchRule.Decision.NONE, FirstLaunchRule.decide(false, installedAt("x", "y"), validated = true, unmetered = true))
    }

    @Test fun pmtilesHeaderSelfTest() = kotlinx.coroutines.runBlocking {
        val f = FixtureFile.tiles(v1)
        val h = PmtilesHeader.parse(f.raw)!!
        assertEquals(3, h.version)
        assertEquals(0, h.minZoom)
        assertEquals(14, h.maxZoom)
        assertTrue(DefaultSelfTests.TEST_POINTS.all { h.contains(it.lat, it.lon) })
        assertNull(PmtilesHeader.parse(ByteArray(127)))
    }

    @Test fun routingSelfTestRunsTheManifestRouteAndNeedsOk() = kotlinx.coroutines.runBlocking {
        val tmp = kotlin.io.path.createTempFile().toFile()
        val m = ManifestParser.parse(Manifests.json(files))
        val ok = FixedRoutingSelfTest("""{"code":"Ok","routes":[]}""")
        val search = SearchSelfTest { _, _, _ -> null }
        assertTrue(DefaultSelfTests(ok, search).routing(m.file(PackKind.ROUTING)!!, tmp, m))
        assertEquals(1, ok.calls)
        assertFalse(DefaultSelfTests(FixedRoutingSelfTest("""{"code":"NoRoute"}"""), search).routing(m.file(PackKind.ROUTING)!!, tmp, m))
        assertTrue(DefaultSelfTests.selfTestBody(m.selfTest!!.route!!)!!.contains("\"language\":\"mn-MN\""))
        tmp.delete()
        Unit
    }

    /** ADR-0017 A3 item 3: since NAV-023 the app runs the server's `skel` expression (NAV-020 task file §3.6). */
    @Test fun searchSelfTestQueryUsesTheSkeletonLikeTheServer() {
        assertEquals("skel : (\"suhbatar\"*)", SearchSelfTestQuery.match("Сүхбаатар"))
        assertEquals("skel : (\"ih\" \"delgur\"*)", SearchSelfTestQuery.match("Их дэлгүүр"))
        assertNull(SearchSelfTestQuery.match("  "))
    }
}
