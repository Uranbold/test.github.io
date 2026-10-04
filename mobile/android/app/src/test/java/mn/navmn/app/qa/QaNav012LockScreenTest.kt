package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import mn.navmn.app.android.RecordingRequester
import mn.navmn.app.android.RecordingVoice
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Progress
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.lockscreen.LockScreenGate
import mn.navmn.app.route.TravelMode
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
import java.time.Duration

/**
 * QA, NAV-012 test plan TC-L01 / TC-L02 (AC 10, 11; ADR-0013 Amendment 1), Robolectric + Hilt on the real
 * [MainActivity], so the `onStop` hook is exercised through the real Activity lifecycle (screen off = the activity
 * moves to CREATED). The Hilt fake navigator cannot reach `ARRIVED`, so the guidance states are fed to the activity's own
 * [LockScreenGate] (the same call `MainActivity` makes from `vm.guidance.collect`, which is not lifecycle-gated and so
 * also runs while the activity is stopped).
 *
 * - TC-L01 (regression for D2, run 1): arrival panel over the lock screen, then the screen turns off → the
 *   show-over-lock flag is cleared and stays cleared for the session; nothing ends, task not moved back, screen never
 *   turned on; the next session shows over the lock screen again.
 * - TC-L02 (D3, run 2): guidance with the screen already off, then arrival while the activity is stopped. AC 10's
 *   exception needs "arrival while the guidance screen is shown over the lock screen"; AC 11 says an arrived session is
 *   never shown over the lock screen when the phone is woken. So the flag must be off before the next wake.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp")
class QaNav012LockScreenTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
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
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    private fun state(phase: GuidancePhase) = GuidanceState(
        phase, 0, Banner.Rerouting(null), Progress(1.0, 1.0, 0), null, emptyList(), trip,
        gpsLost = false, gpsRestoredVisible = false, offline = false, voiceNoticeVisible = false, muted = false, speedMps = 0.0,
    )

    private fun launch(): ActivityScenario<MainActivity> {
        val s = ActivityScenario.launch(MainActivity::class.java)
        scenario = s
        compose.waitForIdle()
        idle()
        return s
    }

    /** The activity's own gate (private field; read-only access for the test). */
    private fun gateOf(a: MainActivity): LockScreenGate =
        MainActivity::class.java.getDeclaredField("lockGate").apply { isAccessible = true }.get(a) as LockScreenGate

    private fun keyguardLocked(locked: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
    private fun interactive(on: Boolean) = shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(on)

    private fun onActivity(block: (MainActivity) -> Unit) = scenario!!.onActivity { block(it) }

    /** TC-L01, AC 10 + 11 (D2 regression): arrival panel over the lock screen until the screen turns off. */
    @Test
    fun tcL01_arrivalOverLockScreenStopsAfterScreenOff() {
        val s = launch()
        lateinit var gate: LockScreenGate
        onActivity { a ->
            gate = gateOf(a)
            keyguardLocked(true)
            gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
            assertTrue("AC 8: guidance over the lock screen", shadowOf(a).showWhenLocked)
            gate.onGuidanceState(state(GuidancePhase.ARRIVED))
            assertTrue("AC 10: arrival panel stays over the lock screen while the screen is on", shadowOf(a).showWhenLocked)
        }
        // Power button: display off, keyguard locked, activity stopped (real MainActivity.onStop).
        interactive(false)
        s.moveToState(Lifecycle.State.CREATED)
        idle()
        onActivity { a ->
            assertFalse("AC 10/11: after the screen turned off the arrival panel no longer shows over the lock screen", shadowOf(a).showWhenLocked)
            assertFalse("the panel is kept for after the unlock (task not moved back)", shadowOf(a).isTaskMovedToBack)
            assertFalse("AC 12: never turns the screen on", shadowOf(a).turnScreenOn)
            assertEquals("AC 34: volume keys still on the prompt stream while the arrival panel is open", AudioManager.STREAM_MUSIC, a.volumeControlStream)
        }
        // Wake + unlock: the arrival panel is still the session state; a repeated ARRIVED emission keeps the flag off.
        interactive(true)
        keyguardLocked(false)
        s.moveToState(Lifecycle.State.RESUMED)
        idle()
        onActivity { a ->
            gate.onGuidanceState(state(GuidancePhase.ARRIVED))
            assertFalse(shadowOf(a).showWhenLocked)
            // «Хаах» ends the session.
            gate.onGuidanceState(null)
            assertFalse("AC 11: off after the end", shadowOf(a).showWhenLocked)
            assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, a.volumeControlStream)
            // The next session is shown over the lock screen again (latch reset).
            gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
            assertTrue("AC 8 in the next session", shadowOf(a).showWhenLocked)
            gate.onGuidanceState(null)
        }
        assertEquals("0 route requests", 0, FakeGateway.routeRequests.get())
    }

    /** TC-L02, AC 10 + 11 (D3): arrival while the screen is already off must not show over the lock screen on wake. */
    @Test
    fun tcL02_arrivalWhileScreenOffIsNotShownOverLockScreenOnWake() {
        val s = launch()
        lateinit var gate: LockScreenGate
        onActivity { a ->
            gate = gateOf(a)
            keyguardLocked(true)
            gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
            assertTrue("AC 8: guidance over the lock screen", shadowOf(a).showWhenLocked)
        }
        // Screen turned off during guidance (AC 13 screen-off driving): activity stopped while NAVIGATING.
        interactive(false)
        s.moveToState(Lifecycle.State.CREATED)
        idle()
        onActivity { a ->
            assertTrue("AC 8: still allowed over the lock screen while guiding", shadowOf(a).showWhenLocked)
            // The engine arrives while the activity is stopped and the screen is off (vm.guidance → lockGate).
            gate.onGuidanceState(state(GuidancePhase.ARRIVED))
            assertFalse(
                "AC 11 (arrived → never shown over the lock screen when woken); AC 10's exception needs the arrival to happen " +
                    "while the guidance screen is shown over the lock screen. The flag must be off before the next wake",
                shadowOf(a).showWhenLocked,
            )
            assertFalse("AC 12: never turns the screen on", shadowOf(a).turnScreenOn)
            gate.onGuidanceState(null)
        }
    }
}
