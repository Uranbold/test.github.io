package mn.navmn.app.pack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkStateSource
import mn.navmn.app.routing.PackFiles
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * NAV-022 PackManager with fakes for WorkManager, the network and the clock: the user-started flow F2 (AC 6, 8–11),
 * the first-launch offer (AC 1–3), the 14-day offer with a fake clock (AC 27–30), outcomes (AC 41), cancel and delete
 * (AC 13, 36, 38).
 */
class PackManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val dispatcher = PackServer()
    private lateinit var store: PackStore
    private val scheduler = FakeScheduler()
    private val network = FakeNetwork()
    private val prefs = MemoryPackPrefs()
    private val notifier = FakeNotifier()
    private val guiding = MutableStateFlow(false)
    private var now = Instant.parse("2026-10-05T04:00:00Z").toEpochMilli()
    private var allocatable = 10_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: PackManager

    private val v1 = "20260927T193105Z"
    private val v2 = "20261004T193412Z"
    private val files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v2), FixtureFile.search(v2))

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        dispatcher.publish(Manifests.json(files), files)
        store = PackStore(File(tmp.root, "packs"))
        manager = newManager()
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun newManager() = PackManager(
        enabled = true,
        store = store,
        http = PackHttp(server.url("/packs").toString(), OkHttpClient(), "navmn-android/test"),
        prefs = prefs,
        scheduler = scheduler,
        network = network,
        space = { allocatable },
        selfTests = FakeSelfTests(),
        notifier = notifier,
        guiding = guiding,
        routingHold = { null },
        clock = { now },
        elapsed = { 0L },
        sleep = {},
        scope = scope,
        log = DebugLog.NONE,
    )

    private fun eventually(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $what")
    }

    // ------------------------------------------------------------ F2: a user-started download

    @Test fun wifiStartsAWifiOnlyJobAtOnce() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.download().join()
        assertEquals(listOf(Enqueued(JobMode.USER, false, 0)), scheduler.enqueued)
        assertEquals("the job downloads; the manager only read the manifest", 0, dispatcher.fileRequests().size)
        eventually("downloading row") { manager.state.value.job == JobUi.Downloading(0) }
    }

    @Test fun mobileDataAsksFirstWithTheSizeAndSendsNoFileRequest() = runBlocking {
        network.set(validated = true, unmetered = false)
        manager.download().join()
        assertEquals("AC 8: 0 file requests and no job before an answer", emptyList<Enqueued>(), scheduler.enqueued)
        assertEquals(0, dispatcher.fileRequests().size)
        assertEquals(MobileConfirm(JobMode.USER, files.sumOf { it.gz.size.toLong() }), manager.confirm.value)
        manager.confirmMobileData()
        assertEquals("AC 9: «Татах» → any validated network", Enqueued(JobMode.USER, true, 0), scheduler.enqueued.single())
        assertNull(manager.confirm.value)
        // The confirmation is never remembered: the next download asks again.
        scheduler.userWork.value = null
        manager.download().join()
        assertEquals(1, scheduler.enqueued.size)
        manager.waitForWifi()
        assertEquals("«Wi-Fi хүлээх» queues for Wi-Fi", Enqueued(JobMode.USER, false, 0), scheduler.enqueued.last())
    }

    @Test fun noNetworkQueuesForWifiAndShowsWaiting() = runBlocking {
        network.set(validated = false, unmetered = false)
        manager.download().join()
        assertEquals(listOf(Enqueued(JobMode.USER, false, 0)), scheduler.enqueued)
        eventually("waiting") { manager.state.value.job == JobUi.Waiting }
        assertEquals(0, dispatcher.requests.size)
    }

    @Test fun notEnoughSpaceShowsTheSpaceToFreeAndStartsNothing() = runBlocking {
        network.set(validated = true, unmetered = true)
        val required = files.sumOf { it.raw.size.toLong() } + files.maxOf { it.gz.size.toLong() } + PackRules.MARGIN_BYTES
        allocatable = required - 45_000_000
        manager.download().join()
        assertTrue(scheduler.enqueued.isEmpty())
        eventually("storage row") { manager.state.value.job == JobUi.NoSpace(45_000_000) }
        assertEquals(0, dispatcher.fileRequests().size)
    }

    @Test fun manifestFailureShowsFailed() = runBlocking {
        network.set(validated = true, unmetered = true)
        dispatcher.manifestCode = 503
        manager.download().join()
        assertTrue(scheduler.enqueued.isEmpty())
        eventually("failed row") { manager.state.value.job == JobUi.Failed }
    }

    // ------------------------------------------------------------ the job outcomes

    @Test fun userJobInstallsAndReportsReadyInTheForeground() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.onForeground()
        assertEquals(PackManager.WorkOutcome.DONE, manager.runWork(JobMode.USER, false))
        eventually("installed") { manager.state.value.installed.files.keys == PackKind.entries.toSet() }
        assertEquals(PackMessage.Ready, manager.message.value)
        assertEquals(0, notifier.readies)
        assertTrue("AC 21: the periodic check is scheduled after an install", scheduler.periodic)
        assertTrue(notifier.progressPosts > 0)
        eventually("up to date") { manager.state.value.facts?.hasFilesToFetch == false }
    }

    @Test fun userJobInTheBackgroundNotifies() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.onBackground()
        manager.runWork(JobMode.USER, false)
        assertEquals(1, notifier.readies)
        assertNull(manager.message.value)
    }

    @Test fun automaticUpdateIsSilent() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.runWork(JobMode.USER, false)
        manager.dismissMessage()
        val readies = notifier.readies
        val posts = notifier.progressPosts
        val weekly = listOf(files[0], FixtureFile.routing("20261011T193000Z", seed = 8), FixtureFile.search("20261011T193000Z", seed = 9))
        dispatcher.publish(Manifests.json(weekly), weekly)
        manager.runWork(JobMode.AUTO, false)
        eventually("updated") { manager.state.value.installed[PackKind.ROUTING]?.version == "20261011T193000Z" }
        assertEquals("AC 22: no dialog, no completion notification", readies, notifier.readies)
        assertEquals(posts, notifier.progressPosts)
        assertNull(manager.message.value)
    }

    @Test fun failedUserJobShowsFailedAndKeepsTheOldPack() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.onForeground()
        manager.runWork(JobMode.USER, false)
        val next = listOf(files[0], FixtureFile.routing("20261011T193000Z", seed = 8), FixtureFile.search("20261011T193000Z", seed = 9))
        dispatcher.publish(Manifests.json(next), next)
        dispatcher.flipMiddle += next[1].path
        manager.runWork(JobMode.USER, false)
        assertEquals(PackMessage.Failed, manager.message.value)
        eventually("failed row") { manager.state.value.job == JobUi.Failed }
        assertEquals(v2, store.installed()[PackKind.ROUTING]!!.version)
    }

    /**
     * NAV-022 review M1: an IOException from PackStore.install (here: the target version directory cannot be created
     * because a plain file has its name) must not escape runWork. The UI leaves «Татаж байна» for the failure, the
     * staged and partial files go, the old pack stays installed and a later run installs normally.
     */
    @Test fun installIoErrorShowsFailedAndKeepsTheOldPack() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.onForeground()
        assertEquals(PackManager.WorkOutcome.DONE, manager.runWork(JobMode.USER, false))
        manager.dismissMessage()
        val v3 = "20261011T193000Z"
        val next = listOf(files[0], FixtureFile.routing(v3, seed = 8), FixtureFile.search(v3, seed = 9))
        dispatcher.publish(Manifests.json(next), next)
        val blocker = File(store.packsDir, v3).apply { writeText("not a directory") }
        val outcome = manager.runWork(JobMode.USER, false) // threw IOException("rename failed") before the fix
        assertEquals(PackManager.WorkOutcome.DONE, outcome)
        assertEquals(PackMessage.Failed, manager.message.value)
        eventually("failed row") { manager.state.value.job == JobUi.Failed }
        assertEquals("the old pack is kept", v2, store.installed()[PackKind.ROUTING]!!.version)
        assertEquals(v2, manager.state.value.installed[PackKind.ROUTING]!!.version)
        assertTrue("the old routing file is still there", store.fileOf(store.installed()[PackKind.ROUTING]!!)!!.isFile)
        assertFalse("staging deleted", store.stagingRoot.exists())
        assertTrue("no partial directory", store.packsDir.listFiles().orEmpty().none { it.name.endsWith(PackStore.PARTIAL) })
        // The cause gone, «Дахин оролдох» installs the new version.
        blocker.delete()
        manager.dismissMessage()
        assertEquals(PackManager.WorkOutcome.DONE, manager.runWork(JobMode.USER, false))
        assertEquals(PackMessage.Ready, manager.message.value)
        assertEquals(v3, store.installed()[PackKind.ROUTING]!!.version)
    }

    /** M1 for an automatic run: any exception (here from a self-test) stays silent and keeps the pack. */
    @Test fun automaticRunExceptionIsSilentAndKeepsThePack() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.runWork(JobMode.USER, false)
        manager.dismissMessage()
        val throwing = PackManager(
            enabled = true,
            store = store,
            http = PackHttp(server.url("/packs").toString(), OkHttpClient(), "navmn-android/test"),
            prefs = prefs,
            scheduler = scheduler,
            network = network,
            space = { allocatable },
            selfTests = FakeSelfTests(throwKinds = setOf(PackKind.ROUTING)),
            notifier = notifier,
            guiding = guiding,
            routingHold = { null },
            clock = { now },
            elapsed = { 0L },
            sleep = {},
            scope = scope,
            log = DebugLog.NONE,
        )
        val weekly = listOf(files[0], FixtureFile.routing("20261011T193000Z", seed = 8), FixtureFile.search("20261011T193000Z", seed = 9))
        dispatcher.publish(Manifests.json(weekly), weekly)
        val failedBefore = notifier.fails
        assertEquals(PackManager.WorkOutcome.DONE, throwing.runWork(JobMode.AUTO, false))
        assertNull("AC 17/22: silent", throwing.message.value)
        assertEquals(failedBefore, notifier.fails)
        assertEquals(v2, store.installed()[PackKind.ROUTING]!!.version)
        assertFalse(store.stagingRoot.exists())
    }

    @Test fun cancelLeavesNoPartialFiles() = runBlocking {
        val dir = store.stagingDir(v2).apply { mkdirs() }
        File(dir, "routing.tar.gz").writeBytes(ByteArray(100))
        manager.cancel().join()
        assertTrue(scheduler.cancelledUser)
        assertFalse(store.stagingRoot.exists())
        assertEquals(1, notifier.clears)
    }

    @Test fun deleteRemovesThePackAndStopsAutomaticChecks() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.runWork(JobMode.USER, false)
        manager.setMapTilesInUse(null)
        manager.delete().join()
        eventually("no pack") { manager.state.value.installed.isEmpty }
        assertFalse(File(store.packsDir, PackFiles.ACTIVE_JSON).exists())
        assertTrue(scheduler.periodicCancelled)
        eventually("files freed") { store.packsDir.listFiles().orEmpty().none { PackRules.SLOT_ID.matches(it.name) } }
        // AC 38: the first-launch offer stays off.
        manager.onBrowseShown()
        Thread.sleep(200)
        assertNull(manager.offer.value)
    }

    @Test fun mapHoldKeepsTheShownBasemapUntilReleased() = runBlocking {
        network.set(validated = true, unmetered = true)
        manager.runWork(JobMode.USER, false)
        val tiles = manager.installedTiles()!!
        manager.setMapTilesInUse(tiles.absolutePath)
        manager.delete().join()
        assertTrue("the map still shows it (AC 36)", tiles.isFile)
        manager.setMapTilesInUse(null)
        eventually("released basemap deleted") { !tiles.exists() }
    }

    // ------------------------------------------------------------ first launch (AC 1–3)

    @Test fun firstLaunchWithoutWifiSetsTheFlagWithoutAnOffer() {
        network.set(validated = true, unmetered = false)
        manager.onBrowseShown()
        eventually("flag") { prefs.offerFlag }
        assertNull(manager.offer.value)
        assertEquals(0, dispatcher.requests.size)
    }

    @Test fun firstLaunchOnWifiOffersAndTheFlagIsSetWhenShown() {
        network.set(validated = true, unmetered = true)
        manager.onBrowseShown()
        eventually("offer") { manager.offer.value != null }
        val offer = manager.offer.value as PackOffer.First
        assertEquals(files.sumOf { it.gz.size.toLong() }, offer.downloadBytes)
        assertFalse(prefs.offerFlag)
        manager.onOfferShown(offer)
        assertTrue("AC 2: stored the moment it shows", prefs.offerFlag)
        manager.onOfferClosed(offer, accepted = false)
        assertNull(manager.offer.value)
        // Once per process; and never again on this installation.
        newManager().onBrowseShown()
        Thread.sleep(200)
        assertNull(manager.offer.value)
    }

    @Test fun firstLaunchManifestFailureLeavesTheFlagUnset() {
        network.set(validated = true, unmetered = true)
        dispatcher.manifestCode = 404
        manager.onBrowseShown()
        eventually("manifest requested") { dispatcher.manifestRequests().isNotEmpty() }
        Thread.sleep(200)
        assertNull(manager.offer.value)
        assertFalse("AC 3: a later start with Wi-Fi tries again", prefs.offerFlag)
    }

    // ------------------------------------------------------------ the 14-day offer (AC 27–30)

    private fun installWithDates(routingTs: String, searchTs: String) = runBlocking {
        val old = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v2, dataTimestamp = routingTs), FixtureFile.search(v2, dataTimestamp = searchTs))
        dispatcher.publish(Manifests.json(old), old)
        network.set(validated = true, unmetered = true)
        manager.runWork(JobMode.USER, false)
        manager.dismissMessage()
        // A newer weekly publication exists on the server.
        val newer = listOf(old[0], FixtureFile.routing("20261011T193000Z", seed = 81), FixtureFile.search("20261011T193000Z", seed = 82))
        dispatcher.publish(Manifests.json(newer), newer)
        newer
    }

    @Test fun staleOfferFollowsTheFakeClock() {
        val newer = installWithDates("2026-09-20T00:00:00Z", "2026-09-19T00:00:00Z")
        val oldest = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        network.set(validated = true, unmetered = false)

        now = oldest + 14 * PackRules.DAY_MS // not more than 14 × 24 h yet
        manager.onForeground()
        Thread.sleep(300)
        assertNull(manager.offer.value)

        now = oldest + 15 * PackRules.DAY_MS
        manager.onBackground()
        manager.onForeground()
        eventually("offer") { manager.offer.value is PackOffer.Stale }
        val offer = manager.offer.value as PackOffer.Stale
        assertEquals("AC 27: routing + search only", newer[1].gz.size.toLong() + newer[2].gz.size, offer.downloadBytes)
        manager.onOfferShown(offer)
        manager.onOfferClosed(offer, accepted = false)
        assertEquals(now, prefs.lastStaleDecline)

        // AC 29: not again for 7 × 24 h, also in new foreground sessions; then again.
        now += 7 * PackRules.DAY_MS - 60_000
        manager.onBackground()
        manager.onForeground()
        Thread.sleep(300)
        assertNull(manager.offer.value)
        now += 60_000
        manager.onBackground()
        manager.onForeground()
        eventually("re-offer") { manager.offer.value is PackOffer.Stale }
    }

    @Test fun staleOfferNeverDuringGuidanceOrOnWifi() {
        installWithDates("2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
        guiding.value = true
        network.set(validated = true, unmetered = false)
        manager.onForeground()
        Thread.sleep(300)
        assertNull(manager.offer.value)
        guiding.value = false
        network.set(validated = true, unmetered = true)
        manager.onBackground()
        manager.onForeground()
        Thread.sleep(300)
        assertNull("AC 27: mobile data only", manager.offer.value)
    }

    @Test fun acceptingTheStaleOfferRunsRoutingAndSearchOverMobileData() = runBlocking {
        installWithDates("2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
        network.set(validated = true, unmetered = false)
        manager.onForeground()
        eventually("offer") { manager.offer.value is PackOffer.Stale }
        val offer = manager.offer.value!!
        scheduler.enqueued.clear()
        manager.onOfferClosed(offer, accepted = true)
        eventually("enqueued") { scheduler.enqueued.isNotEmpty() }
        assertEquals("AC 28: no second confirmation", Enqueued(JobMode.STALE, true, 0), scheduler.enqueued.single())
        assertNull(manager.confirm.value)
    }

    // ------------------------------------------------------------ fakes

    data class Enqueued(val mode: JobMode, val metered: Boolean, val delay: Long)

    class FakeScheduler : PackScheduler {
        val enqueued = java.util.Collections.synchronizedList(ArrayList<Enqueued>())
        val userWork = MutableStateFlow<UserWork?>(null)
        @Volatile var cancelledUser = false
        @Volatile var periodic = false
        @Volatile var periodicCancelled = false
        override fun enqueueUser(mode: JobMode, allowMetered: Boolean, delayMs: Long) {
            enqueued += Enqueued(mode, allowMetered, delayMs)
            userWork.value = UserWork(running = false, allowMetered = allowMetered)
        }
        override fun cancelUser() {
            cancelledUser = true
            userWork.value = null
        }
        override fun userWork(): Flow<UserWork?> = userWork
        override fun ensurePeriodic() {
            periodic = true
        }
        override fun cancelPeriodic() {
            periodicCancelled = true
        }
    }

    class FakeNetwork : NetworkStateSource {
        private val v = MutableStateFlow(false)
        private val u = MutableStateFlow(false)
        override val validated: StateFlow<Boolean> = v
        override val unmetered: StateFlow<Boolean> = u
        override val newlyValidated: Flow<Unit> = emptyFlow()
        override fun isOnline(): Boolean = v.value
        fun set(validated: Boolean, unmetered: Boolean) {
            v.value = validated
            u.value = unmetered
        }
    }

    class FakeNotifier : PackNotifier {
        @Volatile var progressPosts = 0
        @Volatile var readies = 0
        @Volatile var fails = 0
        @Volatile var clears = 0
        override fun progress(percent: Int?) {
            progressPosts++
        }
        override fun waiting() = Unit
        override fun ready() {
            readies++
        }
        override fun failed(noSpaceToFree: Long?) {
            fails++
        }
        override fun clear() {
            clears++
        }
    }
}
