package mn.navmn.app.qa

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.android.RecordingRequester
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.service.GuidanceForegroundService
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import javax.inject.Inject

/**
 * QA, NAV-012 test plan TC-F01 (AC 32, light-QA run 1), Robolectric + Hilt like `GuidanceServiceTest`: while guidance
 * runs the service listens for `ACTION_AUDIO_BECOMING_NOISY` (headphones / car output disconnected); the broadcast
 * neither ends guidance nor changes the voice setting nor sends a route request; the listener is gone after the end.
 * "The prompt in progress stops within 1 s" needs a stop counter in the shared `RecordingVoice` fake (request to the
 * mobile engineer); here only the receiver path is checked.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "mn")
class QaNav012ServiceTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var session: GuidanceSession
    @Inject lateinit var settings: SettingsRepository

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
    private val trip = Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false)

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        RecordingRequester.requests.clear()
    }

    private fun fix() = Fix(47.9189, 106.9176, 5.0, 180.0, 10.0, 10.0, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())

    private fun waitUntil(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
    }

    private fun noisyReceivers(): Int = shadowOf(context as Application).registeredReceivers.count { w ->
        w.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    }

    /** TC-F01 (AC 32): output disconnect during guidance keeps guidance, mute state and 0 requests. */
    @Test
    fun tcF01_outputDisconnectKeepsGuidanceAndVoiceSetting() {
        val mutedBefore = settings.muted.value
        session.start(route, trip, fix())
        val started = shadowOf(context as Application).nextStartedService
        waitUntil { session.engine.value?.state?.value != null }
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java, started).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals("one AUDIO_BECOMING_NOISY listener while guiding", 1, noisyReceivers())
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        Thread.sleep(100)
        assertNotNull("guidance continues", session.engine.value)
        assertEquals(GuidancePhase.NAVIGATING, session.engine.value!!.state.value!!.phase)
        assertEquals("no automatic mute or unmute", mutedBefore, settings.muted.value)
        assertEquals("0 route requests", 0, RecordingRequester.requests.size)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        session.end()
        waitUntil { session.engine.value == null }
        controller.destroy()
        assertTrue("listener unregistered after the end", noisyReceivers() == 0)
    }
}
