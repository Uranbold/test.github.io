package mn.navmn.app.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.navmn.app.BuildConfig
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
) {
    private val _engine = MutableStateFlow<GuidanceEngine?>(null)
    val engine: StateFlow<GuidanceEngine?> = _engine.asStateFlow()

    private val log: DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.guidance", it) } else DebugLog.NONE

    /** «Эхлэх» (AC 15): the previewed route is navigated with 0 additional requests. */
    fun start(route: ParsedRoute, trip: Trip, firstFix: Fix) {
        _engine.value?.end()
        val e = GuidanceEngine(context, route, trip, firstFix, navigators, requester, location, network, voice, settings, log) { event ->
            when (event) {
                GuidanceEvent.Arrived -> stopService()
                GuidanceEvent.Ended -> {
                    stopService()
                    _engine.value = null
                }
            }
        }
        _engine.value = e
        ContextCompat.startForegroundService(context, Intent(context, GuidanceForegroundService::class.java))
    }

    /** «Дуусгах» on screen or in the notification, swipe-away, «Хаах» on the arrival panel (AC 19, 20, 55). */
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
