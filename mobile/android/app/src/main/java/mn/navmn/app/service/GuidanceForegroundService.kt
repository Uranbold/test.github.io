package mn.navmn.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import mn.navmn.app.R
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.ui.MainActivity
import javax.inject.Inject

/**
 * ADR-0009 §9: the app's own foreground service of type `location` while guiding (AC 15, 17). Started from the
 * foreground by «Эхлэх» (a while-in-use permission suffices). Channel «Замчлал», IMPORTANCE_LOW (silent; voice is
 * separate). «Дуусгах» action through an explicit, non-exported PendingIntent. Swipe-away ends guidance (AC 20).
 * With the notification permission denied (Android 13+) the service still runs; only the notification is hidden.
 */
@AndroidEntryPoint
class GuidanceForegroundService : LifecycleService() {
    @Inject lateinit var session: GuidanceSession
    @Inject lateinit var settings: SettingsRepository

    private var lastPostedAt = 0L
    private var lastContent: GuidanceNotificationText.Content? = null
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val initial = build(GuidanceNotificationText.Content(getString(R.string.nav_name), null))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initial,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        observe()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_END) session.end()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // AC 20: removing the app from Recents ends guidance (continuing is NAV-012).
        session.end()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observe() {
        observeJob = lifecycleScope.launch {
            session.engine.flatMapLatest { it?.state ?: flowOf(null) }.filterNotNull().collectLatest { state ->
                update(state)
                // AC 17: refresh at least every 5 s even without a state change (the text may be the same).
                while (true) {
                    delay(REFRESH_MS)
                    update(state, force = true)
                }
            }
        }
    }

    private fun update(state: GuidanceState, force: Boolean = false) {
        val lang = settings.lang.value
        val content = GuidanceNotificationText.of(state, lang, ResourceStrings.of(this, lang))
        val now = SystemClock.elapsedRealtime()
        if (!force && content == lastContent) return
        if (!force && now - lastPostedAt < MIN_INTERVAL_MS) return // at most once per second
        lastContent = content
        lastPostedAt = now
        // AC 13: with POST_NOTIFICATIONS denied (Android 13+) guidance runs on; only the notification is hidden.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(content)) }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val lang = settings.lang.value
        val name = ResourceStrings.of(this, lang)[mn.navmn.app.i18n.StringKey.NAV_NAME]
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    private fun build(content: GuidanceNotificationText.Content) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_navigation)
        .setContentTitle(content.title)
        .setContentText(content.text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            ResourceStrings.of(this, settings.lang.value)[mn.navmn.app.i18n.StringKey.NAV_END],
            PendingIntent.getService(
                this,
                1,
                Intent(this, GuidanceForegroundService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    override fun onDestroy() {
        observeJob?.cancel()
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "navmn_guidance"
        const val NOTIFICATION_ID = 5
        const val ACTION_END = "mn.navmn.app.action.END_GUIDANCE"
        const val REFRESH_MS = 5_000L
        const val MIN_INTERVAL_MS = 1_000L
    }
}
