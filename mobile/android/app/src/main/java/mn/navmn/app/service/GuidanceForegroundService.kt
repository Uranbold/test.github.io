package mn.navmn.app.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.navmn.app.background.battery.BatteryHint
import mn.navmn.app.background.restore.InterruptedNotification
import mn.navmn.app.background.restore.RestartPlan
import mn.navmn.app.background.restore.RestoreManager
import mn.navmn.app.background.restore.RestoreRules
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.location.LocationSource
import mn.navmn.app.service.notification.GuidanceChannel
import mn.navmn.app.service.notification.GuidanceNotificationBuilder
import mn.navmn.app.service.notification.NotificationPostPolicy
import mn.navmn.app.service.notification.RichNotification
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.theme.sun.SunTheme
import mn.navmn.app.theme.sun.ThemeResolver
import mn.navmn.app.voice.GuidanceVoice
import javax.inject.Inject

/**
 * ADR-0009 §9 as amended by ADR-0013 §2 and §4: the app's own foreground service of type `location` while guiding.
 *  - Started from the foreground by «Эхлэх» or a restore the user opened (a while-in-use permission suffices).
 *  - NAV-012 AC 13–15: removing the app from Recents does **not** end guidance; with no session the service stops.
 *  - `START_STICKY` while a session runs. A null-intent restart by the system follows the [RestartPlan] table:
 *    silent resume on API 26–29, the «Замчлал тасарлаа» notification (0 location requests) on API 30+ (AC 22).
 *  - N1 rich notification (AC 1–7): [RichNotification] content, [NotificationPostPolicy] cadence (state ≤ 1 s,
 *    distance every 2 s, ≤ 1 post/s, Android 14+ dismissal re-post at the next instruction change), voice action,
 *    language switch renames the channel and re-posts within 2 s.
 *  - AC 32: `ACTION_AUDIO_BECOMING_NOISY` during guidance stops the current prompt (not re-queued, mute unchanged).
 * With the notification permission denied (Android 13+) the service still runs; only the notification is hidden.
 */
@AndroidEntryPoint
class GuidanceForegroundService : LifecycleService() {
    @Inject lateinit var session: GuidanceSession
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var restore: RestoreManager
    @Inject lateinit var voice: GuidanceVoice
    @Inject lateinit var location: LocationSource
    @Inject lateinit var sunTheme: SunTheme
    @Inject lateinit var battery: BatteryHint

    private val policy = NotificationPostPolicy()
    private lateinit var builder: GuidanceNotificationBuilder
    private var observeJob: Job? = null
    private var foreground = false
    private var latest: GuidanceState? = null
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) voice.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        builder = GuidanceNotificationBuilder(this, GuidanceForegroundService::class.java, ACTION_END, ACTION_TOGGLE_VOICE, ACTION_DISMISSED)
        GuidanceChannel.ensure(this, settings.lang.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when {
            intent == null -> return onSystemRestart()
            intent.action == ACTION_END -> session.end()
            // AC 3: the same toggle as the on-screen button (persisted; the engine follows); the app does not open.
            intent.action == ACTION_TOGGLE_VOICE -> settings.setMuted(!settings.muted.value)
            intent.action == ACTION_DISMISSED -> policy.onDismissed()
            else -> startForegroundNow()
        }
        return if (session.engine.value != null) START_STICKY else START_NOT_STICKY
    }

    /** ADR-0013 §2 null-intent restart (AC 22): the decision runs off the main thread, within 5 s. */
    private fun onSystemRestart(): Int {
        if (session.engine.value != null) {
            startForegroundNow()
            return START_STICKY
        }
        lifecycleScope.launch {
            val locationOk = hasLocationPermission() && runCatching { location.servicesEnabled() }.getOrDefault(false)
            val decision = withContext(Dispatchers.IO) { restore.decide(sessionAlive = false, locationOk = locationOk) }
            val action = RestartPlan.action(Build.VERSION.SDK_INT, decision, GuidanceChannel.allowed(this@GuidanceForegroundService))
            when (action) {
                RestartPlan.Action.RESUME_SILENTLY -> {
                    val meta = (decision as RestoreRules.Decision.Restore).meta
                    val resumed = runCatching { startForegroundNow() }.isSuccess &&
                        withContext(Dispatchers.Default) { restore.load(meta) }?.let { session.restore(it, showNotice = false) } == true
                    if (resumed) {
                        battery.onRestored() // AC 26: the hint shows once on the next route preview
                    } else {
                        if (restore.exists()) postInterrupted()
                        stopSelfNow()
                    }
                }
                RestartPlan.Action.POST_INTERRUPTED -> {
                    postInterrupted()
                    stopSelfNow()
                }
                RestartPlan.Action.DELETE_AND_STOP -> {
                    withContext(Dispatchers.IO) { restore.delete() }
                    stopSelfNow()
                }
                RestartPlan.Action.KEEP_AND_STOP, RestartPlan.Action.STOP -> stopSelfNow()
            }
        }
        return START_NOT_STICKY
    }

    private fun postInterrupted() {
        val meta = restore.peek() ?: return
        InterruptedNotification.post(this, meta, settings.lang.value, System.currentTimeMillis())
    }

    private fun stopSelfNow() {
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundNow() {
        if (foreground) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder.placeholder(settings.lang.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        foreground = true
        InterruptedNotification.cancel(this)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }
        observe()
    }

    /** AC 13: guidance continues after the app is removed from Recents; with no session the service stops (AC 15). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session.engine.value == null) stopSelfNow()
        super.onTaskRemoved(rootIntent)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observe() {
        if (observeJob != null) return
        observeJob = lifecycleScope.launch {
            launch {
                session.engine.flatMapLatest { it?.state ?: flowOf(null) }.collectLatest { state ->
                    latest = state
                    update()
                }
            }
            // AC 4: a language switch updates texts, action labels and the channel name within 2 s, 0 requests.
            launch {
                settings.lang.drop(1).collect {
                    GuidanceChannel.ensure(this@GuidanceForegroundService, it)
                    policy.invalidate()
                    update()
                }
            }
            // State changes are posted ≤ 1 s after the last post, the distance every 2 s (ADR-0013 §4).
            launch {
                while (isActive) {
                    delay(TICK_MS)
                    update()
                }
            }
        }
    }

    private fun update() {
        val state = latest ?: return
        val lang = settings.lang.value
        val content = RichNotification.of(state, lang, ResourceStrings.of(this, lang))
        if (!policy.decide(content, SystemClock.elapsedRealtime())) return
        // AC 7: with POST_NOTIFICATIONS denied (Android 13+) guidance runs on; only the notification is hidden.
        if (!GuidanceChannel.allowed(this)) return
        builder.night = ThemeResolver.night(settings.theme.value, sunTheme.night.value)
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build(content, lang)) }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        if (noisyRegistered) runCatching { unregisterReceiver(noisy) }
        noisyRegistered = false
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = GuidanceChannel.ID
        const val NOTIFICATION_ID = 5
        const val ACTION_END = "mn.navmn.app.action.END_GUIDANCE"
        const val ACTION_TOGGLE_VOICE = "mn.navmn.app.action.TOGGLE_VOICE"
        const val ACTION_DISMISSED = "mn.navmn.app.action.NOTIFICATION_DISMISSED"
        const val TICK_MS = 250L
        const val MIN_INTERVAL_MS = NotificationPostPolicy.MIN_INTERVAL_MS
    }
}
