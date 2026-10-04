package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.android.FakeCamera
import mn.navmn.app.android.FakeGateway
import mn.navmn.app.android.FakeLocation
import mn.navmn.app.android.RecordingMapSurface
import mn.navmn.app.android.RecordingVoice
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.ui.AppViewModel
import mn.navmn.app.ui.MainActivity
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.time.Duration
import kotlin.math.PI
import kotlin.math.cos

/**
 * QA, NAV-005 section N at the Activity level (Robolectric API default, MapLibre replaced by `RecordingMapSurface`, fake
 * location and gateway; test plan `docs/qa/test-plans/NAV-005.md` §5a, cases TC-NA01…TC-NA05). Complements the mobile
 * engineer's `Nav005BrowseDotActivityTest` with cases it does not cover:
 * - TC-NA01 AC 78 / D177 (+ NAV-018 AC 8): the route origin «Миний байршил» is the raw good fix while the dot is held.
 * - TC-NA02 AC 76: poor fixes arriving every second do not keep the dot fresh (stale-timer wiring), no message.
 * - TC-NA03 AC 77 + AC 75: a single jump moves the follow camera 0 times; a confirmed relocation moves dot and camera.
 * - TC-NA04 AC 79: a > 2 min session through every state: 0 coordinates in logs **and app storage**, 0 requests, and a
 *   new Activity/ViewModel starts with no dot (state not restored).
 * - TC-NA05 AC 76 last sentence (AC 12 applies first): a my-location press with location services off shows the
 *   services-off message, never the 10 s wait or «Байршил тодорхойлж чадсангүй».
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class QaNav005BrowseDotActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    // Peace Ave near the State Department Store; distinct from other suites' points.
    private val home = LatLon(47.9152, 106.9045)
    private val zaisan = LatLon(47.8858, 106.9173)
    private val noFix = "Байршил тодорхойлж чадсангүй"
    private val servicesOff = "Байршил тогтоох үйлчилгээ унтарсан байна"
    private val coord = Regex("-?\\d{1,3}\\.\\d{4,}") // AC 67 / AC 79 regex

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
        FakeCamera.focuses.clear()
        ShadowLog.clear()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        hilt.inject()
    }

    @After
    fun tearDown() {
        scenario?.close()
        FakeLocation.reset()
    }

    // ------------------------------------------------------------------------------------------- helpers

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun vm(): AppViewModel {
        var v: AppViewModel? = null
        scenario!!.onActivity { a -> v = ViewModelProvider(a)[AppViewModel::class.java] }
        return v!!
    }

    private fun idle(ms: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun myLocationButton() = compose.onNode(hasContentDescription("Миний байршил") and !hasTestTag("route-preview"))
    private fun shown() = RecordingMapSurface.content?.myLocation

    /** QA's own local offset ([east] / [north] metres), independent of `mn.navmn.app.geo.Geo`. */
    private fun en(east: Double, north: Double, from: LatLon = home) = LatLon(
        from.lat + north / 6_371_008.8 * 180 / PI,
        from.lon + east / (6_371_008.8 * cos(from.lat * PI / 180)) * 180 / PI,
    )

    /** One browse fix now on the Robolectric monotonic clock, then 1 s of (looper) time: the 1 Hz browse cadence. */
    private fun emit(p: LatLon, acc: Double, speed: Double? = 0.0, thenIdleMs: Long = 1_000) {
        FakeLocation.mapFixes.tryEmit(Fix(p.lat, p.lon, acc, speedMps = speed, elapsedMs = SystemClock.elapsedRealtime(), wallTimeMs = System.currentTimeMillis()))
        idle(thenIdleMs)
    }

    private fun pressAndShow(p: LatLon = home, acc: Double = 5.0) {
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }
        emit(p, acc, 0.0, thenIdleMs = 50)
        waitFor { shown() == p }
    }

    // ------------------------------------------------------------------------------------------- TC-NA01

    /**
     * TC-NA01, AC 78 bullet 2 / D177 (AC 5, AC 9; NAV-018 AC 8): with the dot held at H, a newer raw good fix R (8 m away,
     * 5 m accuracy, speed 0: inside r = 10 m, so the dot stays at H) is the route origin «Миний байршил»; the preview map
     * keeps drawing the held dot H (the line may start some metres away, accepted by D177). The fresh-fix fallback is
     * pointed elsewhere, so an origin taken from it would also fail.
     */
    @Test
    fun tcNA01_routeOriginIsTheRawGoodFixWhileTheDotIsHeld() {
        FakeLocation.freshFix = { FakeLocation.goodFix(47.9400, 106.9900) } // not used if the raw map fix is taken
        launch()
        pressAndShow(home, 5.0)
        val raw = en(0.0, 8.0)
        emit(raw, 5.0, 0.0)
        assertEquals("AC 75: the dot is held at H", home, shown())
        RecordingRouteOrigin.longPressAndRoute(scenario!!, compose, zaisan, ::idle)
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        val origin = vm().preview.state.value!!.origin
        assertTrue("device start: $origin", origin is RoutePoint.MyLocation)
        val f = (origin as RoutePoint.MyLocation).fix
        assertEquals("D177: origin = raw fix (lat)", raw.lat, f.lat, 1e-9)
        assertEquals("D177: origin = raw fix (lon)", raw.lon, f.lon, 1e-9)
        assertEquals("S3 preview still draws the held dot (AC 74/78)", home, shown())
        assertEquals("exactly 1 route request", 1, FakeGateway.routeRequests.get())
    }

    // ------------------------------------------------------------------------------------------- TC-NA02

    /** TC-NA02, AC 76: poor fixes every second (101 m / not reported) do not reset the stale timer; stale with no message. */
    @Test
    fun tcNA02_poorFixesEverySecondDoNotKeepTheDotFresh() {
        launch()
        pressAndShow(home, 12.0)
        val t0 = SystemClock.elapsedRealtime()
        for (i in 1..9) emit(en(150.0, 40.0), if (i % 2 == 0) 101.0 else Double.NaN, 1.5)
        assertFalse("9 s: not stale yet", RecordingMapSurface.content!!.myLocationStale)
        assertEquals("poor fixes move the dot 0 m", home, shown())
        emit(en(150.0, 40.0), 150.0, 1.5) // 10 s
        emit(en(150.0, 40.0), 150.0, 1.5) // 11 s
        assertTrue("AC 76: stale ≤ 1 s after 10 s (${SystemClock.elapsedRealtime() - t0} ms)", RecordingMapSurface.content!!.myLocationStale)
        assertEquals("at the last shown position", home, shown())
        assertEquals("AC 74: circle kept, radius of the shown fix", 12.0, RecordingMapSurface.content!!.myLocationAccuracyM!!, 0.0)
        assertFalse("D173: no message", exists(noFix))
        emit(en(2.0, 2.0), 12.0, 0.0, thenIdleMs = 50)
        waitFor(2_000) { RecordingMapSurface.content?.myLocationStale == false }
        assertEquals("recovery inside r while standing: dot 0 m", home, shown())
    }

    // ------------------------------------------------------------------------------------------- TC-NA03

    /** TC-NA03, AC 77 + AC 75: one 300 m jump → dot and follow camera 0 m; a confirmed relocation → both move to K. */
    @Test
    fun tcNA03_jumpHeldThenConfirmedRelocationMovesDotAndCamera() {
        launch()
        pressAndShow(home, 10.0)
        waitFor { FakeCamera.focuses.any { it.p == home } }
        for (i in 1..3) emit(en(1.0, 1.0), 10.0, 0.0)
        val before = FakeCamera.focuses.size
        emit(en(300.0, 0.0), 10.0, 0.0) // jump J
        assertEquals("AC 77: dot 0 m", home, shown())
        emit(en(0.5, 0.5), 10.0, 0.0) // back: J discarded
        emit(en(1.0, 0.0), 10.0, 0.0)
        assertEquals("AC 77(a): dot still 0 m", home, shown())
        assertEquals("AC 75/77: follow camera moved 0 times", before, FakeCamera.focuses.size)
        // A real relocation: J2 and a confirming K within 1 s and 25 m.
        emit(en(0.0, -300.0), 10.0, 0.0)
        assertEquals("J2 pending", home, shown())
        val k = en(4.0, -302.0)
        emit(k, 10.0, 0.0, thenIdleMs = 50)
        waitFor(1_000) { shown() == k }
        waitFor(1_000) { FakeCamera.focuses.any { it.p == k } }
    }

    // ------------------------------------------------------------------------------------------- TC-NA04

    /**
     * TC-NA04, AC 79: a browse session of > 2 min through hold, a rejected jump, a hidden poor fix and the stale state.
     * Logcat (ShadowLog) **and** the app's data directory (files, shared_prefs, datastore, databases) hold 0 coordinates
     * (AC 67 regex); 0 network requests; a new Activity (new ViewModel) shows no dot before its first showable fix.
     */
    @Test
    fun tcNA04_sessionThroughEveryStateLeavesNoCoordinatesAndIsNotRestored() {
        launch()
        val start = SystemClock.elapsedRealtime()
        pressAndShow(home, 15.0)
        for (i in 1..40) emit(en((i % 5).toDouble(), (i % 3).toDouble()), 15.0, if (i % 2 == 0) null else 0.0) // hold
        emit(en(0.0, 400.0), 15.0, 0.0) // rejected jump
        emit(home, 15.0, 0.0)
        emit(home, 250.0, 0.0) // hidden poor fix
        idle(11_500) // stale
        assertTrue("stale reached", RecordingMapSurface.content!!.myLocationStale)
        for (i in 1..80) emit(en(1.4 * i, 0.0), 20.0, 1.4) // walking away: moving state
        assertTrue("session ≥ 2 min", SystemClock.elapsedRealtime() - start >= 120_000)

        val logHits = ShadowLog.getLogs().filter { coord.containsMatchIn(it.msg ?: "") }.map { "${it.tag}: ${it.msg}" }
        assertTrue("AC 79: logs hold no coordinates: ${logHits.take(5)}", logHits.isEmpty())
        val dataDir = File(app.applicationInfo.dataDir)
        val storedHits = dataDir.walkTopDown().filter { it.isFile && it.length() < 2_000_000 }.mapNotNull { f ->
            val text = runCatching { f.readText(Charsets.ISO_8859_1) }.getOrDefault("")
            coord.find(text)?.let { "${f.relativeTo(dataDir)}: ${it.value}" }
        }.toList()
        assertTrue("AC 79: app storage holds no coordinates: ${storedHits.take(5)}", storedHits.isEmpty())
        assertEquals("AC 79 / AC 65: 0 network requests", 0, FakeGateway.urls.size)

        scenario!!.close() // Activity finished → its ViewModel (and the filter) is cleared
        RecordingMapSurface.reset()
        launch()
        idle(500)
        assertNull("AC 79: state not restored, no dot before the first showable fix", shown())
        assertNull(vm().displayLocation.value)
    }

    // ------------------------------------------------------------------------------------------- TC-NA05

    /** TC-NA05, AC 76 last sentence / AC 12: services off → services-off message at once, no 10 s wait, no G5 card. */
    @Test
    fun tcNA05_servicesOffMyLocationPressShowsTheServicesMessageNotTheWait() {
        FakeLocation.servicesOn = false
        launch()
        myLocationButton().performClick()
        idle(300)
        assertTrue("AC 12: «$servicesOff» shown", exists(servicesOff))
        assertFalse("no location_searching wait", vm().ui.value.locating)
        idle(11_000)
        assertFalse("no «$noFix» after 10 s", exists(noFix))
        assertEquals("0 route requests", 0, FakeGateway.routeRequests.get())
    }
}

/** Opens the coordinate card at a point and taps «Маршрут гаргах» (as NAV-018 TC-A02's `previewWithDeviceStart`). */
private object RecordingRouteOrigin {
    fun longPressAndRoute(
        scenario: ActivityScenario<MainActivity>,
        compose: androidx.compose.ui.test.junit4.ComposeTestRule,
        p: LatLon,
        idle: (Long) -> Unit,
    ) {
        var ok = false
        scenario.onActivity { ok = RecordingMapSurface.longPress(p) }
        assertTrue("long-press handler present", ok)
        idle(100)
        compose.onNode(hasText("Маршрут гаргах")).performClick()
        idle(100)
    }
}
