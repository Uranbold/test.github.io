package mn.navmn.app.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.navmn.app.BuildConfig
import mn.navmn.app.audio.calls.AudioModeCallSignals
import mn.navmn.app.background.restore.LoadedRecord
import mn.navmn.app.background.restore.RestoreManager
import mn.navmn.app.background.restore.RestoreRules
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationSource
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.service.GuidanceForegroundService
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.voice.GuidanceVoice
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the one guidance engine of the process (or none) and starts/stops the foreground service with it. */
@Singleton
class GuidanceSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navigators: NavigatorFactory,
    private val requester: RouteRequester,
    private val location: LocationSource,
    private val network: NetworkMonitor,
    private val voice: GuidanceVoice,
    private val settings: SettingsRepository,
    /** NAV-012 (ADR-0013 §3): the restore record is written at «Эхлэх», on new routes and deleted on a normal end. */
    private val restoreRecords: RestoreManager,
    /** NAV-012 AC 35: audio mode and transient focus loss, collected by the engine while guidance runs. */
    private val calls: AudioModeCallSignals,
) {
    private val _engine = MutableStateFlow<GuidanceEngine?>(null)
    val engine: StateFlow<GuidanceEngine?> = _engine.asStateFlow()

    private val log: DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.guidance", it) } else DebugLog.NONE

    /** «Эхлэх» (AC 15): the previewed route is navigated with 0 additional requests. */
    fun start(route: ParsedRoute, trip: Trip, firstFix: Fix) {
        _engine.value?.end()
        val e = newEngine(route, trip, firstFix, showResumedNotice = false)
        _engine.value = e
        restoreRecords.onGuidanceStarted(trip, route, settings.lang.value) // AC 16: written within 2 s
        ContextCompat.startForegroundService(context, Intent(context, GuidanceForegroundService::class.java))
    }

    /**
     * NAV-012 restore (AC 18–22, ADR-0013 §3.4): guidance to the stored destination on the stored route, without
     * «Эхлэх», no depart prompt; the first good fix places it on the route (0 requests) or starts an off-route episode.
     * [showNotice] = opened by the user («Замчлал сэргэлээ»); false on the silent system restart. False when a session
     * is already alive (nothing restored).
     */
    fun restore(loaded: LoadedRecord, showNotice: Boolean): Boolean {
        if (_engine.value != null) return false
        val e = newEngine(loaded.route, loaded.trip, null, showNotice)
        _engine.value = e
        val now = System.currentTimeMillis()
        restoreRecords.onGuidanceStarted(loaded.trip, loaded.route, settings.lang.value, restored = RestoreRules.afterRestore(loaded.meta, now))
        ContextCompat.startForegroundService(context, Intent(context, GuidanceForegroundService::class.java))
        return true
    }

    private fun newEngine(route: ParsedRoute, trip: Trip, firstFix: Fix?, showResumedNotice: Boolean): GuidanceEngine =
        GuidanceEngine(
            context, route, trip, firstFix, navigators, requester, location, network, voice, settings, log,
            onFinished = { event ->
                when (event) {
                    GuidanceEvent.Arrived -> {
                        restoreRecords.onNormalEnd() // AC 17: deleted before the service stops
                        stopService()
                    }
                    GuidanceEvent.Ended -> {
                        restoreRecords.onNormalEnd()
                        stopService()
                        _engine.value = null
                    }
                }
            },
            callSignals = calls.inCall(),
            showResumedNotice = showResumedNotice,
            onNewRoute = { r -> restoreRecords.onNewRoute(r, settings.lang.value) },
        )

    /** «Дуусгах» on screen or in the notification, «Хаах» on the arrival panel (AC 19, 55; swipe-away no longer ends, NAV-012 AC 13). */
    fun end() {
        val e = _engine.value
        if (e == null) {
            stopService()
            return
        }
        e.end()
    }

    private fun stopService() {
        context.stopService(Intent(context, GuidanceForegroundService::class.java))
    }
}
