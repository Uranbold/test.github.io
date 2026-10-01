package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.time.Duration
import javax.inject.Inject

/**
 * Activity-level Robolectric tests (AC 71) with MapLibre replaced by [RecordingMapSurface] (ADR-0009 §10,
 * Amendment 1): permission flow AC 8, 10, 11, 12, 13; configuration changes during guidance AC 64; and the map-screen
 * location lifecycle of Amendment 1 §9 (review M1: 0 map listeners while stopped or guiding). Expected texts are the
 * story's literal Mongolian strings (default locale mn).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class MainActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: GuidanceSession

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
        RecordingRequester.requests.clear()
        // A validated network before NetworkMonitor is created (the preview needs it).
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
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

    private fun launch(): ActivityScenario<MainActivity> {
        val s = ActivityScenario.launch(MainActivity::class.java)
        scenario = s
        compose.waitForIdle()
        return s
    }

    private fun activity(): MainActivity {
        var a: MainActivity? = null
        scenario!!.onActivity { a = it }
        return a!!
    }

    private fun idle(ms: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    /** Polls (main looper + real time) until [cond] holds; returns the elapsed ms or fails after [timeoutMs]. */
    private fun waitFor(timeoutMs: Long = 2_000, cond: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun node(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text))
    private fun nodeExists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun myLocationButton() = compose.onNode(hasContentDescription("Миний байршил") and !hasTestTag("route-preview"))

    private fun grant(vararg p: String) = shadowOf(app).grantPermissions(*p)
    private fun deny(vararg p: String) = shadowOf(app).denyPermissions(*p)
    private fun rationaleAllowed(value: Boolean) {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(fine, value)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(coarse, value)
    }

    private fun lastPermissionRequest() = shadowOf(activity()).lastRequestedPermission

    /** Delivers the OS dialog result for the last permission request (after the test granted or denied). */
    private fun deliverPermissionResult() {
        val req = requireNotNull(lastPermissionRequest()) { "no permission request to answer" }
        val results = req.requestedPermissions.map { p ->
            if (app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }.toIntArray()
        scenario!!.onActivity { it.onRequestPermissionsResult(req.requestCode, req.requestedPermissions, results) }
        idle()
    }

    /** Back from the system settings page: the Activity goes through onStop/onStart/onResume. */
    private fun returnFromSettings() {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        idle()
    }

    /** S2 → S3: long-press on the (fake) map, then «Маршрут гаргах» on the coordinate card. */
    private fun openPreviewByLongPress() {
        var ok = false
        scenario!!.onActivity { ok = RecordingMapSurface.longPress(LatLon(47.8858, 106.9173)) }
        assertTrue("the map screen has a long-press handler", ok)
        idle()
        node("Маршрут гаргах").performClick()
        idle()
    }

    private val route by lazy { (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route }

    // ------------------------------------------------------------------------------------------- AC 8

    @Test
    fun ac8_rationaleBeforeTheOsDialog() {
        launch()
        myLocationButton().performClick()
        idle()
        compose.onNodeWithTag("location-rationale").assertExists()
        node("Байршлаа ашиглахыг зөвшөөрнө үү").assertExists()
        assertNull("no OS dialog before the rationale is confirmed", lastPermissionRequest())
        assertEquals(0, FakeLocation.mapRegistrations.get())

        node("Үргэлжлүүлэх").performClick()
        idle()
        val req = lastPermissionRequest()
        assertNotNull("OS dialog after «Үргэлжлүүлэх»", req)
        assertEquals(setOf(fine, coarse), req!!.requestedPermissions.toSet())
        assertTrue(compose.onAllNodes(hasTestTag("location-rationale")).fetchSemanticsNodes().isEmpty())

        grant(fine, coarse)
        deliverPermissionResult()
        waitFor { FakeLocation.mapListeners.get() == 1 }
    }

    @Test
    fun ac8_rationaleCloseNeverOpensTheOsDialog() {
        launch()
        myLocationButton().performClick()
        idle()
        node("Хаах").performClick()
        idle()
        assertNull(lastPermissionRequest())
        assertEquals(0, FakeLocation.mapRegistrations.get())
    }

    // ------------------------------------------------------------------------------------------- AC 10

    @Test
    fun ac10_approximateLocationShowsThePreciseMessageAndNoRouteRequest() {
        grant(coarse)
        deny(fine)
        launch()
        openPreviewByLongPress()
        waitFor { nodeExists("Нарийвчилсан байршлыг зөвшөөрнө үү") }
        node("Тохиргоо нээх").assertExists()
        assertNull("no OS dialog for approximate → precise (settings only)", lastPermissionRequest())
        assertEquals(0, FakeGateway.routeRequests.get())

        // «Тохиргоо нээх» opens the app's settings page; precise granted there → the origin resolves, 1 route request.
        node("Тохиргоо нээх").performClick()
        idle()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
        grant(fine)
        val ms = run {
            returnFromSettings()
            waitFor { FakeGateway.routeRequests.get() == 1 }
        }
        assertTrue("continued in $ms ms", ms <= 2_000)
    }

    // ------------------------------------------------------------------------------------------- AC 11

    @Test
    fun ac11_deniedThenGrantedInSettingsContinuesThePendingActionWithinTwoSeconds() {
        rationaleAllowed(true)
        launch()
        myLocationButton().performClick()
        idle()
        node("Үргэлжлүүлэх").performClick()
        idle()
        deny(fine, coarse)
        deliverPermissionResult()
        waitFor { nodeExists("Байршлын зөвшөөрөл олгоогүй байна") }
        node("Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу").assertExists()
        assertEquals(0, FakeLocation.mapRegistrations.get())

        node("Тохиргоо нээх").performClick()
        idle()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
        grant(fine, coarse)
        val start = System.nanoTime()
        returnFromSettings()
        waitFor { FakeLocation.mapListeners.get() == 1 && !nodeExists("Байршлын зөвшөөрөл олгоогүй байна") }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("pending action continued in $ms ms", ms <= 2_000)
    }

    @Test
    fun ac11_permanentlyDeniedSkipsTheOsDialogAndResumesFromSettings() {
        rationaleAllowed(false)
        launch()
        openPreviewByLongPress()
        // First time: rationale, then the OS dialog; denied with "don't ask again" → permanent.
        compose.onNodeWithTag("location-rationale").assertExists()
        node("Үргэлжлүүлэх").performClick()
        idle()
        deny(fine, coarse)
        deliverPermissionResult()
        waitFor { nodeExists("Байршлын зөвшөөрөл олгоогүй байна") }
        val firstRequest = lastPermissionRequest()

        // Retry inside the preview: no rationale and no second OS dialog after a permanent denial.
        node("Хаах").performClick()
        idle()
        openPreviewByLongPress()
        idle()
        assertTrue(compose.onAllNodes(hasTestTag("location-rationale")).fetchSemanticsNodes().isEmpty())
        assertSame("no second OS dialog", firstRequest, lastPermissionRequest())
        waitFor { nodeExists("Байршлын зөвшөөрөл олгоогүй байна") }
        assertEquals(0, FakeGateway.routeRequests.get())

        grant(fine, coarse)
        val start = System.nanoTime()
        returnFromSettings()
        waitFor { FakeGateway.routeRequests.get() == 1 }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("pending preview origin continued in $ms ms", ms <= 2_000)
    }

    // ------------------------------------------------------------------------------------------- AC 12

    @Test
    fun ac12_locationServicesOffThenOnContinues() {
        grant(fine, coarse)
        FakeLocation.servicesOn = false
        launch()
        myLocationButton().performClick()
        idle()
        waitFor { nodeExists("Байршил тогтоох үйлчилгээ унтарсан байна") }
        assertNull("no permission dialog when only the services are off", lastPermissionRequest())
        assertEquals(0, FakeLocation.mapRegistrations.get())
        node("Тохиргоо нээх").performClick()
        idle()
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, shadowOf(app).nextStartedActivity.action)

        FakeLocation.servicesOn = true
        val start = System.nanoTime()
        returnFromSettings()
        waitFor { FakeLocation.mapListeners.get() == 1 && !nodeExists("Байршил тогтоох үйлчилгээ унтарсан байна") }
        assertTrue((System.nanoTime() - start) / 1_000_000 <= 2_000)
    }

    // ------------------------------------------------------------------------------------------- AC 13 (+ M1)

    @Test
    fun ac13_notificationPermissionDeniedGuidanceStillStarts() {
        grant(fine, coarse)
        deny(Manifest.permission.POST_NOTIFICATIONS)
        launch()
        openPreviewByLongPress()
        waitFor { FakeGateway.routeRequests.get() == 1 && compose.onAllNodes(hasTestTag("nav-start")).fetchSemanticsNodes().isNotEmpty() }
        idle(300)
        compose.onNodeWithTag("nav-start").performClick()
        idle()
        val req = lastPermissionRequest()
        assertNotNull("POST_NOTIFICATIONS requested at the first «Эхлэх» (API 33+)", req)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), req!!.requestedPermissions.toList())
        deliverPermissionResult() // denied

        waitFor { session.engine.value?.state?.value != null }
        waitFor { compose.onAllNodes(hasContentDescription("Дуусгах")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("guidance location listener", 1, FakeLocation.guidanceListeners.get())
        assertEquals("review M1: no map listener while guiding", 0, FakeLocation.mapListeners.get())
        assertEquals("AC 15: 0 extra route requests at start", 1, FakeGateway.routeRequests.get())
        assertEquals(0, RecordingRequester.requests.size)
    }

    @Test
    fun m1_mapLocationOnlyWhileStartedAndNeverWhileGuiding() {
        grant(fine, coarse)
        launch()
        myLocationButton().performClick()
        waitFor { FakeLocation.mapListeners.get() == 1 }

        // Home: the Activity stops → the map request is removed; nothing is registered in the background.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitFor { FakeLocation.mapListeners.get() == 0 }
        val registrations = FakeLocation.mapRegistrations.get()
        idle(5_000)
        assertEquals(0, FakeLocation.mapListeners.get())
        assertEquals("no registration while stopped", registrations, FakeLocation.mapRegistrations.get())

        // Back in the foreground: the map request returns.
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor { FakeLocation.mapListeners.get() == 1 }

        // Guidance: the guidance provider is the only listener (also on the arrival panel until «Хаах»).
        session.start(route, Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false), FakeLocation.goodFix())
        waitFor { FakeLocation.guidanceListeners.get() == 1 }
        waitFor { FakeLocation.mapListeners.get() == 0 }

        // Guidance stopped while the app is in the background: nothing at all stays registered.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        session.end()
        waitFor { session.engine.value == null }
        waitFor { FakeLocation.guidanceListeners.get() == 0 }
        idle(2_000)
        assertEquals(0, FakeLocation.mapListeners.get())

        // Visible again after guidance: only the map screen's listener.
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor { FakeLocation.mapListeners.get() == 1 }
        assertEquals(0, FakeLocation.guidanceListeners.get())
    }

    // ------------------------------------------------------------------------------------------- AC 64

    @Test
    fun ac64_recreateAndRotationDuringGuidanceGiveNoRequestsAndNoRepeatedPrompts() {
        grant(fine, coarse)
        launch()
        session.start(route, Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false), FakeLocation.goodFix())
        waitFor { session.engine.value?.state?.value != null }
        waitFor { RecordingVoice.played.isNotEmpty() }
        waitFor { compose.onAllNodes(hasContentDescription("Дуусгах")).fetchSemanticsNodes().isNotEmpty() }
        idle(1_000)
        val engine = session.engine.value
        val prompts = RecordingVoice.played.map { it.text }
        val routeRequests = FakeGateway.routeRequests.get()

        scenario!!.recreate()
        idle(500)
        RuntimeEnvironment.setQualifiers("+land")
        scenario!!.recreate()
        idle(500)
        RuntimeEnvironment.setQualifiers("+port")
        scenario!!.recreate()
        idle(2_000)

        assertSame("the engine survives configuration changes", engine, session.engine.value)
        waitFor { compose.onAllNodes(hasContentDescription("Дуусгах")).fetchSemanticsNodes().isNotEmpty() }
        // The engine's own schedule may add a new prompt meanwhile (virtual clock); none may be played again.
        val after = RecordingVoice.played.map { it.text }
        assertEquals("prompts before the changes are kept as they were", prompts, after.take(prompts.size))
        assertEquals("0 repeated prompts: $after", after.distinct(), after)
        assertEquals("0 route requests (preview client)", routeRequests, FakeGateway.routeRequests.get())
        assertEquals("0 reroute requests", 0, RecordingRequester.requests.size)
        assertEquals("one guidance listener", 1, FakeLocation.guidanceListeners.get())
        assertEquals("no map listener while guiding", 0, FakeLocation.mapListeners.get())
        assertTrue("the guidance map content is shown", RecordingMapSurface.content?.guidance == true)
    }
}
