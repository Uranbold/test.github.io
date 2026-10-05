package mn.navmn.app.demo.replay

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.FerrostarNavigatorFactory
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.engine.GuidanceCore
import mn.navmn.app.engine.GuidanceEvent
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Trip
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.qa.repoFile
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.support.TestStrings
import mn.navmn.app.voice.GuidanceVoice
import mn.navmn.app.voiceplan.SpokenPrompt

/** The repo's demo manifest read the way the APK reads its assets (`demo/files/<repo path>` → the repo file). */
object RepoAssets {
    fun read(path: String): ByteArray = when {
        path == DemoCatalogue.MANIFEST_ASSET -> repoFile("web/src/demo/routes.manifest.json").readBytes()
        path.startsWith("demo/files/") -> repoFile(path.removePrefix("demo/files/")).readBytes()
        else -> throw java.io.FileNotFoundException(path)
    }
}

/** A voice that records what it plays and completes each prompt after the QA harness's simulated speech time. */
class FakeVoice(private val scope: TestScope, private val speechMs: (SpokenPrompt) -> Long) : GuidanceVoice {
    val played = ArrayList<Pair<Long, SpokenPrompt>>()
    var stops = 0
    private val pending = ArrayList<Job>()
    override var onDone: ((Long) -> Unit)? = null
    override var onFallback: (() -> Unit)? = null

    override fun play(prompt: SpokenPrompt) {
        played += scope.testScheduler.currentTime to prompt
        pending += scope.backgroundScope.launch {
            delay(speechMs(prompt))
            onDone?.invoke(prompt.id)
        }
    }

    override fun stop() {
        stops++
        pending.forEach { it.cancel() }
        pending.clear()
    }

    override fun prepare() = Unit
    override fun newSession() = Unit
    override fun onLanguageChanged() = Unit
}

/** Records wake-lock calls (ADR-0016 §4.5). */
class RecordingWakeLock : ReplayWakeLock {
    var held = false
    var acquires = 0
    override fun acquire(timeoutMs: Long) {
        held = true
        acquires++
    }

    override fun release() {
        held = false
    }
}

/**
 * NAV-019 demo replay path on the JVM (ADR-0016 §13): [ReplayLocationSource] on a [ReplayClock] driven by virtual time,
 * the real Ferrostar core inside the unchanged [GuidanceCore], a 500 ms ticker and [PauseGatedVoice] around a fake TTS,
 * wired like `GuidanceEngine` does (speaker completions and route results posted asynchronously, location collection
 * cancelled at arrival). Fake TTS in the test only (AC 14).
 */
class DemoHarness(
    private val scope: TestScope,
    routeBytes: ByteArray,
    mode: TravelMode,
    val lang: Lang,
    private val requester: RouteRequester = NoRequests,
    online: Boolean = true,
    speechMs: (SpokenPrompt) -> Long = { minOf(5_000L, 300L + it.text.length * 55L) },
) {
    val clock = ReplayClock(realNow = { scope.testScheduler.currentTime }, wallNow = { WALL_BASE + scope.testScheduler.currentTime })
    val wakeLock = RecordingWakeLock()
    val source = ReplayLocationSource(clock, servicesEnabled = { true }, wakeLock = wakeLock, wallNow = { WALL_BASE + scope.testScheduler.currentTime })
    val voice = FakeVoice(scope, speechMs)
    val gate = PauseGatedVoice(voice) { clock.paused.value }
    val route: ParsedRoute = (PreviewRoutes.process(RouteProcessor(FerrostarRouteParser()), routeBytes, 0) as RouteOutcome.Ok).route
    val trip = Trip(route.plan.end, null, mode, false)

    val states = ArrayList<Pair<Long, GuidanceState>>()
    val events = ArrayList<Pair<Long, GuidanceEvent>>()
    val fixes = ArrayList<Pair<Long, Fix>>()
    var trackEnded = 0
    var startedAt = 0L
        private set
    private var locationJob: Job? = null
    private var tickJob: Job? = null

    private val postingRequester = object : RouteRequester {
        override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable =
            requester.start(request, generation) { outcome -> scope.backgroundScope.launch { onResult(outcome) } }
    }

    val core: GuidanceCore = GuidanceCore(
        clock = clock,
        initialRoute = route,
        trip = trip,
        navigators = FerrostarNavigatorFactory(),
        requester = postingRequester,
        speaker = gate,
        stringsFor = { TestStrings.of(it) },
        lang = lang,
        muted = false,
        online = online,
        onState = { states += now() to it },
        onEvent = { e ->
            events += now() to e
            // GuidanceEngine: arrival and end stop the location updates.
            locationJob?.cancel()
            if (e == GuidanceEvent.Ended) tickJob?.cancel()
        },
    )

    init {
        voice.onDone = { id -> scope.backgroundScope.launch { core.onSpeakerDone(id) } }
    }

    fun now(): Long = scope.testScheduler.currentTime - startedAt

    /** «Эхлэх» on [track]: fix 0 from the source starts the core, then the source's flow drives it. */
    suspend fun start(track: ReplayTrack, speed: Int = 1) {
        source.arm(track)
        source.speed = speed
        source.onTrackEnd = {
            trackEnded++
            core.end()
        }
        startedAt = scope.testScheduler.currentTime
        val fix0 = source.freshGoodFix()!!
        fixes += now() to fix0
        core.start(fix0)
        locationJob = scope.backgroundScope.launch {
            source.guidanceUpdates().collect {
                fixes += now() to it
                core.onFix(it)
            }
        }
        tickJob = scope.backgroundScope.launch {
            while (isActive) {
                delay(500)
                core.onTick()
            }
        }
    }

    fun pause() {
        if (clock.pause()) gate.onPaused()
    }

    fun resume() = clock.resume()

    val spoken: List<Pair<Long, SpokenPrompt>> get() = voice.played.map { (t, p) -> (t - startedAt) to p }

    fun stateAt(t: Long): GuidanceState? = states.lastOrNull { it.first <= t }?.second

    val arrivals: Int get() = events.count { it.second == GuidanceEvent.Arrived }

    fun bannerChanges(text: (Banner) -> String): List<String> {
        val out = ArrayList<String>()
        for ((_, s) in states) {
            val b = text(s.banner)
            if (out.lastOrNull() != b) out += b
        }
        return out
    }

    companion object {
        const val WALL_BASE = 1_790_000_000_000L
    }
}

/** No route requests expected (on-route replays). */
object NoRequests : RouteRequester {
    var count = 0
    override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
        count++
        onResult(RouteOutcome.Unavailable)
        return Cancelable { }
    }
}

