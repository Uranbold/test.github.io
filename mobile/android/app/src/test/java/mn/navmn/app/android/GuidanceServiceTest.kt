package mn.navmn.app.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.service.GuidanceForegroundService
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Robolectric + Hilt: foreground service of type location with channel «Замчлал», notification text from our
 * resources that follows the engine without any Activity, «Дуусгах» from the notification and swipe-away end
 * everything within 2 s with 0 route requests (AC 13, 15, 17, 19, 20).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "mn")
class GuidanceServiceTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var session: GuidanceSession

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
    private val trip = Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false)

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        RecordingRequester.requests.clear()
    }

    private fun grantNotifications() = shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun fix() = Fix(47.9189, 106.9176, 5.0, 180.0, 10.0, 10.0, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())

    private fun waitUntil(timeoutMs: Long = 2_000, cond: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun serviceNotificationFollowsTheEngineAndEndStopsEverything() {
        grantNotifications()
        session.start(route, trip, fix())
        val started = shadowOf(context as Application).nextStartedService
        assertEquals(GuidanceForegroundService::class.java.name, started.component!!.className)
        waitUntil { session.engine.value?.state?.value != null }
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java, started).create().startCommand(0, 1)
        val service = controller.get()
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(GuidanceForegroundService.CHANNEL_ID)
        assertEquals("Замчлал", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        waitUntil {
            val n = shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)
            n?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() == "Зүүн тийш эргэнэ үү"
        }
        val n = shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)
        assertEquals("300 м · Дүнжингаравын гудамж", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Дуусгах", n.actions.single().title.toString())
        assertEquals(GuidancePhase.NAVIGATING, session.engine.value!!.state.value!!.phase)

        // «Дуусгах» in the notification → everything stops within 2 s, 0 route requests.
        controller.withIntent(Intent(context, GuidanceForegroundService::class.java).setAction(GuidanceForegroundService.ACTION_END)).startCommand(0, 2)
        val ms = waitUntil { session.engine.value == null }
        assertTrue("ended in $ms ms", ms <= 2_000)
        assertNotNull(shadowOf(context as Application).nextStoppedService)
        assertEquals(0, RecordingRequester.requests.size)
        controller.destroy()
        assertNull(shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID))
    }

    @Test
    fun notificationPermissionDeniedGuidanceStillRuns() {
        // AC 13: POST_NOTIFICATIONS denied (Android 13+): the service and guidance run; the notification is not updated.
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        session.start(route, trip, fix())
        val started = shadowOf(context as Application).nextStartedService
        waitUntil { session.engine.value?.state?.value != null }
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java, started).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(GuidancePhase.NAVIGATING, session.engine.value!!.state.value!!.phase)
        val nm = context.getSystemService(NotificationManager::class.java)
        val title = shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        assertTrue("no guidance text posted: $title", title != "Зүүн тийш эргэнэ үү")
        session.end()
        waitUntil { session.engine.value == null }
        controller.destroy()
    }

    @Test
    fun swipeAwayEndsGuidance() {
        session.start(route, trip, fix())
        val started = shadowOf(context as Application).nextStartedService
        waitUntil { session.engine.value?.state?.value != null }
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java, started).create().startCommand(0, 1)
        controller.get().onTaskRemoved(Intent())
        val ms = waitUntil(5_000) { session.engine.value == null }
        assertTrue(ms <= 5_000)
        assertEquals(0, RecordingRequester.requests.size)
    }
}
