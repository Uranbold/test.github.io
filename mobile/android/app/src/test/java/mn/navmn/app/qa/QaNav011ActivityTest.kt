package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
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
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
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
}
