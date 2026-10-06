package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.background.restore.RestoreCodec
import mn.navmn.app.background.restore.RestoreDestination
import mn.navmn.app.background.restore.RestoreMeta
import mn.navmn.app.background.restore.RestoreStore
import mn.navmn.app.background.restore.RouteSource
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.ui.MainActivity
import mn.navmn.app.ui.screens.BannerVariant
import mn.navmn.app.ui.screens.StatusKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import javax.inject.Inject

/**
 * NAV-012 at Activity level (Robolectric, MapLibre replaced by [RecordingMapSurface]): opening the app with an
 * interrupted session restores guidance without «Эхлэх» (AC 18): restoring banner, «Замчлал сэргэлээ», no depart prompt,
 * 0 requests; an expired record opens the map screen silently (AC 21); the S7 battery row (AC 28).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w412dp-h915dp")
class Nav012ActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: GuidanceSession

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val recordDir get() = File(app.noBackupFilesDir, RestoreStore.DIR)

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
        RecordingRequester.requests.clear()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        // A validated network before NetworkMonitor is created (the preview needs it).
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        hilt.inject()
        RestoreStore(recordDir).delete()
    }

    @After
    fun tearDown() {
        if (session.engine.value != null) {
            session.end()
            waitFor { session.engine.value == null }
        }
        scenario?.close()
        RestoreStore(recordDir).delete()
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun writeRecord(heartbeat: Long, routeSource: String = RouteSource.GATEWAY) {
        val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
        val bytes = route.source!!
        RestoreStore(recordDir).writeAll(
            RestoreMeta(
                schema = RestoreMeta.SCHEMA,
                destination = RestoreDestination(47.8858, 106.9173, "Зайсан"),
                costing = "auto",
                avoidUnpaved = false,
                language = "mn",
                startedAtWallMs = heartbeat - 120_000,
                heartbeatWallMs = heartbeat,
                routeSha256 = RestoreCodec.sha256(bytes),
                routeSource = routeSource,
            ),
            bytes,
        )
    }

    private fun exists(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun openingTheAppRestoresGuidanceWithTheNoticeAndNoDepartPrompt() {
        writeRecord(System.currentTimeMillis() - 5 * 60_000L)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val ms = waitFor { session.engine.value?.state?.value != null }
        assertTrue("guidance within $ms ms (AC 18: ≤ 3 s)", ms <= 3_000)
        compose.waitForIdle()
        assertEquals(Banner.Restoring, session.engine.value!!.state.value!!.banner)
        waitFor { exists(SemanticsMatcher.expectValue(BannerVariant, "restoring")) }
        assertTrue("«Замчлал сэргэлээ»", exists(SemanticsMatcher.expectValue(StatusKind, "resumed")))
        assertTrue(exists(hasTestTag("nav-progress-skeleton")))
        assertTrue(exists(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Замчлал сэргэлээ")))))
        // AC 18: no depart prompt; AC 19/20: 0 route requests before the first fix.
        assertTrue(RecordingVoice.played.isEmpty())
        assertEquals(0, RecordingRequester.requests.size)
        assertEquals(0, FakeGateway.routeRequests.get())
        // The record was rewritten with this restore counted (AC 24 loop limit).
        waitFor { RestoreCodec.decode(File(recordDir, RestoreStore.META).readBytes())?.restoresWallMs?.size == 1 }
    }

    /**
     * NAV-012 AC 54 (change 7c, the known gap): a record whose route came from the device restores with the OF24 icon in
     * the trip progress panel after the first good fix (none in the skeleton); a gateway record shows none.
     */
    @Test
    fun restoredDeviceRouteShowsTheOfflineIconAfterTheFirstGoodFixAndAGatewayRouteNone() {
        for (source in listOf(RouteSource.DEVICE, RouteSource.GATEWAY)) {
            writeRecord(System.currentTimeMillis() - 5 * 60_000L, source)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitFor { session.engine.value?.state?.value != null }
            compose.waitForIdle()
            waitFor { exists(hasTestTag("nav-progress-skeleton")) }
            assertFalse("no icon in the skeleton ($source)", exists(hasTestTag("offline-indicator-icon")))
            assertEquals(source == RouteSource.DEVICE, session.engine.value!!.state.value!!.onDeviceRoute)
            // The first good fix places the session on the stored route (fake navigator: step 0, 0 requests).
            waitFor { FakeLocation.guidanceListeners.get() > 0 }
            FakeLocation.guidanceFixes.tryEmit(FakeLocation.goodFix(47.9180, 106.9170))
            waitFor { session.engine.value?.state?.value?.restoring == false }
            compose.waitForIdle()
            waitFor { exists(hasTestTag("nav-remaining")) }
            assertEquals("icon for a $source route", source == RouteSource.DEVICE, exists(hasTestTag("offline-indicator-icon")))
            assertEquals(0, FakeGateway.routeRequests.get())
            // The rewritten record keeps the route source (AC 16, 54).
            waitFor { RestoreCodec.decode(File(recordDir, RestoreStore.META).readBytes())?.restoresWallMs?.size == 1 }
            assertEquals(source, RestoreCodec.decode(File(recordDir, RestoreStore.META).readBytes())!!.routeSource)
            session.end()
            waitFor { session.engine.value == null }
            scenario?.close()
            scenario = null
            compose.waitForIdle()
        }
    }

    /**
     * NAV-005 AC 88, 89 (section P) during guidance: «Тохиргоо» (the progress-panel gear) → «Лиценз» → an entry → Back →
     * Back returns to S7 with the row in view; guidance keeps running and 0 requests of any kind are sent.
     */
    @Test
    fun licencesPageFromSettingsDuringGuidanceKeepsGuidanceAndSendsNothing() {
        writeRecord(System.currentTimeMillis() - 60_000L)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor { session.engine.value?.state?.value != null }
        compose.waitForIdle()
        val before = FakeGateway.paths.size
        compose.onNodeWithTag("nav-settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("licences-row").performScrollTo()
        compose.onNodeWithTag("licences-row").performClick()
        val ms = waitFor { exists(hasTestTag("licence-entry-ferrostar")) }
        assertTrue("S9 within $ms ms (AC 88: ≤ 1 s on a phone; Robolectric is slower)", ms <= 3_000)
        assertTrue(exists(hasTestTag("licences-notice")))
        compose.onNodeWithTag("licence-entry-ferrostar").performClick()
        waitFor { exists(hasTestTag("licence-paragraph")) }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists(hasTestTag("licences-list")) && !exists(hasTestTag("licences-detail")) }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists(hasTestTag("licences-row")) }
        assertFalse(exists(hasTestTag("licences-list")))
        assertTrue("guidance continues (AC 88)", session.engine.value?.state?.value != null)
        assertEquals("0 requests while the licences page was open (AC 89)", before, FakeGateway.paths.size)
        assertEquals(0, RecordingRequester.requests.size)
    }

    @Test
    fun expiredRecordOpensTheMapScreenSilently() {
        writeRecord(System.currentTimeMillis() - 31 * 60_000L)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor { !recordDir.exists() }
        compose.waitForIdle()
        assertNull(session.engine.value)
        assertFalse(exists(hasTestTag("nav-banner")))
        assertEquals(0, FakeGateway.routeRequests.get())
    }

    /** AC 26 / Layout rule 10: entry row in the collapsed sheet, full hint when expanded, «Хаах» hides both. */
    @Test
    fun batteryEntryRowThenHintOnThePreviewAndCloseHidesIt() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        var ok = false
        scenario!!.onActivity { ok = RecordingMapSurface.longPress(mn.navmn.app.geo.LatLon(47.8858, 106.9173)) }
        assertTrue(ok)
        waitFor { compose.onAllNodes(androidx.compose.ui.test.hasText("Маршрут гаргах"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().also { if (!it) compose.waitForIdle() } }
        compose.onNode(androidx.compose.ui.test.hasText("Маршрут гаргах")).performClick()
        // The app is restricted by default under Robolectric (isIgnoringBatteryOptimizations = false).
        waitFor(5_000) { compose.waitForIdle(); exists(hasTestTag("battery-entry")) }
        assertFalse("collapsed: no full hint", exists(hasTestTag("battery-hint")))
        compose.onNodeWithTag("battery-entry").performClick()
        waitFor { compose.waitForIdle(); exists(hasTestTag("battery-hint")) }
        compose.onNodeWithTag("battery-hint-close", useUnmergedTree = true).performClick()
        waitFor { compose.waitForIdle(); !exists(hasTestTag("battery-hint")) && !exists(hasTestTag("battery-entry")) }
    }

    @Test
    fun settingsSheetHasTheBatteryRow() {
        writeRecord(System.currentTimeMillis() - 60_000L)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor { session.engine.value?.state?.value != null }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-battery").performScrollTo()
        assertTrue(exists(hasTestTag("settings-battery-open")))
    }
}
