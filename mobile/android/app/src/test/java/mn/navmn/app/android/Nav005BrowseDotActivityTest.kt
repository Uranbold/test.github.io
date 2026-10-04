package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
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
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.settings.ThemeChoice
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
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
import java.time.Duration
import javax.inject.Inject

/**
 * NAV-005 section N (AC 71 additions): the browse location dot through the real Activity, ViewModel and [NavRoot] with
 * MapLibre replaced by [RecordingMapSurface] (the rendered circle is device-only, AC 73 / D178). Checks what reaches the
 * map ([mn.navmn.app.map.MapContent]: position, accuracy, stale flag), the follow camera ([FakeCamera.focuses]), the
 * AC 76 my-location wait and «Байршил тодорхойлж чадсангүй», the D30 search bias (D174), guidance (no dot), a theme
 * switch, and the AC 79 log scan.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class Nav005BrowseDotActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: GuidanceSession

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val base = LatLon(47.9500, 106.8000)
    private val noFix = "Байршил тодорхойлж чадсангүй"

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
        if (session.engine.value != null) {
            session.end()
            waitFor { session.engine.value == null }
        }
        scenario?.close()
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

    private fun waitFor(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle() // recomposition (the recording surface sees MapContent only after a frame)
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun myLocationButton() = compose.onNode(hasContentDescription("Миний байршил") and !hasTestTag("route-preview"))

    /** One 1 Hz browse fix at [p], timed now on the shared (Robolectric) monotonic clock. */
    private fun emit(p: LatLon, acc: Double = 20.0, speed: Double? = 0.0) {
        FakeLocation.mapFixes.tryEmit(Fix(p.lat, p.lon, acc, speedMps = speed, elapsedMs = SystemClock.elapsedRealtime(), wallTimeMs = 0L))
        idle(50)
    }

    private fun content() = RecordingMapSurface.content

    private fun pressAndShowFirstFix(acc: Double = 20.0) {
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }
        emit(base, acc)
        waitFor { content()?.myLocation == base }
    }

    // ------------------------------------------------------------------------------------------- AC 74, 75

    @Test
    fun ac74_dotAndCircleOfTheShownFixReachTheMapAndTheCameraFollowsIt() {
        launch()
        pressAndShowFirstFix(acc = 30.0)
        assertEquals("AC 74: circle radius = accuracy of the shown fix", 30.0, content()!!.myLocationAccuracyM!!, 0.0)
        assertFalse(content()!!.myLocationStale)
        waitFor { FakeCamera.focuses.any { it.p == base } }
    }

    @Test
    fun ac75_standingStillMovesTheDotAndTheFollowingCameraZero() {
        launch()
        pressAndShowFirstFix(acc = 25.0)
        waitFor { FakeCamera.focuses.any { it.p == base } }
        val moves = FakeCamera.focuses.size
        for (i in 1..20) {
            emit(Geo.offset(base, i * 37.0, 15.0), acc = 25.0, speed = if (i % 2 == 0) null else 0.0)
            idle(950)
        }
        assertEquals("the dot is held", base, content()!!.myLocation)
        assertEquals("the circle keeps the shown fix's accuracy", 25.0, content()!!.myLocationAccuracyM!!, 0.0)
        assertEquals("AC 75: a held dot moves the camera 0 times", moves, FakeCamera.focuses.size)
        // Walking (speed reported) releases the hold and the camera follows the new shown position.
        val walk = Geo.offset(base, 90.0, 3.0)
        emit(walk, acc = 20.0, speed = 1.4)
        waitFor { content()?.myLocation == walk && FakeCamera.focuses.any { it.p == walk } }
    }

    @Test
    fun ac76_poorFirstFixesShowNoDotAndASingleJumpIsHeld() {
        launch()
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }
        emit(base, acc = 150.0)
        emit(base, acc = Double.NaN)
        assertNull("AC 76: no dot before the first showable fix", content()?.myLocation)
        assertNull(content()?.myLocationAccuracyM)
        emit(base, acc = 10.0)
        waitFor { content()?.myLocation == base }
        idle(1_000)
        emit(Geo.offset(base, 45.0, 300.0), acc = 10.0) // AC 77: 300 m in 1 s
        assertEquals("AC 77: the dot moves 0 m", base, content()!!.myLocation)
    }

    // ------------------------------------------------------------------------------------------- AC 76 stale

    @Test
    fun ac76_staleAfterTenSecondsWithNoMessageAndNormalAgainOnTheNextFix() {
        launch()
        pressAndShowFirstFix()
        idle(9_500)
        assertFalse("not stale before 10 s", content()!!.myLocationStale)
        idle(1_000)
        assertTrue("AC 76: stale within 1 s of the 10 s mark", content()!!.myLocationStale)
        assertEquals("at the last shown position", base, content()!!.myLocation)
        assertFalse("D173: no message for the stale dot", exists(noFix))
        emit(Geo.offset(base, 0.0, 4.0))
        waitFor { content()?.myLocationStale == false }
        assertEquals("recovery does not move a held dot", base, content()!!.myLocation)
    }

    @Test
    fun ac76_pressWithAStaleDotCentresOnItWithNoMessage() {
        launch()
        pressAndShowFirstFix()
        vm().stopFollowingMe() // a map gesture
        idle(11_000)
        assertTrue(content()!!.myLocationStale)
        FakeCamera.focuses.clear()
        myLocationButton().performClick()
        idle(200)
        waitFor { FakeCamera.focuses.any { it.p == base } }
        idle(11_000)
        assertFalse("no message", exists(noFix))
    }

    // ------------------------------------------------------------------------------------------- AC 76 my-location wait

    @Test
    fun ac76_myLocationPressWithNoShowableFixShowsTheMessageAfterTenSeconds() {
        launch()
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }
        assertTrue("waiting: location_searching", vm().ui.value.locating)
        assertTrue(vm().ui.value.followingMe)
        emit(base, acc = 200.0) // not showable
        idle(9_000)
        assertFalse("no message before 10 s", exists(noFix))
        idle(1_200)
        waitFor { exists(noFix) }
        assertFalse(vm().ui.value.locating)
        assertFalse("the button returns to normal colours", vm().ui.value.followingMe)
        assertEquals("AC 76: 0 route requests", 0, FakeGateway.routeRequests.get())
        assertEquals("0 extra location requests", 1, FakeLocation.mapRegistrations.get())
        // A showable fix while the card is shown: it closes by itself, the dot appears, the camera centres and follows.
        emit(base, acc = 30.0)
        waitFor { !exists(noFix) && content()?.myLocation == base && FakeCamera.focuses.any { it.p == base } }
        assertTrue(vm().ui.value.followingMe)
    }

    @Test
    fun ac76_closeEndsTheWaitAndALaterFixDoesNotMoveTheCamera() {
        launch()
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }
        idle(10_300)
        waitFor { exists(noFix) }
        compose.onNode(hasText("Хаах")).performClick()
        idle()
        assertFalse(exists(noFix))
        emit(base, acc = 30.0)
        waitFor { content()?.myLocation == base }
        idle(500)
        assertTrue("«Хаах»: a later fix shows the dot without a camera move", FakeCamera.focuses.none { it.p == base })
    }

    @Test
    fun ac76_retryRestartsTheWaitAndAGestureCancelsIt() {
        launch()
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }
        idle(10_300)
        waitFor { exists(noFix) }
        compose.onNode(hasText("Дахин оролдох")).performClick()
        idle()
        assertFalse("the card closes", exists(noFix))
        assertTrue("the wait starts again", vm().ui.value.locating)
        vm().stopFollowingMe() // a map gesture during the wait
        idle(11_000)
        assertFalse("a gesture cancels the wait: no card later", exists(noFix))
        assertFalse(vm().ui.value.locating)
    }

    // ------------------------------------------------------------------------------------------- AC 78 consumers

    @Test
    fun ac78_searchBiasUsesTheShownPositionNotTheRawFix() {
        launch()
        pressAndShowFirstFix(acc = 80.0)
        // A raw fix ~60 m north (inside r = 80 m while standing): held; the raw position rounds to another lat (47.951).
        val raw = Geo.offset(base, 0.0, 60.0)
        emit(raw, acc = 80.0)
        assertEquals(base, content()!!.myLocation)
        assertEquals("the raw fix is still the origin input", raw.lat, vm().myLocation.value!!.lat, 1e-9)
        vm().onQuery("Зайсан")
        waitFor { FakeGateway.urls.any { it.encodedPath.endsWith("/search") } }
        val url = FakeGateway.urls.last { it.encodedPath.endsWith("/search") }
        assertEquals("D174: bias = shown position (3 decimals)", "47.950", url.queryParameter("lat"))
        assertEquals("106.800", url.queryParameter("lon"))
    }

    @Test
    fun ac74_noDotOrCircleDuringGuidanceAndNoStalePreGuidanceDotAfterwards() {
        launch()
        pressAndShowFirstFix()
        val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
        session.start(route, Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false), FakeLocation.goodFix())
        waitFor { FakeLocation.guidanceListeners.get() == 1 }
        FakeLocation.guidanceFixes.tryEmit(FakeLocation.goodFix())
        waitFor(5_000) { content()?.guidance == true }
        assertNull("AC 74: no dot during guidance", content()!!.myLocation)
        assertNull("AC 74: no circle during guidance", content()!!.myLocationAccuracyM)
        assertNull("§9.3: the browse filter is reset when guidance starts", vm().displayLocation.value)
        session.end()
        waitFor { session.engine.value == null && content()?.guidance == false }
        idle(300)
        assertNull("§9.3: after guidance there is no dot until the first showable fix", content()!!.myLocation)
        waitFor { FakeLocation.mapListeners.get() == 1 }
        emit(base)
        waitFor { content()?.myLocation == base }
    }

    @Test
    fun ac74_themeSwitchKeepsTheDotAndCircleWithZeroRequests() {
        launch()
        pressAndShowFirstFix(acc = 40.0)
        val requests = FakeGateway.urls.size
        // The recording surface stands in for MapLibre (the real style reload and layer re-add are device-only, AC 73):
        // the night flavour reaches the map with the same dot content.
        vm().setTheme(ThemeChoice.NIGHT)
        waitFor { vm().theme.value == ThemeChoice.NIGHT }
        idle(1_000)
        assertEquals(base, content()!!.myLocation)
        assertEquals(40.0, content()!!.myLocationAccuracyM!!, 0.0)
        assertEquals("0 network requests", requests, FakeGateway.urls.size)
    }

    // ------------------------------------------------------------------------------------------- AC 79

    @Test
    fun ac79_aBrowseSessionThroughEveryStateLogsNoCoordinates() {
        launch()
        pressAndShowFirstFix()
        for (i in 1..30) { // hold
            emit(Geo.offset(base, i * 23.0, 8.0))
            idle(950)
        }
        emit(Geo.offset(base, 45.0, 400.0)) // rejected jump
        idle(950)
        emit(base, acc = 300.0) // hidden poor fix
        idle(12_000) // stale
        assertTrue(content()!!.myLocationStale)
        for (i in 1..80) { // more than 2 min in total
            emit(Geo.offset(base, i * 11.0, 5.0))
            idle(950)
        }
        val needles = Regex("-?\\d{1,3}\\.\\d{4,}")
        val hits = ShadowLog.getLogs().filter { needles.containsMatchIn(it.msg ?: "") }.map { "${it.tag}: ${it.msg}" }
        assertTrue("AC 79: logs hold no coordinates: $hits", hits.isEmpty())
        assertEquals("AC 79: 0 network requests from the filter", 0, FakeGateway.urls.size)
    }
}
