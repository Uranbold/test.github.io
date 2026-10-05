package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.android.FakeGateway
import mn.navmn.app.android.FakeLocation
import mn.navmn.app.android.RecordingMapSurface
import mn.navmn.app.android.RecordingVoice
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.points.MyLocationOption
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.search.SearchView
import mn.navmn.app.settings.ThemeChoice
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

/**
 * QA, NAV-018 at the Activity level (Robolectric, fake gateway and location; test plan §4):
 * - TC-A01 AC 3 / AC 10 / ADR-0015 §4: a failed «Миний байршил» attempt that the user left must not set the start later
 *   when location becomes available and the app resumes (back from Settings).
 * - TC-A02 AC 10: with a device start, new fixes, services off/on and a resume change nothing and send 0 requests.
 * - TC-A03 AC 15, 18, 22, 24, 31, 33: a chosen-start session: the disabled «Эхлэх» asks for nothing; the turn list, a row
 *   tap, a language and theme switch send 0 requests; labels switch; no coordinates in logs or app storage.
 * - TC-A04 AC 5 bullet 2 (D140/D145), AC 3, 11, 15, 31, 33: a typed pair in the **destination** field (run 2).
 * - TC-A05 AC 5 bullet 2 (D140: the option needs no request, so it also shows offline): typed pair in the start field
 *   while offline (run 2).
 * - TC-A06 AC 5 (D140: pairs the ADR-0006 rule rejects are still searched as typed text) (run 2).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class QaNav018ActivityTest {
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

    private fun onVm(block: (AppViewModel) -> Unit) {
        val v = vm()
        scenario!!.onActivity { block(v) }
        idle()
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun longPress(p: LatLon) {
        var ok = false
        scenario!!.onActivity { ok = RecordingMapSurface.longPress(p) }
        assertTrue("long-press handler present", ok)
        idle()
    }

    /** Opens the preview to [zaisan] from the coordinate card and waits for the device-start route (1 request). */
    private fun previewWithDeviceStart() {
        longPress(zaisan)
        compose.onNode(hasText("Маршрут гаргах")).performClick()
        idle()
        waitFor { FakeGateway.routeRequests.get() == 1 && vm().preview.state.value?.result is PreviewResult.Route }
        assertTrue("AC 1: device start", vm().preview.state.value!!.origin is RoutePoint.MyLocation)
    }

    /** AC 6: long-press during the preview, «Эхлэх цэг болгох» → chosen start [pickup] (one more request). */
    private fun chooseStartOnTheCard() {
        val before = FakeGateway.routeRequests.get()
        longPress(pickup)
        compose.onNodeWithTag("coord-set-origin").performClick()
        idle()
        waitFor { FakeGateway.routeRequests.get() == before + 1 && vm().preview.state.value?.result is PreviewResult.Route }
        assertEquals(RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)
    }

    /** Simulates leaving to the system Settings and coming back (Activity onPause/onStop → onStart/onResume). */
    private fun leaveAndResume() {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        idle()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        idle(500)
    }

    /**
     * TC-A01 (AC 3, AC 10, ADR-0015 §4 "onResume never overwrites a start"). A chosen start is set. The user opens the
     * start editor and picks «Миний байршил» while location services are off: the option card shows the failure and the
     * start stays (AC 3). The user then leaves the editor with Back (AC 7: nothing changes). Later location is switched on
     * and the app resumes. Expected: the chosen start stays and 0 route requests are sent (AC 10: a permission or GPS
     * change while the preview is open changes only «Эхлэх», never the start).
     */
    @Test
    fun tcA01_aFailedMyLocationAttemptThatWasLeftNeverSetsTheStartOnResume() {
        launch()
        previewWithDeviceStart()
        chooseStartOnTheCard()
        assertEquals(2, FakeGateway.routeRequests.get())

        FakeLocation.servicesOn = false
        FakeLocation.freshFix = { FakeLocation.goodFix(47.9300, 106.9500) } // the device is elsewhere now
        compose.onNodeWithTag("route-origin").performClick()
        idle()
        assertTrue(tagged("point-editor"))
        compose.onNodeWithTag("point-option-my-location").performClick()
        idle(300)
        assertTrue("AC 3: the failure is shown in the option card", vm().points.value.editor?.myLocation is MyLocationOption.Failed)
        assertEquals("AC 3: the start stays", RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)
        assertEquals("AC 3: 0 requests on failure", 2, FakeGateway.routeRequests.get())

        onVm { it.closePointEditor() } // Back (NavRoot: points.editor != null → closePointEditor)
        assertNull(vm().points.value.editor)

        FakeLocation.servicesOn = true // the user switches location on in the system settings
        leaveAndResume()
        idle(1_000)
        assertEquals(
            "AC 10 / ADR-0015 §4: the chosen start is not overwritten after the abandoned attempt",
            RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin,
        )
        assertEquals("AC 10: 0 route requests", 2, FakeGateway.routeRequests.get())
    }

    /** TC-A02 (AC 10): device start; fixes, services off/on and resume change nothing, 0 requests. */
    @Test
    fun tcA02_aDeviceStartIsFrozenWhenSet() {
        launch()
        previewWithDeviceStart()
        val start = vm().preview.state.value!!.origin
        for (i in 1..5) FakeLocation.mapFixes.tryEmit(FakeLocation.goodFix(47.9189 + i * 0.001, 106.9176))
        idle(500)
        FakeLocation.servicesOn = false
        leaveAndResume()
        FakeLocation.servicesOn = true
        leaveAndResume()
        assertEquals("AC 10: the start is fixed when it is set", start, vm().preview.state.value!!.origin)
        assertEquals("AC 10: 0 route requests", 1, FakeGateway.routeRequests.get())
        assertTrue(vm().preview.state.value!!.result is PreviewResult.Route)
    }

    /** TC-A03 (AC 15, 18, 22, 24, 31, 33): a chosen-start session. */
    @Test
    fun tcA03_chosenStartSessionSendsNothingExtraAndLeavesNoCoordinates() {
        launch()
        previewWithDeviceStart()
        chooseStartOnTheCard()
        val requests = FakeGateway.routeRequests.get()

        // AC 15: the disabled «Эхлэх» starts nothing, asks for nothing, sends nothing.
        assertTrue(exists("Замчлал зөвхөн таны байршлаас эхэлнэ"))
        compose.onNodeWithTag("nav-start").performClick()
        idle(300)
        assertNull("AC 15: no guidance", vm().guidance.value)
        assertFalse("AC 15: no notification prompt", vm().ui.value.requestNotificationPermission)
        assertFalse("AC 15: no location prompt", vm().ui.value.requestLocationPermission)
        assertFalse("AC 15: no rationale", vm().ui.value.rationale)

        // AC 18: the turn list is in the expanded sheet only.
        assertFalse("AC 18: not part of the collapsed sheet", tagged("route-steps"))
        onVm { it.setSheetExpanded(true) }
        idle(300)
        // The list is the last item of the lazy body: scroll to it (lazy composition, AC 25).
        compose.onNode(hasScrollToNodeAction() and hasTestTag("route-preview-body")).performScrollToNode(hasTestTag("route-steps"))
        assertTrue("AC 18: «Маршрутын заавар» in the expanded sheet", exists("Маршрутын заавар"))
        assertTrue("AC 18: rows shown", tagged("route-step"))

        // AC 22: a row activation moves to the step, 0 requests, selection kept.
        val route = (vm().preview.state.value!!.result as PreviewResult.Route)
        val step2 = route.route.plan.steps[2].location
        onVm { it.focusStep(2, collapse = true) }
        idle(300)
        assertEquals("AC 22: manoeuvre point shown", step2, RecordingMapSurface.content?.step)
        assertFalse("Q8: portrait touch use collapses the sheet", vm().ui.value.sheetExpanded)
        assertEquals("AC 22: selection kept", route.selected, (vm().preview.state.value!!.result as PreviewResult.Route).selected)
        assertEquals("AC 22: 0 requests", requests, FakeGateway.routeRequests.get())

        // AC 24 / 33: language and theme switches keep the points with 0 requests; «Сонгосон цэг» follows the language.
        onVm { it.setLanguage(Lang.EN) }
        idle(500)
        val originNode = compose.onNodeWithTag("route-origin", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("AC 33: «Сонгосон цэг» → \"Selected point\"", "Selected point", originNode.config.getOrNull(SemanticsProperties.StateDescription))
        onVm { it.setTheme(ThemeChoice.NIGHT) }
        idle(500)
        onVm { it.setLanguage(Lang.MN) }
        idle(500)
        assertEquals(RoutePoint.MapPoint(pickup), vm().preview.state.value!!.origin)
        assertEquals("AC 24 / 33: 0 route requests", requests, FakeGateway.routeRequests.get())
        assertTrue(exists("Замчлал зөвхөн таны байршлаас эхэлнэ"))

        // AC 31: only gateway API paths; no coordinates of the points or the device in logs or app storage.
        val unexpected = FakeGateway.paths.filter { it !in setOf("/v1/route", "/v1/reverse", "/v1/search") }
        assertTrue("AC 31: only API paths: $unexpected", unexpected.isEmpty())
        val needles = Regex("47[.,]921[0-9]|106[.,]894[0-9]|47[.,]885[0-9]|106[.,]917[0-9]|47[.,]918[0-9]")
        val dataDir = File(app.applicationInfo.dataDir)
        val storedHits = dataDir.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            needles.find(text)?.let { "${f.relativeTo(dataDir)}: ${it.value}" }
        }.toList()
        assertTrue("AC 31: storage holds no coordinates: $storedHits", storedHits.isEmpty())
        val logHits = ShadowLog.getLogs().filter { needles.containsMatchIn(it.msg ?: "") }.map { "${it.tag}: ${it.msg}" }
        assertTrue("AC 31: logs hold no coordinates: $logHits", logHits.isEmpty())
    }

    // ---- Run 2 (2026-10-04): AC 5 bullet 2, the D140 rule now in force (NAV-011 AC 7 / 7a; D145 for the NAV-018 fields).

    private fun searches() = FakeGateway.paths.count { it == "/v1/search" }
    private fun reverses() = FakeGateway.paths.count { it == "/v1/reverse" }
    private fun stateOf(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun typeInEditor(text: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("point-editor"))).performTextReplacement(text)
        idle(400) // > 250 ms debounce
    }

    /**
     * TC-A04 (AC 5 bullet 2, D145; AC 3, 11, 15, 31, 33). The destination field: a typed pair with 8 decimals shows exactly
     * one «Сонгосон цэг» option and no «Миний байршил» option (AC 3), with 0 `search` requests. Choosing it sets the
     * destination to the typed coordinate unrounded, closes the editor, opens no coordinate card, sends 0 `search`,
     * 0 `reverse` and exactly 1 route request; the field reads «Сонгосон цэг». No `search` URL carries the pair (AC 31,
     * NAV-011 AC 39). Swap moves the typed point to the start: «Эхлэх» becomes disabled with O1 (AC 11, 15). A language
     * switch relabels it "Selected point" with 0 requests (AC 33).
     */
    @Test
    fun tcA04_typedPairInTheDestinationFieldSetsTheDestinationWithoutSearchOrCard() {
        launch()
        previewWithDeviceStart()
        val reversesBefore = reverses()
        compose.onNodeWithTag("route-destination").performClick()
        idle()
        val editor = compose.onNodeWithTag("point-editor", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("destination", editor.config.getOrNull(mn.navmn.app.ui.screens.preview.EditingKey))
        assertFalse("AC 3: no «Миний байршил» option in the destination list", tagged("point-option-my-location"))

        typeInEditor("47.92131234, 106.89481234")
        waitFor { vm().fieldSearch.view.value is SearchView.Coordinate }
        idle(300)
        assertEquals("D140: exactly one option", 1, compose.onAllNodes(hasTestTag("search-coordinate-option"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("T6 label", exists("Сонгосон цэг"))
        assertEquals("D140: 0 search requests for a recognised pair", 0, searches())
        assertEquals("1 route request so far", 1, FakeGateway.routeRequests.get())

        compose.onNodeWithTag("search-coordinate-option").performClick()
        waitFor { FakeGateway.routeRequests.get() == 2 && vm().preview.state.value?.result is PreviewResult.Route }
        val typed = LatLon(47.92131234, 106.89481234)
        assertEquals("AC 5: destination = the typed coordinate, not rounded (AC 13)", RoutePoint.TypedCoordinate(typed), vm().preview.state.value!!.destination)
        assertTrue("start unchanged", vm().preview.state.value!!.origin is RoutePoint.MyLocation)
        assertNull("editor closed (D146)", vm().points.value.editor)
        assertEquals(SearchView.Closed, vm().fieldSearch.view.value)
        assertNull("AC 5: no coordinate card", vm().ui.value.card)
        assertFalse(tagged("coordinate-card"))
        assertEquals("AC 5: field text «Сонгосон цэг»", "Сонгосон цэг", stateOf("route-destination"))
        idle(500)
        assertEquals("0 search", 0, searches())
        assertEquals("0 reverse", reversesBefore, reverses())
        assertEquals("exactly one route request", 2, FakeGateway.routeRequests.get())
        val leaked = FakeGateway.urls.filter { it.encodedPath == "/v1/search" && (it.queryParameter("q") ?: "").contains("47.92") }
        assertTrue("AC 31: the pair never reaches q: $leaked", leaked.isEmpty())

        // AC 11 / 15: swap moves the typed point to the start → disabled «Эхлэх» with O1; one request.
        compose.onNodeWithTag("route-swap").performClick()
        waitFor { FakeGateway.routeRequests.get() == 3 && vm().preview.state.value?.result is PreviewResult.Route }
        assertEquals(RoutePoint.TypedCoordinate(typed), vm().preview.state.value!!.origin)
        assertEquals("Сонгосон цэг", stateOf("route-origin"))
        assertTrue("AC 15: O1 for a typed start", exists("Замчлал зөвхөн таны байршлаас эхэлнэ"))

        // AC 33: language switch relabels, 0 requests.
        onVm { it.setLanguage(Lang.EN) }
        idle(500)
        assertEquals("Selected point", stateOf("route-origin"))
        onVm { it.setLanguage(Lang.MN) }
        idle(500)
        assertEquals("AC 33: 0 requests on language switch", 3, FakeGateway.routeRequests.get())
        assertEquals(0, searches())
    }

    /**
     * TC-A05 (AC 5 bullet 2 with D140 "the option also shows offline, because no request is needed"; AC 17 / NAV-005 AC 7
     * offline rule). Offline, a typed pair in the start field still shows the option with 0 requests; choosing it sets the
     * start («Сонгосон цэг») and sends 0 `search`, 0 `reverse` and 0 route requests while offline.
     */
    @Test
    fun tcA05_typedPairInTheStartFieldWorksOfflineWithZeroRequests() {
        launch()
        previewWithDeviceStart()
        val pathsBefore = FakeGateway.paths.size
        val cm = app.getSystemService(ConnectivityManager::class.java)
        scenario!!.onActivity { shadowOf(cm).networkCallbacks.forEach { cb -> cb.onLost(cm.activeNetwork!!) } }
        idle()

        compose.onNodeWithTag("route-origin").performClick()
        idle()
        typeInEditor("47.9213 106.8948")
        waitFor { vm().fieldSearch.view.value is SearchView.Coordinate }
        idle(300)
        assertTrue("D140: the option shows offline", tagged("search-coordinate-option"))
        compose.onNodeWithTag("search-coordinate-option").performClick()
        idle(500)
        assertEquals(RoutePoint.TypedCoordinate(pickup), vm().preview.state.value!!.origin)
        assertEquals("Сонгосон цэг", stateOf("route-origin"))
        assertNull(vm().ui.value.card)
        assertEquals("offline: 0 requests of any kind", pathsBefore, FakeGateway.paths.size)
        assertEquals(1, FakeGateway.routeRequests.get())
    }

    /**
     * TC-A06 (AC 5; D140: pairs the ADR-0006 rule rejects, such as out of range, are still searched as typed text). An
     * out-of-range pair in the start field shows no coordinate option, sends at least one `search` request with the text
     * as typed, and sets no point (the start stays «Миний байршил», 1 route request).
     */
    @Test
    fun tcA06_aRejectedPairInAFieldIsSearchedAsTypedText() {
        launch()
        previewWithDeviceStart()
        compose.onNodeWithTag("route-origin").performClick()
        idle()
        typeInEditor("95.5 200.5")
        waitFor { searches() >= 1 }
        idle(300)
        assertFalse("no coordinate option for a rejected pair", tagged("search-coordinate-option"))
        assertTrue(vm().fieldSearch.view.value !is SearchView.Coordinate)
        val qs = FakeGateway.urls.filter { it.encodedPath == "/v1/search" }.map { it.queryParameter("q") }
        assertTrue("sent as typed: $qs", "95.5 200.5" in qs)
        assertTrue("start unchanged", vm().preview.state.value!!.origin is RoutePoint.MyLocation)
        assertEquals(1, FakeGateway.routeRequests.get())
    }
}
