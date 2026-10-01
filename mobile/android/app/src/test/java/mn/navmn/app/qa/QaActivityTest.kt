package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.android.FakeGateway
import mn.navmn.app.android.FakeLocation
import mn.navmn.app.android.RecordingMapSurface
import mn.navmn.app.android.RecordingRequester
import mn.navmn.app.android.RecordingVoice
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.settings.ThemeChoice
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import javax.inject.Inject

/**
 * NAV-005 QA, Activity level (Robolectric, MapLibre replaced by the mobile engineer's RecordingMapSurface seam,
 * ADR-0009 §10). Test plan docs/qa/test-plans/NAV-005.md, ids TC-A*. Covers what round 0 left PARTIAL because of D7:
 * AC 1 (no OS prompt on launch), AC 58/59 (theme switch during guidance) and AC 60 (first launch in Mongolian on an
 * English device; language switch during guidance). Expected texts are the story's literal strings. Runs on API 32.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// API 32: AppCompat itself applies the per-app locale there. On API 33+ the framework LocaleManager does it, which
// Robolectric's SDK 35 sandbox does not emulate (the locale is stored as `mn` but the Activity configuration stays
// `en`), so the API 33+ path is a reference-device check (test plan §6 DV1).
@Config(application = HiltTestApplication::class, qualifiers = "en-w360dp-h640dp", sdk = [32])
class QaActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: GuidanceSession
    @Inject lateinit var settings: SettingsRepository

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
        RecordingRequester.requests.clear()
        // A fresh install: no per-app locale stored yet (the device itself is English, qualifiers "en").
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
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
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val s = ActivityScenario.launch(MainActivity::class.java)
        scenario = s
        compose.waitForIdle()
        return s
    }

    private fun idle(ms: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun waitFor(timeoutMs: Long = 2_000, cond: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun described(desc: String) = compose.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()

    private val route by lazy { (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route }

    private fun startGuidance() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        launch()
        session.start(route, Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false), FakeLocation.goodFix())
        waitFor { session.engine.value?.state?.value != null }
        waitFor { RecordingVoice.played.isNotEmpty() }
        waitFor { described("Дуусгах") }
        idle(1_000)
    }

    /** TC-A01 (AC 60, AC 1): first launch on an ENGLISH device opens in Mongolian; no OS location prompt on launch. */
    @Test
    fun tcA01_firstLaunchOnAnEnglishDeviceOpensInMongolian() {
        launch()
        idle(500)
        waitFor { exists("Газар, хаяг хайх") }
        assertTrue("no English search placeholder", !exists("Search for a place or address"))
        assertEquals("mn", AppCompatDelegate.getApplicationLocales().get(0)?.language)
        var a: MainActivity? = null
        scenario!!.onActivity { a = it }
        assertNull("AC 1: no OS location prompt on launch", shadowOf(a!!).lastRequestedPermission)
        assertEquals("AC 1: no location listener before a user action", 0, FakeLocation.mapRegistrations.get())
    }

    /** TC-A02 (AC 60): language switch during guidance: labels within 1 s, same engine, 0 requests, remembered. */
    @Test
    fun tcA02_languageSwitchDuringGuidance() {
        startGuidance()
        val engine = session.engine.value
        val requests = FakeGateway.routeRequests.get()
        val prompts = RecordingVoice.played.map { it.text }
        compose.onNodeWithTag("nav-settings").performClick()
        idle()
        compose.onNode(hasText("English")).performClick()
        val ms = waitFor { described("End") }
        assertTrue("AC 60: labels switched in $ms ms", ms <= 1_000)
        idle(1_000)
        assertTrue("no Mongolian «Дуусгах» left", !described("Дуусгах"))
        assertSame("AC 60/64: guidance continues on the same engine", engine, session.engine.value)
        assertEquals("AC 60: 0 route requests", requests, FakeGateway.routeRequests.get())
        assertEquals("AC 60: 0 reroute requests", 0, RecordingRequester.requests.size)
        val after = RecordingVoice.played.map { it.text }
        assertEquals("0 repeated prompts: $after", prompts, after.take(prompts.size))
        assertEquals("0 repeated prompts: $after", after.distinct(), after)
        assertEquals("AC 60: remembered", "en", AppCompatDelegate.getApplicationLocales().get(0)?.language)
    }

    /** TC-A03 (AC 58, 59): theme switch during guidance: choice applied and stored, route/banner stay, 0 requests/prompts. */
    @Test
    fun tcA03_themeSwitchDuringGuidance() {
        startGuidance()
        val engine = session.engine.value
        val requests = FakeGateway.routeRequests.get()
        val prompts = RecordingVoice.played.map { it.text }
        compose.onNodeWithTag("nav-settings").performClick()
        idle()
        compose.onNode(hasText("Шөнийн горим")).performClick()
        // The recording map seam does not record the `night` flag it receives (request to mobile in the handoff), so
        // the switch is observed through the stored choice and the selected «Шөнийн горим» row.
        val ms = waitFor { settings.theme.value == ThemeChoice.NIGHT && compose.onAllNodes(hasText("Шөнийн горим") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("AC 59: theme switched in $ms ms", ms <= 1_000)
        idle(1_000)
        assertTrue("AC 59: guidance stays («Дуусгах»)", described("Дуусгах"))
        assertTrue("AC 59: the route stays on the map", RecordingMapSurface.content?.guidance == true)
        assertSame(engine, session.engine.value)
        assertEquals("AC 59: 0 route requests", requests, FakeGateway.routeRequests.get())
        assertEquals("AC 59: 0 reroute requests", 0, RecordingRequester.requests.size)
        val after = RecordingVoice.played.map { it.text }
        assertEquals("AC 59: 0 repeated prompts: $after", prompts, after.take(prompts.size))
        assertEquals("AC 59: 0 repeated prompts: $after", after.distinct(), after)
    }
}
