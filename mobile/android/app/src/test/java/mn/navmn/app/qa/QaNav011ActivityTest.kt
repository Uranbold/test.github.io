package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
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
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.time.Duration

/**
 * NAV-011 QA, Activity level (Robolectric, the mobile engineer's test seams; real PlatformLockFixSource on the shadow
 * LocationManager). Test plan docs/qa/test-plans/NAV-011.md, id TC-A11. Covers the parts of AC 27 / 28 the mobile
 * suite does not: the 1 Hz request, 0 network requests caused by the lock, the log and storage scan for coordinates
 * and speeds after a full lock session (engage and release), and the listener stopping within 2 s when the map screen
 * leaves the foreground (and coming back with it).
 *
 * D140 typed-coordinate change (run 2, AC 7, 7a, 8, 11, 12, 39): TC-A13 (option at a 6-decimal point in "en", one
 * `reverse` with the typed values and the UI language, one camera move, then a long-press adds exactly one `reverse`
 * and no camera move), TC-A14 (offline: the option still opens the card, 0 `reverse`, one when the network returns)
 * and TC-P39 (session request scan with the restored AC 39 oracle). TC-A12 itself is the mobile engineer's
 * `Nav011ActivityTest.typedCoordinateOptionOpensTheCardWithOneReverseAndCentresTheCameraOnce` (not duplicated here).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class QaNav011ActivityTest {
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
        ShadowLog.clear()
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
        scenario!!.onActivity { a -> v = ViewModelProvider(a)[AppViewModel::class.java] }
        return v!!
    }

    private val lm: LocationManager get() = app.getSystemService(LocationManager::class.java)

    /** [n] platform fixes 1 s apart at [kmh] heading east from [from]; returns the last position. */
    private fun drive(kmh: Double, n: Int, from: LatLon): LatLon {
        var p = from
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
        return p
    }

    /** TC-A11: AC 27 (1 Hz, foreground only, stops ≤ 2 s) and AC 28 (0 requests, 0 coordinates/speeds in logs or storage). */
    @Test
    fun tcA11_lockSessionStaysOnTheDeviceAndStopsWithTheScreen() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setLocationEnabled(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(500)

        val requests = shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER)
        assertTrue("AC 27: a GPS request while the map screen is in the foreground", requests.isNotEmpty())
        assertTrue("AC 27: 1 Hz (${requests.map { it.intervalMillis }})", requests.all { it.intervalMillis == 1_000L })

        val pathsBefore = FakeGateway.paths.toList()
        val start = LatLon(47.9189, 106.9176)
        val p1 = drive(20.0, 4, start)
        waitFor { vm().typingLock.state.value.engaged }
        drive(2.0, 12, p1)
        waitFor { !vm().typingLock.state.value.engaged }
        assertEquals("AC 28: the lock adds 0 network requests", pathsBefore, FakeGateway.paths.toList())

        // AC 28 scan: the driven positions (47.918…/106.917…–106.919…) and speeds (5.5556 m/s, 20 km/h, 0.5556 m/s).
        val needles = Regex("47[.,]91[0-9]|106[.,]91[0-9]|5[.,]555|5[.,]556|0[.,]555|0[.,]556")
        val dataDir = File(app.applicationInfo.dataDir)
        val storedHits = dataDir.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            needles.find(text)?.let { "${f.relativeTo(dataDir)}: ${it.value}" }
        }.toList()
        assertTrue("AC 28: storage holds no coordinates or speeds: $storedHits", storedHits.isEmpty())
        val logHits = ShadowLog.getLogs().filter { needles.containsMatchIn(it.msg ?: "") }.map { "${it.tag}: ${it.msg}" }
        assertTrue("AC 28: logs hold no coordinates or speeds: $logHits", logHits.isEmpty())

        // AC 27: the screen leaves the foreground → updates stop within 2 s; back in the foreground → they resume.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        idle(2_000)
        assertTrue(
            "AC 27: no GPS listener 2 s after leaving the foreground (${shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size})",
            shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty(),
        )
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        idle(1_000)
        assertFalse("AC 27: updates resume with the map screen", shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }

    // ------------------------------------------------------------------------------- D140 typed coordinates (run 2)

    private val field = hasContentDescription("Газар, хаяг хайх")
    private fun exists(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun searchUrls() = FakeGateway.urls.filter { it.encodedPath == "/v1/search" }
    private fun reverseUrls() = FakeGateway.urls.filter { it.encodedPath == "/v1/reverse" }
    private fun options() = compose.onAllNodesWithTag("search-coordinate-option").fetchSemanticsNodes().size

    /** AC 7 / AC 39 oracle from the story text (same as QaNav011CoordinateTest; independent of the app's parser). */
    private fun recognisedPair(q: String): Boolean {
        val s = q.replace(Regex("[\\s\\u00A0\\u202F\\uFEFF]+"), " ").trim()
        val m = Regex("^(-?[0-9]+(?:\\.[0-9]+)?)(?: ?, ?| )(-?[0-9]+(?:\\.[0-9]+)?)$").matchEntire(s) ?: return false
        return m.groupValues[1].toDouble() in -90.0..90.0 && m.groupValues[2].toDouble() in -180.0..180.0
    }

    private fun typeAndSettle(text: String, first: Boolean) {
        if (first) {
            compose.onNode(field).performClick()
            compose.onNode(field).performTextInput(text)
        } else {
            compose.onNode(field).performTextReplacement(text)
        }
        idle(700) // debounce 250 ms + loading-row delay 300 ms
    }

    /**
     * TC-A13, AC 7, 7a, 8, 12, 13 at a second point, typed in Mongolian, then the UI switched to English with the option
     * shown: the option shows «Selected point» and
     * "47.88612, 106.90547"; selecting it sends exactly 1 `reverse` with lat 47.886123 / lon 106.905467 (typed values,
     * 6 decimals), `lang=en`, `limit=1`, `radius=0.5`, moves the camera once to that point at zoom 16 (fake camera zoom
     * 12), closes the list; then a long-press elsewhere sends exactly 1 more `reverse` and moves the camera 0 times.
     * 0 `search` requests in the whole case.
     */
    @Test
    fun tcA13_typedOptionAtASecondPointInEnglishThenLongPress() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        typeAndSettle("47.886123, 106.905467", first = true)
        // AC 13: switch to English with the option shown; the label follows within 1 s.
        vm().setLanguage(Lang.EN)
        waitFor(1_000) { exists("Selected point") }
        idle(300)
        val point = LatLon(47.886123, 106.905467)
        assertEquals(SearchView.Coordinate(point), vm().search.view.value)
        assertEquals("AC 7: exactly one option", 1, options())
        assertTrue("AC 7 / AC 13: English label", exists("Selected point"))
        assertTrue("AC 7: 5 decimals, point separator", exists("47.88612, 106.90547"))
        assertEquals("AC 7: 0 search, AC 12: showing the option sends 0 reverse", 0, searchUrls().size + reverseUrls().size)

        compose.onNodeWithTag("search-coordinate-option").performClick()
        idle(300)
        assertEquals("AC 7a: the list closes", 0, options())
        assertFalse("AC 7a: the search field closes", vm().ui.value.searchActive)
        assertEquals(point, vm().ui.value.card)
        waitFor { reverseUrls().size == 1 }
        idle(500)
        val r = reverseUrls().single()
        assertEquals("AC 8: typed lat, not rounded", "47.886123", r.queryParameter("lat"))
        assertEquals("AC 8: typed lon, not rounded", "106.905467", r.queryParameter("lon"))
        assertEquals("AC 8: lang = UI language", "en", r.queryParameter("lang"))
        assertEquals("1", r.queryParameter("limit"))
        assertEquals("0.5", r.queryParameter("radius"))
        waitFor { FakeCamera.focuses.any { it.p == point } }
        val moves = FakeCamera.focuses.filter { it.p == point }
        assertEquals("AC 7a: one camera move to the typed point", 1, moves.size)
        assertEquals("AC 7a / D142: max(12, 16)", 16.0, moves.single().zoom, 0.0)

        val pressed = LatLon(47.9200, 106.9000)
        val focusesBefore = FakeCamera.focuses.size
        var ok = false
        scenario!!.onActivity { ok = RecordingMapSurface.longPress(pressed) }
        assertTrue(ok)
        waitFor { reverseUrls().size == 2 }
        idle(1_000)
        assertEquals("AC 8 / AC 12: exactly one reverse per card", 2, reverseUrls().size)
        assertEquals("47.920000", reverseUrls().last().queryParameter("lat"))
        assertTrue(
            "AC 9: a long-press moves the camera 0 times (${FakeCamera.focuses.drop(focusesBefore)})",
            FakeCamera.focuses.drop(focusesBefore).none { it.p == pressed || it.p == point },
        )
        assertEquals("0 search requests", 0, searchUrls().size)
    }

    /**
     * TC-A14, AC 7 offline + AC 11 on the typed card: with no validated network the option still shows (0 requests);
     * selecting it opens the card «Сонгосон цэг» with «Интернэт холболт алга» in the nearest-place area, 0 `reverse`;
     * «Маршрут гаргах» stays. When the network returns while the card is open, exactly 1 `reverse` is sent within 2 s.
     */
    @Test
    fun tcA14_offlineTypedOptionOpensTheCardAndReverseFollowsTheNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, ShadowNetworkCapabilities.newInstance())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        typeAndSettle("47.9189, 106.9176", first = true)
        assertEquals(SearchView.Coordinate(LatLon(47.9189, 106.9176)), vm().search.view.value)
        assertEquals("AC 7 offline: one option", 1, options())
        compose.onNodeWithTag("search-coordinate-option").performClick()
        idle(800)
        assertEquals(LatLon(47.9189, 106.9176), vm().ui.value.card)
        assertTrue("AC 11: «Интернэт холболт алга» on the card", exists("Интернэт холболт алга"))
        assertTrue(exists("Сонгосон цэг"))
        assertTrue("«Маршрут гаргах» stays", exists("Маршрут гаргах"))
        assertEquals("offline: 0 search, 0 reverse", 0, searchUrls().size + reverseUrls().size)

        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        shadowOf(cm).networkCallbacks.forEach { it.onCapabilitiesChanged(cm.activeNetwork!!, caps) }
        waitFor(2_000) { reverseUrls().size == 1 }
        idle(500)
        assertEquals("AC 11: exactly one reverse after the network returns", 1, reverseUrls().size)
        assertEquals("47.918900", reverseUrls().single().queryParameter("lat"))
        assertEquals(0, searchUrls().size)
    }

    /**
     * TC-P39 (AC 39 restored oracle, D140): a session with text, every recognised form, the rejected pairs, the option
     * selected and «Маршрут гаргах». Captured requests: no `search` `q` is a recognised pair; the rejected pairs appear
     * as typed (user text, allowed); every `search` keeps the D30 bias at 3 decimals; `reverse` carries only the selected
     * point; «Маршрут гаргах» adds no `search` / `reverse`. The full-feature capture (alternatives, «Дугуй» guidance, lock) stays full QA.
     */
    @Test
    fun tcP39_sessionNeverSendsARecognisedPairInQ() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle(300)
        val inputs = listOf(
            "Зайсан", "47.9189, 106.9176", "106.9176, 47.9189", "47.9189,106.9176", "47,9189, 106,9176",
            "-45.5 -170", "47 106", "47.9189,\u00A0106.9176", "Сүхбаатар", "47.9189 106.9176",
        )
        inputs.forEachIndexed { i, q ->
            typeAndSettle(q, first = i == 0)
            if (recognisedPair(q)) {
                assertTrue("«$q» is the option (${vm().search.view.value})", vm().search.view.value is SearchView.Coordinate)
            } else {
                waitFor { vm().search.view.value !is SearchView.Loading && vm().search.view.value !is SearchView.Closed }
            }
        }
        compose.onNodeWithTag("search-coordinate-option").performClick()
        waitFor { reverseUrls().size == 1 }
        idle(300)
        compose.onNode(hasText("Маршрут гаргах")).performClick()
        idle(500)
        // No device fix in this case, so the preview waits for the origin; the route POST body is out of this scan.
        assertEquals("«Маршрут гаргах» closes the card (preview opens)", null, vm().ui.value.card)

        val qs = searchUrls().map { it.queryParameter("q") ?: "" }
        assertTrue("AC 39: recognised pairs in q: ${qs.filter { recognisedPair(it) }}", qs.none { recognisedPair(it) })
        assertTrue("rejected pairs are user text, as typed ($qs)", qs.containsAll(listOf("106.9176, 47.9189", "47,9189, 106,9176")))
        assertTrue("text was searched ($qs)", qs.contains("Зайсан"))
        for (u in searchUrls()) {
            val lat = u.queryParameter("lat")
            val lon = u.queryParameter("lon")
            assertTrue("D30 bias ≤ 3 decimals ($lat, $lon)", (lat == null || Regex("^-?[0-9]+(\\.[0-9]{1,3})?$").matches(lat)) && (lon == null || Regex("^-?[0-9]+(\\.[0-9]{1,3})?$").matches(lon)))
        }
        assertEquals("reverse only at the selected point", listOf("47.918900" to "106.917600"), reverseUrls().map { it.queryParameter("lat") to it.queryParameter("lon") })
        val unknown = FakeGateway.paths.toSet() - setOf("/v1/search", "/v1/reverse", "/v1/route")
        assertTrue("no other endpoint: $unknown", unknown.isEmpty())
    }
}
