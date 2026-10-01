package mn.navmn.app.engine

import mn.navmn.app.arrival.ArrivalDetector
import mn.navmn.app.gps.GpsMonitor
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.Strings
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.location.Fix
import mn.navmn.app.log.DebugLog
import mn.navmn.app.reroute.OffRouteDetector
import mn.navmn.app.reroute.ReroutePolicy
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.voiceplan.PlaybackQueue
import mn.navmn.app.voiceplan.PromptClass
import mn.navmn.app.voiceplan.ScheduledPrompt
import mn.navmn.app.voiceplan.Speaker
import mn.navmn.app.voiceplan.SpeedTracker
import mn.navmn.app.voiceplan.SpokenPrompt
import mn.navmn.app.voiceplan.VoiceScheduler

/**
 * The guidance logic of ADR-0009, single-threaded and pure: every call must come from the one engine thread (or a
 * test with a virtual clock). Drives the navigator (Ferrostar `NavigationSession`) with each fix and owns the
 * off-route episode (§4), the reroute policy (P1–P11), GPS loss (§5), arrival (§5), the voice schedule (§3.3) and the
 * playback queue (§3.4 / navigation-ux §4.5). The Android wrapper ([GuidanceEngine]) only feeds it.
 *
 * Contract for [requester]: its callback must be delivered on the engine thread (the wrapper posts it).
 */
