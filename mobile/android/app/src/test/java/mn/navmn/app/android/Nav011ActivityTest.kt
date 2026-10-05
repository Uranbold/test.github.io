package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.search.SearchView
import mn.navmn.app.typinglock.PassengerOverride
import mn.navmn.app.ui.AppViewModel
import mn.navmn.app.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * NAV-011 at the Activity level (Robolectric, real [mn.navmn.app.typinglock.PlatformLockFixSource] on the shadow
 * LocationManager): the typing lock engages from platform fixes, the tap shows the card and no keyboard, «Би зорчигч»
 * overrides for the process only (AC 27, 30, 31, 34); the coordinate card sends exactly one `reverse` and none on
 * rotation (AC 8, 12, 13, 20); a typed coordinate pair is one option with 0 `search` requests that opens the card and
 * centres the camera once (AC 7, 7a, 33; D140–D146).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class Nav011ActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        RecordingVoice.reset()
        FakeGateway.reset()
        FakeCamera.focuses.clear()
        PassengerOverride.resetForProcessRestart()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        hilt.inject()
    }

    @After
    fun tearDown() {
        scenario?.close()
        PassengerOverride.resetForProcessRestart()
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

    private val field = hasContentDescription("Газар, хаяг хайх")

    private fun exists(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun drive(kmh: Double, n: Int) {
        val lm = app.getSystemService(LocationManager::class.java)
        var p = LatLon(47.9189, 106.9176)
        repeat(n) {
            p = Geo.offset(p, 90.0, kmh / 3.6)
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = p.lat
                longitude = p.lon
                accuracy = 5f
                speed = (kmh / 3.6).toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(lm).simulateLocation(loc)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
            Thread.sleep(30)
        }
        idle()
    }

    @Test
    fun lockEngagesFromPlatformFixesTapShowsTheCardPassengerOverridesForTheProcessOnly() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setLocationEnabled(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(500)
        drive(20.0, 4)
        waitFor { vm().typingLock.state.value.locked }
        assertFalse("AC 30: nothing on screen before a tap", exists("Хөдөлж байх үед бичих боломжгүй"))
        assertTrue("locked: the field is read-only (no SetText action)", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        compose.onNode(field).performClick()
        idle(300)
        assertTrue("AC 31: the lock card", exists("Хөдөлж байх үед бичих боломжгүй"))
        assertTrue(exists("Жолооч бол зогсоод хайна уу"))
        assertFalse("the field is not focused (no keyboard)", compose.onNode(field).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        compose.onNodeWithTag("typing-lock-passenger").performClick()
        idle(300)
        assertFalse(exists("Хөдөлж байх үед бичих боломжгүй"))
        assertTrue(vm().typingLock.state.value.overridden)
        assertTrue("AC 34: the field takes focus", compose.onNode(field).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        assertTrue("and is editable again", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        // Same process: the override survives a configuration change.
        scenario!!.recreate()
        idle(300)
        assertTrue(vm().typingLock.state.value.overridden)
        // Never stored: nothing about it in the app's files or preferences.
        val dataDir = File(app.applicationInfo.dataDir)
        val stored = dataDir.walkTopDown().filter { it.isFile }.any { f -> runCatching { f.readText() }.getOrDefault("").contains("passenger", ignoreCase = true) }
        assertFalse("the override is never stored", stored)
        // A new process (simulated: the process-memory holder is gone) starts without it.
        scenario!!.close()
        PassengerOverride.resetForProcessRestart()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        assertFalse(vm().typingLock.state.value.overridden)
    }

    @Test
    fun noLockWithoutPreciseLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        drive(30.0, 5)
        assertFalse("AC 35", vm().typingLock.state.value.engaged)
        compose.onNode(field).performClick()
        idle(200)
        assertFalse(exists("Хөдөлж байх үед бичих боломжгүй"))
    }

    @Test
    fun coordinateCardSendsExactlyOneReverseAndNoneOnRotation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        var ok = false
        scenario!!.onActivity { ok = RecordingMapSurface.longPress(LatLon(47.8858, 106.9173)) }
        assertTrue(ok)
        waitFor { FakeGateway.paths.count { it == "/v1/reverse" } == 1 && vm().reverse.view.value?.id == "error" }
        idle(200)
        // The fake gateway answers 404 → «Алдаа гарлаа»; heading, coordinates and «Маршрут гаргах» stay.
        assertTrue(exists("Алдаа гарлаа"))
        assertTrue(exists("Сонгосон цэг"))
        assertTrue(exists("Маршрут гаргах"))
        scenario!!.recreate()
        idle(500)
        assertEquals("0 reverse requests on rotation", 1, FakeGateway.paths.count { it == "/v1/reverse" })
        assertTrue(exists("Алдаа гарлаа"))
        compose.onNode(hasText("Маршрут гаргах")).performClick()
        idle(300)
        assertEquals(1, FakeGateway.paths.count { it == "/v1/reverse" })
        assertEquals(null, vm().reverse.view.value)
    }

    private val typed = LatLon(47.9189, 106.9176)
    private fun searches() = FakeGateway.paths.count { it == "/v1/search" }
    private fun reverses() = FakeGateway.paths.count { it == "/v1/reverse" }
    private fun options() = compose.onAllNodesWithTag("search-coordinate-option").fetchSemanticsNodes().size

    /** TC-A12 with the D140 oracle (NAV-011 AC 7, 7a, 8, 12, 13; D142, D146; ADR-0012 Amendment A5). */
    @Test
    fun typedCoordinateOptionOpensTheCardWithOneReverseAndCentresTheCameraOnce() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        compose.onNode(field).performClick()
        compose.onNode(field).performTextInput("47.9189, 106.9176")
        waitFor { vm().search.view.value is SearchView.Coordinate }
        idle(500) // past the 300 ms loading-row delay
        assertEquals("AC 7: one option", 1, options())
        assertTrue(exists("Сонгосон цэг"))
        assertTrue(exists("47.91890, 106.91760"))
        assertFalse("no loading row", exists("Ачаалж байна…"))
        assertEquals("AC 7: 0 search requests", 0, searches())
        // AC 13: a language switch and a rotation with the option shown re-render only.
        vm().setLanguage(Lang.EN)
        waitFor { exists("Selected point") }
        vm().setLanguage(Lang.MN)
        waitFor { exists("Сонгосон цэг") }
        scenario!!.recreate()
        idle(500)
        assertEquals(1, options())
        assertEquals("0 search, 0 reverse", 0, searches() + reverses())
        // AC 7a: select → the list closes, the card opens, one reverse at the typed point, one camera move.
        compose.onNodeWithTag("search-coordinate-option").performClick()
        idle(300)
        assertEquals("the list is gone", 0, options())
        assertEquals(SearchView.Closed, vm().search.view.value)
        assertEquals(typed, vm().ui.value.card)
        assertFalse("following stops", vm().ui.value.followingMe)
        assertTrue("the field keeps the typed text", exists("47.9189, 106.9176"))
        assertTrue(compose.onAllNodesWithTag("coordinate-card").fetchSemanticsNodes().isNotEmpty())
        waitFor { reverses() == 1 }
        val r = FakeGateway.urls.single { it.encodedPath == "/v1/reverse" }
        assertEquals("AC 8: the typed values, 6 decimals", "47.918900", r.queryParameter("lat"))
        assertEquals("106.917600", r.queryParameter("lon"))
        assertEquals("AC 39: the pair never reaches a search q", 0, searches())
        waitFor { FakeCamera.focuses.any { it.p == typed } }
        val f = FakeCamera.focuses.single { it.p == typed }
        assertEquals("D142: max(current 12, 16)", 16.0, f.zoom, 0.0)
        // C1: the point lands in the free map between the top group and the card, clear of both (pin 40 dp above it).
        val d = app.resources.displayMetrics.density
        val map = compose.onNodeWithTag("map").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("search-bar").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("coordinate-card").fetchSemanticsNode().boundsInRoot
        val y = map.top + f.top + (map.height - f.top - f.bottom) / 2f
        assertTrue("pin top below the search bar (y=$y, bar=${bar.bottom})", y - 40 * d >= bar.bottom)
        assertTrue("point above the card (y=$y, card=${card.top})", y <= card.top - 16 * d)
        assertEquals("narrow: full width, 16 dp sides", Math.round(16 * d), f.left)
        assertEquals(Math.round(16 * d), f.right)
        // One-shot: a rotation repeats neither the camera move nor the reverse.
        scenario!!.recreate()
        idle(500)
        assertEquals(1, FakeCamera.focuses.count { it.p == typed })
        assertEquals(1, reverses())
        // «Маршрут гаргах» → the destination is «Сонгосон цэг» (the map point, no name).
        compose.onNode(hasText("Маршрут гаргах")).performClick()
        idle(300)
        assertEquals(RoutePoint.MapPoint(typed), vm().preview.state.value?.destination)
        assertEquals(0, searches())
    }

    /** AC 33: the option is a list item, not typing, so it stays selectable while the typing lock is engaged. */
    @Test
    fun typedCoordinateOptionIsSelectableWhileTheTypingLockIsEngaged() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setLocationEnabled(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(500)
        compose.onNode(field).performClick()
        compose.onNode(field).performTextInput("47.9189 106.9176")
        waitFor { vm().search.view.value is SearchView.Coordinate }
        drive(20.0, 4)
        waitFor { vm().typingLock.state.value.locked }
        idle(300)
        assertEquals(1, options())
        compose.onNodeWithTag("search-coordinate-option").performClick()
        idle(300)
        assertEquals(typed, vm().ui.value.card)
        waitFor { reverses() == 1 }
        assertEquals(0, searches())
    }

    /** AC 9: a long-press still never moves the camera. */
    @Test
    fun longPressCardDoesNotMoveTheCamera() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        scenario!!.onActivity { RecordingMapSurface.longPress(typed) }
        waitFor { reverses() == 1 }
        idle(800)
        assertTrue(FakeCamera.focuses.none { it.p == typed })
    }
}
