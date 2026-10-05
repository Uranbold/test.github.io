package mn.navmn.app.pack

import kotlinx.coroutines.runBlocking
import mn.navmn.app.routing.PackFiles
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * NAV-022 P3 + P4 against a mock static `/packs/` server: install, integrity (AC 16, 17), resume (AC 14), 404 / 429
 * (AC 15), storage (AC 6, 7), network rules (AC 8–11, 23), automatic checks (AC 21, 24, 25, 38) and per-file cadence
 * (D166: a weekly manifest moves routing + search only).
 */
class PackJobTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val dispatcher = PackServer()
    private lateinit var store: PackStore
    private lateinit var http: PackHttp
    private val selfTests = FakeSelfTests()
    private val gate = FakeGate()
    private var allocatable = 10_000_000_000L
    private val sleeps = ArrayList<Long>()

    private val v1 = "20260927T193105Z"
    private val v2 = "20261004T193412Z"
    private val v3 = "20261011T193000Z"

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        store = PackStore(File(tmp.root, "packs"))
        http = PackHttp(server.url("/packs").toString(), OkHttpClient(), "navmn-android/test")
    }

    @After fun tearDown() {
        server.close()
    }

    private fun job(h: PackHttp = http) = PackJob(h, store, selfTests, { allocatable }, gate, { sleeps += it })

    private fun publishV1(): List<FixtureFile> {
        val files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v1), FixtureFile.search(v1))
        dispatcher.publish(Manifests.json(files), files)
        return files
    }

    @Test fun firstInstallWritesActiveJsonThatNav021Reads() = runBlocking {
        val files = publishV1()
        val progress = ArrayList<JobProgress>()
        val r = job().run(JobMode.USER, allowMetered = false) { progress += it }
        assertTrue(r.toString(), r is JobResult.Installed)
        val installed = store.installed()
        assertEquals(PackKind.entries.toSet(), installed.files.keys)
        for (f in files) {
            val file = store.fileOf(installed[f.kind]!!)!!
            assertEquals("$v1/${f.kind.installedName}", installed[f.kind]!!.path)
            assertEquals(f.sha, FixtureFile.sha256(file.readBytes()))
        }
        // The NAV-021 reader takes the routing entry of the same file (task file §1 format).
        val routing = PackFiles(store.packsDir).current()
        assertNotNull(routing)
        assertEquals(v1, routing!!.version)
        assertEquals("valhalla 3.9.0", routing.graphBuilder)
        // No partial or staging files remain; progress ended at 100 % then the checks.
        assertFalse(File(store.packsDir, "$v1.partial").exists())
        assertFalse(store.stagingRoot.exists())
        assertEquals(100, (progress.filterIsInstance<JobProgress.Downloading>().last()).percent)
        assertEquals(JobProgress.Verifying, progress.last())
        assertEquals(PackKind.entries.toSet(), selfTests.ran.toSet())
        // AC 42: no identifiers; the User-Agent is the app name and version only; gzip bytes untouched.
        for (req in dispatcher.requests) {
            assertEquals("navmn-android/test", req.headers["User-Agent"])
            assertNull(req.url.query)
        }
        for (req in dispatcher.fileRequests()) assertEquals("identity", req.headers["Accept-Encoding"])
    }

    @Test fun checksumFailureKeepsTheOldPackAndDeletesTheVersionsFiles() = runBlocking {
        publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        val before = File(store.packsDir, PackFiles.ACTIVE_JSON).readText()
        // AC 17: a proxy flips one byte in the middle of routing.tar.gz of the next publication.
        val next = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v2, seed = 22), FixtureFile.search(v2, seed = 33))
        dispatcher.publish(Manifests.json(next), next)
        dispatcher.flipMiddle += next[1].path
        val r = job().run(JobMode.USER, false)
        assertEquals(JobResult.Failed(FailReason.INTEGRITY), r)
        assertEquals(before, File(store.packsDir, PackFiles.ACTIVE_JSON).readText())
        assertFalse("no staged files of the failed version", store.stagingRoot.exists())
        assertFalse(File(store.packsDir, v2).exists())
        assertEquals(v1, store.installed()[PackKind.ROUTING]!!.version)
    }

    @Test fun validGzipOfTheWrongFileFailsWithoutActivation() = runBlocking {
        val good = FixtureFile.routing(v1)
        val wrong = FixtureFile.routing(v1, seed = 99)
        val files = listOf(FixtureFile.tiles(v1), good, FixtureFile.search(v1))
        dispatcher.publish(Manifests.json(files), files)
        // The server sends a valid gzip of another file under the manifest's path: the checksum check fails.
        dispatcher.files["/packs/mn/${good.path}"] = wrong.gz
        val r = job().run(JobMode.USER, false)
        assertTrue(r.toString(), r is JobResult.Failed)
        assertTrue(store.installed().isEmpty)
        assertFalse(File(store.packsDir, PackFiles.ACTIVE_JSON).exists())
    }

    @Test fun selfTestFailureActivatesNothing() = runBlocking {
        publishV1()
        selfTests.failKinds = setOf(PackKind.ROUTING)
        assertEquals(JobResult.Failed(FailReason.SELF_TEST), job().run(JobMode.USER, false))
        assertTrue(store.installed().isEmpty)
        assertFalse(store.stagingRoot.exists())
    }

    @Test fun resumeSendsRangeWithIfRangeAndTransfersOnlyTheRest() = runBlocking {
        val files = publishV1()
        val tiles = files[0]
        // About 50 % of basemap.pmtiles.gz is on disk from an interrupted run, with its validator.
        val dir = store.stagingDir(v1).apply { mkdirs() }
        val half = tiles.gz.size / 2
        File(dir, "basemap.pmtiles.gz").writeBytes(tiles.gz.copyOfRange(0, half))
        File(dir, "basemap.pmtiles.gz.validator").writeText(dispatcher.etags["/packs/mn/${tiles.path}"]!!)
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        val req = dispatcher.fileRequests().single { it.url.encodedPath.endsWith("basemap.pmtiles.gz") }
        assertEquals("bytes=$half-", req.headers["Range"])
        assertEquals(dispatcher.etags["/packs/mn/${tiles.path}"], req.headers["If-Range"])
        // AC 14: total bytes for the file ≤ download_bytes + 1 MB (here exactly the missing part).
        assertEquals((tiles.gz.size - half).toLong(), dispatcher.bytesSent["/packs/mn/${tiles.path}"])
        assertEquals(tiles.sha, FixtureFile.sha256(store.fileOf(store.installed()[PackKind.TILES]!!)!!.readBytes()))
    }

    @Test fun aChangedValidatorRestartsTheFileFromByteZero() = runBlocking {
        val files = publishV1()
        val tiles = files[0]
        val dir = store.stagingDir(v1).apply { mkdirs() }
        File(dir, "basemap.pmtiles.gz").writeBytes(ByteArray(tiles.gz.size / 2) { 7 }) // junk from another validator
        File(dir, "basemap.pmtiles.gz.validator").writeText("\"old\"")
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed) // 200 to the If-Range request → restart
        assertEquals(tiles.gz.size.toLong(), dispatcher.bytesSent["/packs/mn/${tiles.path}"])
    }

    @Test fun aServerWithoutRangeSupportStillInstalls() = runBlocking {
        val files = publishV1()
        dispatcher.ignoreRange = true
        val dir = store.stagingDir(v1).apply { mkdirs() }
        File(dir, "basemap.pmtiles.gz").writeBytes(files[0].gz.copyOfRange(0, 100))
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
    }

    @Test fun meteredNetworkWithoutConfirmationSendsNoFileRequest() = runBlocking {
        publishV1()
        gate.unmetered = false
        assertEquals(JobResult.WaitNetwork, job().run(JobMode.USER, allowMetered = false))
        assertEquals("AC 11: the manifest may use mobile data", 1, dispatcher.manifestRequests().size)
        assertEquals("AC 8, 23: 0 pack file requests over mobile data", 0, dispatcher.fileRequests().size)
        // After OF13 «Татах» the same download runs on mobile data (AC 9).
        assertTrue(job().run(JobMode.USER, allowMetered = true) is JobResult.Installed)
    }

    @Test fun noValidatedNetworkWaitsWithoutAnyRequest() = runBlocking {
        publishV1()
        gate.validated = false
        assertEquals(JobResult.WaitNetwork, job().run(JobMode.USER, false))
        assertEquals(0, dispatcher.requests.size)
    }

    @Test fun notEnoughSpaceSendsNoFileRequestAndReportsTheSpaceToFree() = runBlocking {
        val files = publishV1()
        val required = files.sumOf { it.raw.size.toLong() } + files.maxOf { it.gz.size.toLong() } + PackRules.MARGIN_BYTES
        allocatable = required - 45_000_000
        assertEquals(JobResult.NoSpace(45_000_000), job().run(JobMode.USER, false))
        assertEquals("AC 6: 0 file requests", 0, dispatcher.fileRequests().size)
        assertTrue(store.installed().isEmpty)
    }

    @Test fun diskFullWhileWritingDeletesPartialFilesAndKeepsThePack() = runBlocking {
        publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        val before = File(store.packsDir, PackFiles.ACTIVE_JSON).readText()
        val next = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v2, seed = 5), FixtureFile.search(v2, seed = 6))
        dispatcher.publish(Manifests.json(next), next)
        // AC 7: a write fails with ENOSPC after part of the file arrived.
        val full = object : PackHttp(server.url("/packs").toString(), OkHttpClient(), "ua") {
            override suspend fun download(f: PackFile, gz: File, validatorFile: File, onBytes: (Long) -> Unit): DownloadResult {
                gz.parentFile?.mkdirs()
                gz.writeBytes(ByteArray(100))
                return DownloadResult.NoSpace
            }
        }
        val r = job(full).run(JobMode.USER, false)
        assertTrue(r.toString(), r is JobResult.NoSpace)
        assertFalse("partial files deleted", store.stagingRoot.exists())
        assertEquals(before, File(store.packsDir, PackFiles.ACTIVE_JSON).readText())
    }

    @Test fun retiredFileReReadsTheManifest() = runBlocking {
        val files = publishV1()
        dispatcher.retireOnce += "/packs/mn/${files[1].path}"
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        assertEquals("AC 15: the manifest is read again after a 404", 2, dispatcher.manifestRequests().size)
    }

    @Test fun fiveNetworkFailuresFailTheUserDownloadAndKeepPartials() = runBlocking {
        publishV1()
        val failing = object : PackHttp(server.url("/packs").toString(), OkHttpClient(), "ua") {
            var calls = 0
            override suspend fun download(f: PackFile, gz: File, validatorFile: File, onBytes: (Long) -> Unit): DownloadResult {
                calls++
                gz.parentFile?.mkdirs()
                if (!gz.exists()) gz.writeBytes(ByteArray(10))
                return DownloadResult.Failed(progressed = false)
            }
        }
        assertEquals(JobResult.Failed(FailReason.NETWORK), job(failing).run(JobMode.USER, false))
        assertEquals(5, failing.calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), sleeps)
        assertTrue("AC 15: partial files kept for a resume", store.stagingRoot.walkTopDown().any { it.name.endsWith(".gz") })
    }

    @Test fun weeklyManifestMovesRoutingAndSearchOnly() = runBlocking {
        val first = publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        dispatcher.requests.clear()
        // D166: the weekly publication keeps the monthly tiles file (same version, same checksum).
        val weekly = listOf(first[0], FixtureFile.routing(v2, seed = 12), FixtureFile.search(v2, seed = 13))
        dispatcher.publish(Manifests.json(weekly), weekly)
        val r = job().run(JobMode.AUTO, false)
        assertEquals(setOf(PackKind.ROUTING, PackKind.SEARCH), (r as JobResult.Installed).kinds)
        assertEquals(2, dispatcher.fileRequests().size)
        val p = store.installed()
        assertEquals(v1, p[PackKind.TILES]!!.version)
        assertEquals(v2, p[PackKind.ROUTING]!!.version)
        assertEquals("$v2/search.sqlite", p[PackKind.SEARCH]!!.path)
    }

    @Test fun automaticCheckUsesIfNoneMatchAnd304SendsNoFileRequest() = runBlocking {
        publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        dispatcher.requests.clear()
        assertEquals(JobResult.NothingToDo, job().run(JobMode.AUTO, false))
        assertEquals(dispatcher.manifestEtag, dispatcher.manifestRequests().single().headers["If-None-Match"])
        assertEquals(0, dispatcher.fileRequests().size)
    }

    @Test fun olderVersionWithAnotherChecksumIsInstalledLikeAnUpdate() = runBlocking {
        val v2files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v3, seed = 40), FixtureFile.search(v3, seed = 41))
        dispatcher.publish(Manifests.json(v2files), v2files)
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        // AC 25: a rollback republishes v2 (older) files.
        val rollback = listOf(v2files[0], FixtureFile.routing(v2, seed = 50), FixtureFile.search(v2, seed = 51))
        dispatcher.publish(Manifests.json(rollback), rollback)
        assertTrue(job().run(JobMode.AUTO, false) is JobResult.Installed)
        assertEquals(v2, store.installed()[PackKind.ROUTING]!!.version)
    }

    @Test fun unknownPackSchemaOffersNothing() = runBlocking {
        val files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v1), FixtureFile.search(v1))
        dispatcher.publish(Manifests.json(files, packSchema = 2), files)
        assertEquals(JobResult.Failed(FailReason.INCOMPATIBLE), job().run(JobMode.USER, false))
        assertEquals(0, dispatcher.fileRequests().size)
    }

    @Test fun routingOutsideTheAllowListIsSkippedWhileTilesAndSearchInstall() = runBlocking {
        val files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v1, builder = "valhalla 9.9.9"), FixtureFile.search(v1))
        dispatcher.publish(Manifests.json(files), files)
        val r = job().run(JobMode.USER, false) as JobResult.Installed
        assertEquals(setOf(PackKind.TILES, PackKind.SEARCH), r.kinds)
        assertNull(PackFiles(store.packsDir).current())
        assertFalse(dispatcher.fileRequests().any { it.url.encodedPath.endsWith("routing.tar.gz") })
    }

    /** NAV-022 review M2: an empty plan because routing is not allow-listed must not store that manifest's ETag. */
    @Test fun incompatibleRoutingDoesNotRememberTheManifestEtag() = runBlocking {
        val first = publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        val oldEtag = store.installed().manifestEtag
        assertEquals(dispatcher.manifestEtag, oldEtag)
        // The next build moved to a graph builder this app does not know yet (tiles and search unchanged).
        val next = listOf(first[0], FixtureFile.routing(v3, seed = 60, builder = "valhalla 9.9.9"), first[2])
        dispatcher.publish(Manifests.json(next), next, etag = "\"m-next\"")
        dispatcher.requests.clear()
        assertEquals(JobResult.NothingToDo, job().run(JobMode.AUTO, false))
        assertEquals(0, dispatcher.fileRequests().size)
        assertEquals("the skipped manifest's ETag is not remembered", oldEtag, store.installed().manifestEtag)
        // So the next automatic check gets the full manifest again (a newer app could take the file), not a 304.
        dispatcher.requests.clear()
        assertEquals(JobResult.NothingToDo, job().run(JobMode.AUTO, false))
        assertEquals(oldEtag, dispatcher.manifestRequests().single().headers["If-None-Match"])
        assertEquals(v1, store.installed()[PackKind.ROUTING]!!.version)
    }

    /** The other side of M2: an empty plan with every file compatible still remembers the ETag (AC 21). */
    @Test fun compatibleEmptyPlanRemembersTheManifestEtag() = runBlocking {
        val first = publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        dispatcher.publish(Manifests.json(first), first, etag = "\"m-same-files\"")
        assertEquals(JobResult.NothingToDo, job().run(JobMode.AUTO, false))
        assertEquals("\"m-same-files\"", store.installed().manifestEtag)
    }

    @Test fun incompatibleWantedListsOnlyChangedFilesThisAppCannotTake() {
        val m = ManifestParser.parse(
            Manifests.json(listOf(FixtureFile.tiles(v1), FixtureFile.routing(v3, builder = "valhalla 9.9.9"), FixtureFile.search(v1))),
        )
        val wanted = PackRules.incompatibleWanted(m, InstalledPack.NONE, PackKind.entries.toSet())
        assertEquals(listOf(PackKind.ROUTING), wanted.map { it.packKind })
        assertTrue(PackRules.incompatibleWanted(m, InstalledPack.NONE, setOf(PackKind.TILES, PackKind.SEARCH)).isEmpty())
        assertTrue(PackRules.incompatibleWanted(m, InstalledPack.NONE, PackKind.entries.toSet()) { true }.isEmpty())
    }

    @Test fun automaticChecksOnlyUpdateInstalledKinds() = runBlocking {
        publishV1()
        // AC 38: nothing installed (deleted) → the periodic check downloads nothing.
        assertEquals(JobResult.NothingToDo, job().run(JobMode.AUTO, false))
        assertEquals(0, dispatcher.fileRequests().size)
    }

    @Test fun staleModeFetchesRoutingAndSearchOnly() = runBlocking {
        val first = publishV1()
        assertTrue(job().run(JobMode.USER, false) is JobResult.Installed)
        val all = listOf(FixtureFile.tiles(v2, seed = 70), FixtureFile.routing(v2, seed = 71), FixtureFile.search(v2, seed = 72))
        dispatcher.publish(Manifests.json(all), all)
        dispatcher.requests.clear()
        gate.unmetered = false
        val r = job().run(JobMode.STALE, allowMetered = true) as JobResult.Installed
        assertEquals("AC 27: tiles is never part of the 14-day offer", setOf(PackKind.ROUTING, PackKind.SEARCH), r.kinds)
        assertEquals(first[0].sha, store.installed()[PackKind.TILES]!!.sha256)
    }
}
