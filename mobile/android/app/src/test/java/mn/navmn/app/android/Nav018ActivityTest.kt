package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import mn.navmn.app.geo.LatLon
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.points.MyLocationOption
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchView
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
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.time.Duration

/**
 * NAV-018 at the Activity level (Robolectric, fake gateway and location): the coordinate-card buttons during the
 * preview, the late-fix guard (ADR-0015 §4), swap, the «Миний байршил» option, rotation with 0 requests, and a new
 * preview starting from «Миний байршил» again (AC 2, 3, 6, 10, 11, 15, 32, 33).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class Nav018ActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val zaisan = LatLon(47.8858, 106.9173)
    private val pickup = LatLon(47.9213, 106.8948)

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
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
        FakeLocation.freshFixGate?.complete(Unit)
        scenario?.close()
        FakeLocation.reset()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun idle(ms: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
    }

    private fun vm(): AppViewModel {
        var v: AppViewModel? = null
        scenario!!.onActivity { a -> v = androidx.lifecycle.ViewModelProvider(a)[AppViewModel::class.java] }
        return v!!
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun longPress(p: LatLon) {
        var ok = false
        scenario!!.onActivity { ok = RecordingMapSurface.longPress(p) }
        assertTrue("long-press handler present", ok)
        idle()
    }

    private fun openPreviewTo(p: LatLon) {
        longPress(p)
        compose.onNode(hasText("Маршрут гаргах")).performClick()
        idle()
    }

    @Test
    fun aLateFixNeverOverwritesAStartChosenOnTheCoordinateCard() {
        FakeLocation.freshFixGate = CompletableDeferred()
        launch()
        openPreviewTo(zaisan)
        assertEquals("waiting for the first fix: no request yet", 0, FakeGateway.routeRequests.get())
        // AC 6: long-press during the preview → «Эхлэх цэг болгох».
        longPress(pickup)
        assertTrue(tagged("coord-set-origin"))
        assertFalse(exists("Маршрут гаргах"))
        compose.onNodeWithTag("coord-set-origin").performClick()
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        val s = vm().preview.state.value!!
        assertEquals(RoutePoint.MapPoint(pickup), s.origin)
        assertFalse("the card closed", tagged("coordinate-card"))
        // The fix arrives late (ADR-0015 §4): ignored, 0 requests.
        FakeLocation.freshFixGate!!.complete(Unit)
        idle(500)
        assertEquals(RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)
        assertEquals(1, FakeGateway.routeRequests.get())
        // AC 15: «Эхлэх» disabled with O1; the chosen start uses the start marker (map-style §7.7).
        assertTrue(exists("Замчлал зөвхөн таны байршлаас эхэлнэ"))
        assertTrue(compose.onNodeWithTag("nav-start").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) != null)
        assertEquals(pickup, RecordingMapSurface.content?.origin)
        assertEquals("Сонгосон цэг", compose.onNodeWithTag("route-origin").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        // AC 33: rotation keeps the points with 0 requests.
        scenario!!.recreate()
        idle(300)
        assertEquals(RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)
        assertEquals(1, FakeGateway.routeRequests.get())
    }

    @Test
    fun swapAndMyLocationOptionEachSendOneRequest() {
        launch()
        openPreviewTo(zaisan)
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        assertTrue("AC 1: device start", vm().preview.state.value!!.origin is RoutePoint.MyLocation)
        assertNull(RecordingMapSurface.content?.origin)
        compose.onNodeWithTag("route-swap").performClick()
        waitFor { FakeGateway.routeRequests.get() == 2 && vm().preview.state.value?.result is PreviewResult.Route }
        assertTrue("AC 11: «Миний байршил» moved to the destination", vm().preview.state.value!!.destination is RoutePoint.MyLocation)
        assertFalse(vm().preview.state.value!!.canStart)
        // AC 3: the start editor offers «Миний байршил»; choosing it sends one request and enables «Эхлэх». The device has
        // moved since (at the same fix both points would be within 10 m: «Эхлэх цэг, очих газар ижил байна», AC 9).
        FakeLocation.freshFix = { FakeLocation.goodFix(47.9300, 106.9500) }
        compose.onNodeWithTag("route-origin").performClick()
        idle()
        assertTrue(tagged("point-editor"))
        compose.onNodeWithTag("point-option-my-location").performClick()
        waitFor { FakeGateway.routeRequests.get() == 3 && vm().preview.state.value?.result is PreviewResult.Route }
        assertFalse("the editor closed", tagged("point-editor"))
        assertTrue(vm().preview.state.value!!.origin is RoutePoint.MyLocation)
        assertTrue(vm().preview.state.value!!.canStart)
    }

    @Test
    fun searchResultSetsTheStartAndANewPreviewStartsFromMyLocationAgain() {
        launch()
        openPreviewTo(zaisan)
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        val v = vm()
        scenario!!.onActivity {
            v.openPointEditor(PointSide.ORIGIN)
            v.onPointResult(PlaceDisplay.info(PhotonFeature(pickup, mapOf("osm_key" to "amenity", "osm_value" to "place_of_worship", "name" to "Гандантэгчинлэн хийд"))), "Гандантэгчинлэн хийд")
        }
        waitFor { FakeGateway.routeRequests.get() == 2 && vm().preview.state.value?.result is PreviewResult.Route }
        assertEquals(RoutePoint.Place(pickup, "Гандантэгчинлэн хийд"), vm().preview.state.value!!.origin)
        assertNull(vm().points.value.editor)
        // Leaving the editor without a choice changes nothing (AC 7).
        scenario!!.onActivity {
            v.openPointEditor(PointSide.DESTINATION)
            v.closePointEditor()
        }
        idle()
        assertEquals(2, FakeGateway.routeRequests.get())
        // AC 32: «Хаах», then a new preview: the chosen start is not kept.
        compose.onNodeWithTag("route-close").performClick()
        idle()
        assertNull(vm().preview.state.value)
        openPreviewTo(zaisan)
        waitFor { FakeGateway.routeRequests.get() == 3 && vm().preview.state.value?.result is PreviewResult.Route }
        assertTrue(vm().preview.state.value!!.origin is RoutePoint.MyLocation)
    }

    private fun searches() = FakeGateway.paths.count { it == "/v1/search" }
    private fun reverses() = FakeGateway.paths.count { it == "/v1/reverse" }

    /** Simulates leaving to the system Settings and coming back (onPause/onStop → onStart/onResume). */
    private fun leaveAndResume() {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        idle()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        idle(500)
    }

    /**
     * Review fix (AC 10, ADR-0015 §4): «Миний байршил» in the start editor fails with location services off, so the
     * PREVIEW_ORIGIN action stays pending. Leaving the editor (Back) must end that attempt: after services are switched
     * on and the app resumes, the chosen start stays and 0 route requests are sent.
     */
    @Test
    fun aFailedMyLocationAttemptLeftWithBackNeverReplacesTheStartOnResume() {
        launch()
        openPreviewTo(zaisan)
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        longPress(pickup)
        compose.onNodeWithTag("coord-set-origin").performClick()
        waitFor { FakeGateway.routeRequests.get() == 2 && vm().preview.state.value?.result is PreviewResult.Route }
        assertEquals(RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)

        FakeLocation.servicesOn = false
        FakeLocation.freshFix = { FakeLocation.goodFix(47.9300, 106.9500) }
        compose.onNodeWithTag("route-origin").performClick()
        idle()
        compose.onNodeWithTag("point-option-my-location").performClick()
        idle(300)
        assertTrue("AC 3: failure in the option card", vm().points.value.editor?.myLocation is MyLocationOption.Failed)
        assertEquals(2, FakeGateway.routeRequests.get())

        val v = vm()
        scenario!!.onActivity { v.closePointEditor() } // Back
        idle()
        assertNull(vm().points.value.editor)

        FakeLocation.servicesOn = true
        leaveAndResume()
        idle(1_000)
        assertEquals("the chosen start stays", RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)
        assertEquals("0 route requests after resume", 2, FakeGateway.routeRequests.get())
    }

    /**
     * AC 5 second bullet (D140/D145, ADR-0012 Amendment A3): a typed pair in the start editor shows the «Сонгосон цэг»
     * option; choosing it sets the start to the coordinate, closes the editor, opens no card, and sends 0 `search`,
     * 0 `reverse` and exactly 1 route request.
     */
    @Test
    fun typedCoordinateInTheStartFieldSetsTheStartWithNoSearchAndNoCard() {
        launch()
        openPreviewTo(zaisan)
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        val reversesBefore = reverses()
        compose.onNodeWithTag("route-origin").performClick()
        idle()
        assertTrue(tagged("point-editor"))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("point-editor"))).performTextReplacement("47.9213, 106.8948")
        waitFor { vm().fieldSearch.view.value is SearchView.Coordinate }
        idle(500)
        assertEquals("one option in the field list", 1, compose.onAllNodesWithTag("search-coordinate-option").fetchSemanticsNodes().size)
        assertEquals("0 search requests for the pair", 0, searches())
        compose.onNodeWithTag("search-coordinate-option").performClick()
        waitFor { FakeGateway.routeRequests.get() == 2 && vm().preview.state.value?.result is PreviewResult.Route }
        assertEquals(RoutePoint.TypedCoordinate(pickup), vm().preview.state.value!!.origin)
        assertNull("the editor closed", vm().points.value.editor)
        assertEquals(SearchView.Closed, vm().fieldSearch.view.value)
        assertNull("no coordinate card", vm().ui.value.card)
        assertFalse(tagged("coordinate-card"))
        assertEquals("0 search", 0, searches())
        assertEquals("0 reverse", reversesBefore, reverses())
        assertEquals("Сонгосон цэг", compose.onNodeWithTag("route-origin").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        idle(500)
        assertEquals("exactly one route request", 2, FakeGateway.routeRequests.get())
    }
}
