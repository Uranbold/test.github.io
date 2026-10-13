package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.background.restore.RestoreStore
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.time.Duration
import javax.inject.Inject

/**
 * NAV-005 AC 101 (UX audit 2026-10-13 UX-01 (2), PO D240) at Activity level: the on-screen «Дуусгах» tears the session
 * down at once (AC 19 unchanged: engine gone, guidance listener removed, restore record deleted, 0 requests) and S1
 * shows the undo snackbar «Замчлал дууслаа» + «Буцаах» for 5 s; «Буцаах» restarts guidance on the kept route within
 * 1 s with no depart prompt and 0 requests, and one catch-up prompt follows the first good fix; the notification path
 * (GuidanceSession.end) opens no window; the window closes by itself after 5 s with nothing kept.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class Nav005UndoActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: GuidanceSession

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val recordDir get() = File(app.noBackupFilesDir, RestoreStore.DIR)
    private val route by lazy { (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route }
    private val trip = Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false)

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
        RecordingRequester.requests.clear()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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

    private fun idle(ms: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun exists(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** «Эхлэх» as the Activity does it (the preview is not needed for the panel): the engine, service and the S5 overlay. */
    private fun startGuidance() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        session.start(route, trip, FakeLocation.goodFix())
        waitFor { session.engine.value?.state?.value != null }
        waitFor { RecordingVoice.played.isNotEmpty() } // the depart prompt
        waitFor { exists(hasTestTag("nav-end")) }
        idle(300)
    }

    @Test
    fun onScreenEndTearsDownAtOnceAndOffersUndoWhichRestartsWithoutRequestsOrDepartPrompt() {
        startGuidance()
        val promptsBefore = RecordingVoice.played.size
        val stateBefore = session.engine.value!!.state.value!!
        assertTrue(stateBefore.banner is Banner.Maneuver)
        assertTrue("record written at «Эхлэх»", waitFor { RestoreStore(recordDir).exists() } >= 0)

        compose.onNodeWithTag("nav-end").performClick()
        // AC 19 unchanged: session gone, guidance listener removed, record deleted; S1 within 2 s.
        val endMs = waitFor(2_000) { session.engine.value == null }
        assertTrue("ended within $endMs ms", endMs <= 2_000)
        waitFor { FakeLocation.guidanceListeners.get() == 0 }
        assertFalse("restore record deleted at «Дуусгах» (AC 17; nothing of the undo copy is persisted)", RestoreStore(recordDir).exists())
        waitFor { exists(hasTestTag("nav-ended-snackbar")) }
        assertTrue("«Замчлал дууслаа»", exists(hasText("Замчлал дууслаа")))
        assertTrue("«Буцаах» button", exists(hasTestTag("nav-undo")))
        assertTrue("«Буцаах» label", exists(hasText("Буцаах")))
        assertTrue("S1 is shown (no «Дуусгах»)", !exists(hasTestTag("nav-end")))
        assertEquals("0 prompts during the window", promptsBefore, RecordingVoice.played.size)
        assertEquals("0 route requests", 0, FakeGateway.routeRequests.get())
        assertEquals(0, RecordingRequester.requests.size)

        compose.onNodeWithTag("nav-undo").performClick()
        val undoMs = waitFor(1_000) { session.engine.value?.state?.value != null }
        assertTrue("guidance back within $undoMs ms (AC 101: ≤ 1 s)", undoMs <= 1_000)
        waitFor { exists(hasTestTag("nav-end")) }
        assertFalse("snackbar gone", exists(hasTestTag("nav-ended-snackbar")))
        val restored = session.engine.value!!.state.value!!
        assertTrue("kept banner (the last manoeuvre, dimmed) while waiting for the first good fix: ${restored.banner}", restored.restoring && (restored.banner as Banner.Maneuver).stale)
        assertEquals((stateBefore.banner as Banner.Maneuver).key, (restored.banner as Banner.Maneuver).key)
        assertEquals("kept progress", stateBefore.progress.distanceRemaining, restored.progress.distanceRemaining, 0.0)
        assertFalse("no skeleton: the kept numbers show", exists(hasTestTag("nav-progress-skeleton")))
        assertEquals("no depart prompt on «Буцаах»", promptsBefore, RecordingVoice.played.size)
        assertEquals("0 route requests", 0, FakeGateway.routeRequests.get())
        assertEquals(0, RecordingRequester.requests.size)
        assertTrue("the live session has its restore record again (AC 16)", waitFor { RestoreStore(recordDir).exists() } >= 0)

        // The first good fix places the session on the kept route (fake navigator: step 0): one catch-up prompt at most.
        waitFor { FakeLocation.guidanceListeners.get() > 0 }
        FakeLocation.guidanceFixes.tryEmit(FakeLocation.goodFix(47.9180, 106.9170))
        waitFor { session.engine.value?.state?.value?.restoring == false }
        idle(3_500)
        val after = RecordingVoice.played.map { it.text }
        assertTrue("≤ 1 catch-up prompt after the first good fix: $after", after.size - promptsBefore <= 1)
        assertEquals("0 repeated prompts: $after", after.distinct().size, after.size)
        assertEquals(0, FakeGateway.routeRequests.get())
        assertEquals(0, RecordingRequester.requests.size)
        assertTrue(exists(hasContentDescription("Дуусгах")))
    }

    @Test
    fun theWindowClosesAfterFiveSecondsAndTheNotificationEndHasNoUndo() {
        startGuidance()
        compose.onNodeWithTag("nav-end").performClick()
        waitFor { exists(hasTestTag("nav-ended-snackbar")) }
        waitFor(7_000) { !exists(hasTestTag("nav-ended-snackbar")) }
        assertNull(session.engine.value)
        assertFalse("nothing to undo after the window", exists(hasTestTag("nav-undo")))

        // The notification / lock-screen path (GuidanceSession.end): AC 19 as before, no snackbar.
        session.start(route, trip, FakeLocation.goodFix())
        waitFor { session.engine.value?.state?.value != null }
        waitFor { exists(hasTestTag("nav-end")) }
        session.end()
        waitFor { session.engine.value == null }
        idle(500)
        assertFalse("no undo after the notification «Дуусгах» (Known limitations 12)", exists(hasTestTag("nav-ended-snackbar")))
        assertNotNull(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes())
    }
}
