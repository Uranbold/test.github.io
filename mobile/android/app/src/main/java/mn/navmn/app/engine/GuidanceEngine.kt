package mn.navmn.app.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationSource
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.voice.GuidanceVoice
import mn.navmn.app.voiceplan.Speaker
import mn.navmn.app.voiceplan.SpokenPrompt
import java.util.concurrent.Executors

/** Real time for the engine: elapsedRealtime for rules, the wall clock only for «Хүрэх цаг». */
object SystemClocks : Clock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}

/**
 * ADR-0009 §1: application-scoped, single-threaded engine. Every input (fixes, the 500 ms ticker, network, route
 * results, speaker completions, settings) is posted to ONE dedicated thread that owns the [GuidanceCore]. Created at
 * «Эхлэх», destroyed when guidance ends; the Activity only renders [state] (rotation, theme and language changes
 * recreate the Activity, never the engine: 0 requests, 0 repeated prompts, AC 59, 60, 64).
 */
class GuidanceEngine(
    context: Context,
    route: ParsedRoute,
    trip: Trip,
    firstFix: Fix,
    navigators: NavigatorFactory,
    requester: RouteRequester,
    private val location: LocationSource,
    private val network: NetworkMonitor,
    private val voice: GuidanceVoice,
    private val settings: SettingsRepository,
    log: DebugLog,
    private val onFinished: (GuidanceEvent) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "navmn-guidance").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var locationJob: Job? = null
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<GuidanceState?>(null)
    val state: StateFlow<GuidanceState?> = _state.asStateFlow()

    private lateinit var core: GuidanceCore

    /** Results arrive on OkHttp threads; post them to the engine thread (GuidanceCore contract). */
    private val postingRequester = object : RouteRequester {
        override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable =
            requester.start(request, generation) { outcome -> scope.launch { onResult(outcome) } }
    }

    private val speaker = object : Speaker {
        override fun play(prompt: SpokenPrompt) = voice.play(prompt)
        override fun stop() = voice.stop()
    }

    init {
        voice.newSession()
        voice.onDone = { id -> scope.launch { core.onSpeakerDone(id) } }
        voice.onFallback = { scope.launch { core.onVoiceFallback() } }
        scope.launch {
            core = GuidanceCore(
                clock = SystemClocks,
                initialRoute = route,
                trip = trip,
                navigators = navigators,
                requester = postingRequester,
                speaker = speaker,
                stringsFor = { lang: Lang -> ResourceStrings.of(appContext, lang) },
                lang = settings.lang.value,
                muted = settings.muted.value,
                online = network.isOnline(),
                onState = { _state.value = it },
                onEvent = { e -> handle(e) },
                log = log,
            )
            core.start(firstFix)
            locationJob = launch { location.guidanceUpdates().collect { core.onFix(it) } }
            launch {
                while (isActive) {
                    delay(TICK_MS)
                    core.onTick()
                }
            }
            launch { network.validated.drop(1).collect { core.onNetwork(it) } }
            launch { settings.lang.drop(1).collect { voice.onLanguageChanged(); core.setLanguage(it) } }
            launch { settings.muted.drop(1).collect { core.setMuted(it) } }
        }
    }

    private fun handle(e: GuidanceEvent) {
        when (e) {
            // AC 55: location updates and the foreground service stop now; the arrival panel stays until «Хаах».
            GuidanceEvent.Arrived -> {
                locationJob?.cancel()
                onFinished(e)
            }
            GuidanceEvent.Ended -> {
                locationJob?.cancel()
                onFinished(e)
                scope.launch { shutdown() }
            }
        }
    }

    private fun shutdown() {
        voice.onDone = null
        voice.onFallback = null
        scope.cancel()
        executor.shutdown()
    }

    fun end() = post { core.end() }
    fun dismissVoiceNotice() = post { core.dismissVoiceNotice() }
    fun setMuted(value: Boolean) = post { core.setMuted(value) }

    private fun post(block: () -> Unit) {
        if (scope.isActive) scope.launch { if (::core.isInitialized) block() }
    }

    companion object {
        const val TICK_MS = 500L
    }
}
