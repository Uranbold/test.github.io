package mn.navmn.app.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import mn.navmn.app.background.restore.InterruptedEndReceiver
import mn.navmn.app.background.restore.InterruptedNotification
import mn.navmn.app.background.restore.RestoreCodec
import mn.navmn.app.background.restore.RestoreDestination
import mn.navmn.app.background.restore.RestoreMeta
import mn.navmn.app.background.restore.RestoreStore
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Progress
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.lockscreen.LockScreenGate
import mn.navmn.app.lockscreen.LockScreenPolicy
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.service.GuidanceForegroundService
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.time.Duration
import javax.inject.Inject

/**
 * NAV-012 Android glue under Robolectric + Hilt (AC 3, 13–17, 22, 25, 34, 47, 48): voice action, restore record write
 * and delete, the null-intent restart per API level (SDK 35: «Замчлал тасарлаа», 0 location requests; SDK 28: silent
 * resume), «Дуусгах» on N2, the merged-manifest scan and the lock-screen gate flags and volume stream.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "mn")
class Nav012AndroidTest {
    private companion object {
        /** Untimed waits only (set-up, notification re-post); the AC 3 ≤ 1 s assertion is unchanged. */
        const val SETTLE_TIMEOUT_MS = 10_000L
    }

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var session: GuidanceSession
    @Inject lateinit var settings: SettingsRepository

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as Application
    private val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
    private val trip = Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false)
    private val recordDir get() = File(context.noBackupFilesDir, RestoreStore.DIR)

    @Before
    fun setUp() {
        hilt.inject()
        FakeLocation.reset()
        RecordingRequester.requests.clear()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        RestoreStore(recordDir).delete()
    }

    @After
    fun tearDown() {
        session.end()
        RestoreStore(recordDir).delete()
    }

    private fun fix() = Fix(47.9189, 106.9176, 5.0, 180.0, 10.0, 10.0, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean): Long {
        val start = System.nanoTime()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) throw AssertionError("condition not met within $timeoutMs ms")
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    private val nm get() = context.getSystemService(NotificationManager::class.java)

    /** Idles the main looper and lets background threads (engine, DataStore IO) run for [ms] of real time. */
    private fun settle(ms: Long = 250) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun voiceActionTogglesMuteWithoutOpeningTheAppOrEndingGuidance() {
        // Deterministic start: the DataStore-backed flow starts at `false` before its first read, so a bare
        // setMuted(false) + wait could pass on that initial value while an older stored value is still on its way
        // (and would then flip the toggle). Write true, see it, write false, see it: the false is the persisted value.
        // This also warms the DataStore write path before the timed toggle.
        runBlocking { settings.setMuted(true).join() }
        waitUntil(SETTLE_TIMEOUT_MS) { settings.muted.value }
        runBlocking { settings.setMuted(false).join() }
        waitUntil(SETTLE_TIMEOUT_MS) { !settings.muted.value }
        session.start(route, trip, fix())
        val started = shadowOf(app).nextStartedService
        waitUntil(SETTLE_TIMEOUT_MS) { session.engine.value?.state?.value != null }
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java, started).create().startCommand(0, 1)
        waitUntil(SETTLE_TIMEOUT_MS) { shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)?.actions?.firstOrNull()?.title?.toString() == "Дууг хаах" }
        // The engine subscribes to the mute setting right after its first state, on its own thread: let it and the main
        // looper settle before the timed toggle, so the toggle is not dropped as the subscription's initial value.
        settle()
        val ms = run {
            controller.withIntent(Intent(context, GuidanceForegroundService::class.java).setAction(GuidanceForegroundService.ACTION_TOGGLE_VOICE)).startCommand(0, 2)
            waitUntil(SETTLE_TIMEOUT_MS) { session.engine.value?.state?.value?.muted == true }
        }
        assertTrue("muted in $ms ms (AC 3: ≤ 1 s)", ms <= 1_000)
        assertTrue(settings.muted.value)
        waitUntil(SETTLE_TIMEOUT_MS) { shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)?.actions?.firstOrNull()?.title?.toString() == "Дууг нээх" }
        assertNull("the guidance screen is not opened", shadowOf(app).nextStartedActivity)
        assertEquals(GuidancePhase.NAVIGATING, session.engine.value!!.state.value!!.phase)
        settings.setMuted(false)
        session.end()
        waitUntil { session.engine.value == null }
        controller.destroy()
    }

    /** AC 1, 4, 5, 12: N1 flags (silent, private with public version «Замчлал», delete intent), language switch ≤ 2 s. */
    @Test
    fun notificationFlagsPublicVersionDeleteIntentAndLanguageSwitch() {
        session.start(route, trip, fix())
        val started = shadowOf(app).nextStartedService
        waitUntil { session.engine.value?.state?.value != null }
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java, started).create().startCommand(0, 1)
        waitUntil { shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT) != null }
        val n = shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Замчлал", n.publicVersion.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertNull(n.publicVersion.extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
        assertEquals(NotificationCompat.CATEGORY_NAVIGATION, n.category)
        assertTrue(n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNotNull("large icon = manoeuvre bitmap", n.getLargeIcon())
        val delete = shadowOf(n.deleteIntent)
        assertEquals(GuidanceForegroundService.ACTION_DISMISSED, delete.savedIntent.action)
        // AC 4: a language switch renames the channel and re-posts the texts within 2 s, 0 route requests.
        settings.setLanguage(mn.navmn.app.i18n.Lang.EN)
        val ms = waitUntil {
            nm.getNotificationChannel(GuidanceForegroundService.CHANNEL_ID).name == "Navigation" &&
                shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID)?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() == "Turn left"
        }
        assertTrue("language switch in $ms ms", ms <= 2_000)
        assertEquals(listOf("Mute", "End"), shadowOf(nm).getNotification(GuidanceForegroundService.NOTIFICATION_ID).actions.map { it.title.toString() })
        assertEquals(0, RecordingRequester.requests.size)
        settings.setLanguage(mn.navmn.app.i18n.Lang.MN)
        session.end()
        waitUntil { session.engine.value == null }
        controller.destroy()
    }

    /** AC 16, 17, 47: the record exists only between «Эхлэх» and the normal end; then 0 trip data in storage. */
    @Test
    fun restoreRecordWrittenAtStartDeletedAtEndPrivacyScan() {
        session.start(route, trip, fix())
        val ms = waitUntil { File(recordDir, RestoreStore.META).isFile && File(recordDir, RestoreStore.ROUTE).isFile }
        assertTrue("written in $ms ms (AC 16: ≤ 2 s)", ms <= 2_000)
        val meta = RestoreCodec.decode(File(recordDir, RestoreStore.META).readBytes())!!
        assertEquals("Зайсан", meta.destination.text)
        assertEquals("auto", meta.costing)
        session.end()
        waitUntil { session.engine.value == null }
        assertFalse("deleted at the normal end", recordDir.exists())
        val coordinate = Regex("-?\\d{1,3}\\.\\d{4,}")
        val dirs = listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir, File(context.dataDir, "shared_prefs"))
        val hits = dirs.flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }.filter { f ->
            val t = runCatching { f.readBytes().decodeToString() }.getOrDefault("")
            coordinate.containsMatchIn(t) || t.contains("Зайсан") || t.contains("\"routes\"")
        }
        assertEquals(hits.toString(), 0, hits.size)
    }

    private fun writeRecord(heartbeat: Long = System.currentTimeMillis()) {
        val bytes = route.source!!
        RestoreStore(recordDir).writeAll(
            RestoreMeta(
                schema = RestoreMeta.SCHEMA,
                destination = RestoreDestination(trip.destination.lat, trip.destination.lon, trip.destinationName),
                costing = "auto",
                avoidUnpaved = false,
                language = "mn",
                startedAtWallMs = heartbeat - 60_000,
                heartbeatWallMs = heartbeat,
                routeSha256 = RestoreCodec.sha256(bytes),
            ),
            bytes,
        )
    }

    /** AC 22 on API 30+ (here SDK 35): no location FGS, «Замчлал тасарлаа» within 5 s, 0 location requests, stop. */
    @Test
    fun nullIntentRestartOnModernAndroidPostsInterruptedNotification() {
        writeRecord()
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        waitUntil { shadowOf(nm).getNotification(InterruptedNotification.ID) != null }
        val n = shadowOf(nm).getNotification(InterruptedNotification.ID)
        assertEquals("Замчлал тасарлаа", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Үргэлжлүүлэхийн тулд дарна уу", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(listOf("Үргэлжлүүлэх", "Дуусгах"), n.actions.map { it.title.toString() })
        assertTrue("not ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0)
        assertTrue(n.timeoutAfter in 1..30 * 60_000L)
        assertNull("no foreground service", shadowOf(service).lastForegroundNotification)
        assertEquals(0, FakeLocation.guidanceListeners.get())
        assertNull(session.engine.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        // The content intent opens the activity directly (no trampoline).
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
        assertEquals(InterruptedNotification.ACTION_RESTORE, shadowOf(n.contentIntent).savedIntent.action)
        // «Дуусгах» on N2 → record deleted and notification removed (AC 22, AC 17).
        val end = shadowOf(n.actions[1].actionIntent)
        assertTrue(end.isBroadcastIntent)
        InterruptedEndReceiver().onReceive(context, end.savedIntent)
        assertFalse(recordDir.exists())
        assertNull(shadowOf(nm).getNotification(InterruptedNotification.ID))
        controller.destroy()
    }

    /** AC 22 on API 26–29 (here SDK 28): the system restart resumes guidance silently, as AC 19, without the app. */
    @Test
    @Config(sdk = [28])
    fun nullIntentRestartOnAndroid9ResumesSilently() {
        writeRecord()
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        waitUntil { session.engine.value?.state?.value != null }
        val s = session.engine.value!!.state.value!!
        assertEquals(Banner.Restoring, s.banner)
        assertFalse("no notice on the silent restart", s.resumedNoticeVisible)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertNull(shadowOf(nm).getNotification(InterruptedNotification.ID))
        assertEquals(0, RecordingRequester.requests.size)
        session.end()
        waitUntil { session.engine.value == null }
        controller.destroy()
    }

    @Test
    fun expiredRecordIsDeletedOnRestartWithoutANotification() {
        writeRecord(heartbeat = System.currentTimeMillis() - 31 * 60_000L)
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        waitUntil { !recordDir.exists() }
        assertNull(shadowOf(nm).getNotification(InterruptedNotification.ID))
        assertNull(session.engine.value)
        controller.destroy()
    }

    /** AC 15: with no guidance, removing the app from Recents leaves no service running. */
    @Test
    fun swipeAwayWithoutGuidanceStopsTheService() {
        val controller = Robolectric.buildService(GuidanceForegroundService::class.java).create()
        controller.get().onTaskRemoved(Intent())
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    /** AC 25, 48: the merged manifest has none of the forbidden permissions; the N2 receiver is not exported. */
    @Test
    fun mergedManifestScan() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toSet() ?: emptySet()
        for (p in listOf(
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.READ_PHONE_STATE", "android.permission.READ_CALL_LOG",
            "android.permission.BLUETOOTH_CONNECT", "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.WAKE_LOCK",
        )) {
            assertFalse("$p must not be requested", p in requested)
        }
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, InterruptedEndReceiver::class.java), 0)
        assertFalse(receiver.exported)
    }

    private fun state(phase: GuidancePhase) = GuidanceState(
        phase, 0, Banner.Rerouting(null), Progress(1.0, 1.0, 0), null, emptyList(), trip,
        gpsLost = false, gpsRestoredVisible = false, offline = false, voiceNoticeVisible = false, muted = false, speedMps = 0.0,
    )

    /** AC 8–11, 34: over the lock screen only while guiding or on the arrival panel; unlock prompt before leaving. */
    @Test
    fun lockScreenGateFlagsUnlockPromptAndVolumeStream() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val gate = LockScreenGate(activity)
        val keyguard = shadowOf(activity.getSystemService(KeyguardManager::class.java))
        assertFalse(shadowOf(activity).showWhenLocked)
        gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
        assertTrue(shadowOf(activity).showWhenLocked)
        assertFalse("never turns the screen on (AC 12)", shadowOf(activity).turnScreenOn)
        assertEquals(AudioManager.STREAM_MUSIC, activity.volumeControlStream)
        gate.onGuidanceState(state(GuidancePhase.ARRIVED))
        assertTrue("arrival panel stays over the lock screen (AC 10)", shadowOf(activity).showWhenLocked)
        // Locked: «Тохиргоо» asks for the unlock prompt first; nothing happens until it succeeds (AC 9).
        keyguard.setKeyguardLocked(true)
        var opened = 0
        gate.requireUnlocked { opened++ }
        assertEquals(0, opened)
        keyguard.setKeyguardLocked(false)
        assertEquals(1, opened)
        // Unlocked: runs at once.
        gate.requireUnlocked { opened++ }
        assertEquals(2, opened)
        // Guidance ends while locked → flag cleared, task to the back (the phone's lock screen, not the map, AC 9–11).
        keyguard.setKeyguardLocked(true)
        gate.onGuidanceState(null)
        assertFalse(shadowOf(activity).showWhenLocked)
        assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, activity.volumeControlStream)
        assertTrue(shadowOf(activity).isTaskMovedToBack)
    }

    /**
     * AC 10–11 (ADR-0013 Amendment 1): the arrival panel stays over the lock screen until the screen turns off. onStop on
     * ARRIVED with the keyguard locked clears the flag for the rest of the session; the panel is not ended, the task is
     * not moved to the back, and later arrival updates do not set the flag again. A new session starts cleared.
     */
    @Test
    fun arrivalStopsShowingOverLockScreenAfterScreenOff() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val gate = LockScreenGate(activity)
        val keyguard = shadowOf(activity.getSystemService(KeyguardManager::class.java))
        gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
        // Navigating: onStop while locked keeps the guidance screen over the lock screen (AC 8).
        keyguard.setKeyguardLocked(true)
        gate.onStop()
        assertTrue(shadowOf(activity).showWhenLocked)
        gate.onGuidanceState(state(GuidancePhase.ARRIVED))
        assertTrue("arrival panel over the lock screen until the screen turns off (AC 10)", shadowOf(activity).showWhenLocked)
        gate.onStop()
        assertFalse("ARRIVED + onStop while locked clears the flag (AC 10, 11)", shadowOf(activity).showWhenLocked)
        assertTrue(gate.arrivalScreenOffSeen)
        assertFalse("the panel stays for after the unlock", shadowOf(activity).isTaskMovedToBack)
        assertFalse(shadowOf(activity).turnScreenOn)
        // Later arrival updates in the same session never set it again.
        gate.onGuidanceState(state(GuidancePhase.ARRIVED))
        assertFalse(shadowOf(activity).showWhenLocked)
        // «Хаах» ends the session; the next session shows over the lock screen again.
        gate.onGuidanceState(null)
        assertFalse(shadowOf(activity).showWhenLocked)
        assertEquals(AudioManager.USE_DEFAULT_STREAM_TYPE, activity.volumeControlStream)
        gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
        assertTrue(shadowOf(activity).showWhenLocked)
        assertFalse(gate.arrivalScreenOffSeen)
    }

    /** AC 10 (ADR-0013 Amendment 1): the display turning off before the keyguard locks (lock delay) also counts. */
    @Test
    fun arrivalScreenOffWithoutKeyguardClearsFlag() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val gate = LockScreenGate(activity)
        gate.onGuidanceState(state(GuidancePhase.ARRIVED))
        // Unlocked and interactive (the user switched apps): the flag stays.
        gate.onStop()
        assertTrue(shadowOf(activity).showWhenLocked)
        shadowOf(activity.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        gate.onStop()
        assertFalse(shadowOf(activity).showWhenLocked)
    }

    /** Pure policy (ADR-0013 Amendment 1). */
    @Test
    fun lockScreenPolicyArrivalLatch() {
        assertFalse(LockScreenPolicy.showWhenLocked(null))
        assertFalse(LockScreenPolicy.showWhenLocked(state(GuidancePhase.ENDED)))
        assertTrue(LockScreenPolicy.showWhenLocked(state(GuidancePhase.NAVIGATING), arrivalScreenOffSeen = true))
        assertTrue(LockScreenPolicy.showWhenLocked(state(GuidancePhase.ARRIVED), arrivalScreenOffSeen = false))
        assertFalse(LockScreenPolicy.showWhenLocked(state(GuidancePhase.ARRIVED), arrivalScreenOffSeen = true))
        assertTrue(LockScreenPolicy.arrivalLeftWhileLocked(state(GuidancePhase.ARRIVED), keyguardLocked = true, interactive = true))
        assertTrue(LockScreenPolicy.arrivalLeftWhileLocked(state(GuidancePhase.ARRIVED), keyguardLocked = false, interactive = false))
        assertFalse(LockScreenPolicy.arrivalLeftWhileLocked(state(GuidancePhase.ARRIVED), keyguardLocked = false, interactive = true))
        assertFalse(LockScreenPolicy.arrivalLeftWhileLocked(state(GuidancePhase.NAVIGATING), keyguardLocked = true, interactive = false))
    }

    /** Pure policy (ADR-0013 Amendment 3, QA D3): arrival while the panel cannot be seen latches at once. Table test. */
    @Test
    fun lockScreenPolicyArrivalUnseenTable() {
        data class Row(val phase: GuidancePhase?, val started: Boolean, val interactive: Boolean, val expected: Boolean)
        val table = listOf(
            // ARRIVED: unseen when the activity is stopped or the display is off.
            Row(GuidancePhase.ARRIVED, started = true, interactive = true, expected = false),
            Row(GuidancePhase.ARRIVED, started = true, interactive = false, expected = true),
            Row(GuidancePhase.ARRIVED, started = false, interactive = true, expected = true),
            Row(GuidancePhase.ARRIVED, started = false, interactive = false, expected = true),
            // Any other phase, or no session: never.
            Row(GuidancePhase.NAVIGATING, started = false, interactive = false, expected = false),
            Row(GuidancePhase.NAVIGATING, started = true, interactive = true, expected = false),
            Row(GuidancePhase.ENDED, started = false, interactive = false, expected = false),
            Row(null, started = false, interactive = false, expected = false),
        )
        for (r in table) {
            val st = r.phase?.let { state(it) }
            assertEquals("phase=${r.phase} started=${r.started} interactive=${r.interactive}", r.expected, LockScreenPolicy.arrivalUnseen(st, r.started, r.interactive))
        }
        // Every phase is covered for the "seen" case (started + interactive → never unseen).
        for (phase in GuidancePhase.entries) {
            assertFalse("$phase started+interactive", LockScreenPolicy.arrivalUnseen(state(phase), activityStarted = true, interactive = true))
        }
    }

    /**
     * ADR-0013 Amendment 3 (gate): guidance over the lock screen, the display turns off, the trip arrives → the flag is
     * cleared at once and latched; the task is not moved to the back, the screen is never turned on, volume keys stay
     * on the prompt stream; later arrival updates keep it off; a new session starts cleared.
     */
    @Test
    fun arrivalWhileDisplayOffLatchesAtOnce() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val gate = LockScreenGate(activity)
        val keyguard = shadowOf(activity.getSystemService(KeyguardManager::class.java))
        val power = shadowOf(activity.getSystemService(PowerManager::class.java))
        keyguard.setKeyguardLocked(true)
        gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
        power.setIsInteractive(false)
        gate.onStop()
        assertTrue("AC 8: still over the lock screen while guiding", shadowOf(activity).showWhenLocked)
        gate.onGuidanceState(state(GuidancePhase.ARRIVED))
        assertFalse("AC 11: arrived while the display is off → not over the lock screen on wake", shadowOf(activity).showWhenLocked)
        assertTrue(gate.arrivalScreenOffSeen)
        assertFalse("the arrival panel stays for after the unlock", shadowOf(activity).isTaskMovedToBack)
        assertFalse("AC 12", shadowOf(activity).turnScreenOn)
        assertEquals(AudioManager.STREAM_MUSIC, activity.volumeControlStream)
        // Wake: later ARRIVED emissions keep the flag off.
        power.setIsInteractive(true)
        gate.onGuidanceState(state(GuidancePhase.ARRIVED))
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).isTaskMovedToBack)
        // «Хаах» ends the session; the next one shows over the lock screen again.
        gate.onGuidanceState(null)
        gate.onGuidanceState(state(GuidancePhase.NAVIGATING))
        assertTrue(shadowOf(activity).showWhenLocked)
        assertFalse(gate.arrivalScreenOffSeen)
    }
}