class GuidanceCore(
    private val clock: Clock,
    initialRoute: ParsedRoute,
    private val trip: Trip,
    private val navigators: NavigatorFactory,
    private val requester: RouteRequester,
    speaker: Speaker,
    private val stringsFor: (Lang) -> Strings,
    lang: Lang,
    muted: Boolean,
    online: Boolean,
    private val onState: (GuidanceState) -> Unit,
    private val onEvent: (GuidanceEvent) -> Unit,
    private val log: DebugLog = DebugLog.NONE,
) {
    private var route: ParsedRoute = initialRoute
    private val plan get() = route.plan
    private var navigator: Navigator = navigators.create(initialRoute)
    private var snapshot: NavSnapshot? = null
    private var lastFix: Fix? = null
    private var lastGoodFix: Fix? = null
    /**
     * Ferrostar 0.57.0 computes the deviation of an update from the PREVIOUS trip state (the previous fix), so the
     * deviation reported after fix n describes fix n − 1. The off-route debounce pairs it with that fix's accuracy and
     * time (ADR-0009 §4).
     */
    private var previousFixGood = false
    private var previousFixElapsed = 0L

    private val gps = GpsMonitor()
    private val offRoute = OffRouteDetector()
    val policy = ReroutePolicy()
    private val arrival = ArrivalDetector()
    private val scheduler = VoiceScheduler(walk = trip.mode == TravelMode.WALK)
    private val speed = SpeedTracker()
    val queue = PlaybackQueue(speaker)

    private var phase = GuidancePhase.NAVIGATING
    private var lang = lang
    private var muted = muted
    private var online = online
    private var progress = Progress(initialRoute.plan.distance, initialRoute.plan.duration, clock.wallMs())
    private var gpsRestoredUntil = Long.MIN_VALUE
    private var voiceNoticeUntil = Long.MIN_VALUE
    private var voiceNoticeShown = false
    private var promptIds = 0L
    private var requestToken = 0
    private var inFlight: Cancelable? = null
    private var started = false

    val finished: Boolean get() = phase == GuidancePhase.ARRIVED || phase == GuidancePhase.ENDED
    val currentPhase: GuidancePhase get() = phase
    val currentRoute: ParsedRoute get() = route

    // ------------------------------------------------------------------------------------------- inputs

    /** «Эхлэх» with a fresh good fix (AC 15): 0 requests; the depart prompt within 2 s (AC 35). */
    fun start(fix: Fix) {
        check(!started) { "already started" }
        started = true
        val now = clock.elapsedMs()
        gps.start(now)
        lastFix = fix
        if (fix.isGood(now)) {
            lastGoodFix = fix
            speed.add(fix.elapsedMs, fix.speedMps)
        }
        previousFixGood = fix.isGood(now)
        previousFixElapsed = fix.elapsedMs
        val snap = navigator.initial(fix)
        snapshot = snap
        updateProgress(snap)
        val depart = scheduler.start(plan, now)
        speak(depart, PromptClass.MANEUVER, now)
        log.d("guidance started gen=${plan.generation} steps=${plan.steps.size}")
        emit()
    }

    fun onFix(fix: Fix) {
        if (!started || finished) return
        val now = clock.elapsedMs()
        lastFix = fix
        val good = fix.isGood(now)
        val snap = navigator.update(fix)
        if (good) {
            lastGoodFix = fix
            speed.add(fix.elapsedMs, fix.speedMps)
            if (gps.onGoodFix(fix.elapsedMs) == GpsMonitor.Event.RESTORED) onGpsRestored(now)
        }
        if (!gps.lost) {
            snapshot = snap
            updateProgress(snap)
            val event = offRoute.onFix(previousFixGood, snap.deviation == Deviation.COMPLETELY_OFF_ROUTE, previousFixElapsed)
            when (event) {
                OffRouteDetector.Event.STARTED -> startEpisode(now)
                OffRouteDetector.Event.ENDED -> endEpisodeOnOldRoute(now)
                null -> Unit
            }
        }
        previousFixGood = good
        previousFixElapsed = fix.elapsedMs
        if (arrival.check(snap.complete, offRoute.inEpisode, good, snap.distanceRemaining, fix.latLon, plan.end)) {
            arrive(now)
            emit()
            return
        }
        evaluateVoice(now)
        maybeReroute(now)
        emit()
    }

    /** 0.5–1 s ticker: GPS-loss check (no fix may arrive at all), queue timeouts, reroute timing, expiring messages. */
    fun onTick() {
        if (!started || phase == GuidancePhase.ENDED) return
        val now = clock.elapsedMs()
        queue.tick(now)
        if (phase == GuidancePhase.ARRIVED) {
            emit()
            return
        }
        if (gps.onTick(now) == GpsMonitor.Event.LOST) onGpsLost(now)
        evaluateVoice(now)
        maybeReroute(now)
        emit()
    }

    fun onNetwork(validated: Boolean) {
        val was = online
        online = validated
        if (!finished && !was && validated && offRoute.inEpisode) {
            policy.onNetworkRestored()
            maybeReroute(clock.elapsedMs())
        }
        emit()
    }

    /** Delivered on the engine thread by the wrapper. Stale tokens (cancelled requests) are ignored. */
    fun onRouteResult(token: Int, outcome: RouteOutcome) {
        if (token != requestToken || !policy.inFlight) return
        inFlight = null
        val now = clock.elapsedMs()
        policy.onOutcome(now, outcome)
        log.d("reroute outcome ${outcome::class.simpleName}")
        if (outcome is RouteOutcome.Ok && !finished && offRoute.inEpisode) {
            applyRoute(outcome.route, now)
        }
        // P11: a 200 after the episode ended (back on the old route) is discarded.
        emit()
    }

    fun onSpeakerDone(promptId: Long) {
        queue.onDone(promptId, clock.elapsedMs())
    }

    /** The voice output used the chime instead of a voice (D23): show A1 once per session (navigation-ux §4.6). */
    fun onVoiceFallback() {
        if (voiceNoticeShown || lang != Lang.MN || finished) return
        voiceNoticeShown = true
        voiceNoticeUntil = clock.elapsedMs() + VOICE_NOTICE_MS
        emit()
    }

    fun dismissVoiceNotice() {
        voiceNoticeUntil = Long.MIN_VALUE
        emit()
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) queue.clear() // AC 37: the current utterance or chime stops
        emit()
    }

    /** AC 60: the current utterance stops; the next prompt uses the new language (§4.5 rule 6). 0 requests. */
    fun setLanguage(value: Lang) {
        if (value == lang) return
        lang = value
        queue.clear()
        emit()
    }

    /** «Дуусгах», swipe-away, «Хаах» after arrival (AC 19–20): everything stops, 0 requests. */
    fun end() {
        if (phase == GuidancePhase.ENDED) return
        phase = GuidancePhase.ENDED
        cancelInFlight()
        queue.close()
        runCatching { navigator.close() }
        emit()
        onEvent(GuidanceEvent.Ended)
    }

    // ------------------------------------------------------------------------------------------- internals

    private fun updateProgress(snap: NavSnapshot) {
        progress = Progress(snap.distanceRemaining, snap.durationRemaining, clock.wallMs())
    }

    private fun onGpsLost(now: Long) {
        offRoute.resetDebounce()
        queue.clear()
        speak(ScheduledPrompt(VoiceContent.GpsLost, null, null, now), PromptClass.CLIENT, now)
        log.d("gps lost")
    }

    private fun onGpsRestored(now: Long) {
        gpsRestoredUntil = now + GPS_RESTORED_MS
        speak(ScheduledPrompt(VoiceContent.GpsRestored, null, null, now), PromptClass.CLIENT, now)
        scheduler.onGpsRestored()
        log.d("gps restored")
    }

    private fun startEpisode(now: Long) {
        phase = GuidancePhase.OFF_ROUTE
        queue.clear() // §4.5 rule 2: the old route's utterance stops, the queue is cleared
        speak(ScheduledPrompt(VoiceContent.OffRoute, null, null, now), PromptClass.CLIENT, now)
        log.d("off-route episode started")
    }

    /** AC 46: back within 50 m of the old route before a new one arrived: 0 extra requests, catch-up prompt. */
    private fun endEpisodeOnOldRoute(now: Long) {
        phase = GuidancePhase.NAVIGATING
        cancelInFlight()
        policy.onEpisodeEnd()
        scheduler.onRouteActive()
        log.d("off-route episode ended on the old route")
    }

    private fun applyRoute(newRoute: ParsedRoute, now: Long) {
        runCatching { navigator.close() }
        route = newRoute
        navigator = navigators.create(newRoute)
        val fix = lastGoodFix ?: lastFix
        if (fix != null) {
            val snap = navigator.initial(fix)
            snapshot = snap
            updateProgress(snap)
        }
        previousFixGood = false
        offRoute.reset()
        policy.onEpisodeEnd()
        phase = GuidancePhase.NAVIGATING
        scheduler.onRouteActive()
        evaluateVoice(now)
        log.d("new route active gen=${newRoute.plan.generation}")
    }

    private fun arrive(now: Long) {
        phase = GuidancePhase.ARRIVED
        cancelInFlight()
        val last = plan.steps.last()
        speak(ScheduledPrompt(VoiceContent.Arrival(last.key), plan.generation to plan.steps.lastIndex, null, now), PromptClass.ARRIVAL, now)
        log.d("arrived")
        onEvent(GuidanceEvent.Arrived)
    }

    private fun cancelInFlight() {
        val c = inFlight
        inFlight = null
        if (policy.inFlight) {
            requestToken++ // a late callback is ignored
            policy.onOutcome(clock.elapsedMs(), RouteOutcome.Cancelled)
        }
        c?.cancel()
    }

    private fun evaluateVoice(now: Long) {
        if (phase != GuidancePhase.NAVIGATING || gps.lost) return
        val snap = snapshot ?: return
        val p = scheduler.evaluate(
            VoiceScheduler.Input(now, plan, snap.stepIndex, snap.distanceToNextManeuver, speed.mean()),
        ) ?: return
        speak(p, PromptClass.MANEUVER, now)
    }

    private fun speak(p: ScheduledPrompt, cls: PromptClass, now: Long) {
        if (muted) return // muted prompts count as handled (ADR-0009 §3.3)
        val text = VoiceText.render(p.content, lang, stringsFor(lang))
        queue.enqueue(SpokenPrompt(++promptIds, text, lang, cls, p.maneuver, now), now)
    }

    private fun maybeReroute(now: Long) {
        val ctx = ReroutePolicy.Context(
            now = now,
            inEpisode = offRoute.inEpisode,
            online = online,
            gpsOk = gps.ok(now),
            finished = finished,
            position = lastGoodFix?.latLon,
        )
        if (!policy.mayStart(ctx)) return
        val fix = lastGoodFix ?: return
        val request = RouteRequest(
            origin = fix.latLon,
            destination = trip.destination,
            mode = trip.mode,
            avoidUnpaved = trip.avoidUnpaved,
            lang = lang,
            heading = RouteBody.headingFor(fix),
        )
        policy.onStarted(now, fix.latLon)
        val token = ++requestToken
        log.d("reroute request")
        val c = requester.start(request, plan.generation + 1) { outcome -> onRouteResult(token, outcome) }
        if (token == requestToken && policy.inFlight) inFlight = c
    }

    private fun emit() {
        val now = clock.elapsedMs()
        val snap = snapshot
        val banner: Banner = when (phase) {
            GuidancePhase.ARRIVED, GuidancePhase.ENDED -> plan.steps.last().let { Banner.Arrival(it.key, it.street) }
            GuidancePhase.OFF_ROUTE -> Banner.Rerouting(policy.secondary)
            GuidancePhase.NAVIGATING -> {
                val k = snap?.stepIndex ?: 0
                val m = k + 1
                if (m < plan.steps.size) {
                    val step = plan.steps[m]
                    Banner.Maneuver(
                        key = step.key,
                        distanceM = snap?.distanceToNextManeuver ?: plan.steps[0].distance,
                        street = step.street,
                        then = scheduler.thenVisible(plan, k),
                        stale = gps.lost,
                    )
                } else {
                    plan.steps.last().let { Banner.Arrival(it.key, it.street) }
                }
            }
        }
        val fix = lastFix
        val puck = when {
            fix == null -> null
            gps.lost -> Puck((lastGoodFix ?: fix).latLon, (lastGoodFix ?: fix).bearingDeg, stale = true, snapped = false)
            phase == GuidancePhase.OFF_ROUTE || snap == null -> Puck(fix.latLon, fix.bearingDeg, stale = false, snapped = false)
            else -> Puck(snap.snapped, snap.snappedCourseDeg ?: fix.bearingDeg, stale = false, snapped = true)
        }
        onState(
            GuidanceState(
                phase = phase,
                generation = plan.generation,
                banner = banner,
                progress = progress,
                puck = puck,
                route = plan.geometry,
                trip = trip,
                gpsLost = gps.lost && !finished,
                gpsRestoredVisible = now < gpsRestoredUntil && !finished,
                offline = !online,
                voiceNoticeVisible = now < voiceNoticeUntil && !finished,
                muted = muted,
                speedMps = speed.mean(),
            ),
        )
    }

    companion object {
        const val GPS_RESTORED_MS = 3_000L
        const val VOICE_NOTICE_MS = 8_000L
    }
}
