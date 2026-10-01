package mn.navmn.app.voiceplan

import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.route.GuidancePlan

/**
 * navigation-ux §4.2–§4.4 as named constants (ADR-0009 §3.3). Change only through UX and the BA.
 */
object VoiceConstants {
    /** Fast = v ≥ 70 km/h. */
    const val FAST_MPS = 70.0 / 3.6
    const val EARLY_SLOW_M = 1_000.0
    const val EARLY_SLOW_MIN_GAP_M = 1_300.0
    const val EARLY_FAST_M = 2_000.0
    const val EARLY_FAST_MIN_GAP_M = 2_500.0
    const val MAIN_FAST_M = 500.0
    const val MAIN_FAST_MIN_GAP_M = 700.0
    const val MAIN_SECONDS = 12.0
    const val MAIN_MIN_M = 100.0
    const val MAIN_MAX_M = 250.0
    const val NOW_SECONDS = 3.0
    const val NOW_MIN_M = 15.0
    const val NOW_MAX_M = 80.0
    const val WALK_MAIN_M = 50.0
    const val WALK_NOW_M = 15.0
    const val CONTINUE_ON_MIN_M = 2_000.0
    const val SAME_MANEUVER_GAP_MS = 8_000L
    const val ARRIVE_MIN_M = 30.0
    const val CHAIN_CAR_M = 150.0
    const val CHAIN_WALK_M = 40.0
    const val RESTORE_MIN_AHEAD_M = 20.0
    const val SPEED_WINDOW_MS = 5_000L
    /** §4.5: a waiting prompt that cannot start within 3 s of its trigger is dropped. */
    const val MAX_WAIT_MS = 3_000L
}

enum class PromptKind { CONTINUE_ON, EARLY, MAIN, NOW, DEPART, CATCH_UP }

/** A prompt the schedule wants spoken. [maneuver] = (generation, plan step index) or null for client prompts. */
data class ScheduledPrompt(
    val content: VoiceContent,
    val maneuver: Pair<Int, Int>?,
    val kind: PromptKind?,
    val triggerAtMs: Long,
)

/** Mean speed over the last 5 s of good fixes (reported speed when present). */
class SpeedTracker(private val windowMs: Long = VoiceConstants.SPEED_WINDOW_MS) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(atMs: Long, speedMps: Double?) {
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0) return
        samples.addLast(atMs to speedMps)
        while (samples.isNotEmpty() && atMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun mean(): Double = if (samples.isEmpty()) 0.0 else samples.sumOf { it.second } / samples.size

    fun clear() = samples.clear()
}

/**
 * Device-side voice schedule (navigation-ux §4.2–§4.4, ADR-0009 §3.3). Pure; called on the engine thread with the
 * live distance to the upcoming manoeuvre. Each kind fires once when d first falls to or below its distance; only the
 * most urgent unfired kind fires and the less urgent ones are then marked as handled.
 */
class VoiceScheduler(private val walk: Boolean) {
    private val fired = HashSet<Triple<Int, Int, PromptKind>>()
    private val lastPromptAt = HashMap<Pair<Int, Int>, Long>()
    private val spoken = HashSet<Pair<Int, Int>>()
    /** Manoeuvres whose chained prompt was produced (the Then strip shows for them, §2.4). */
    private val chainedAnnounced = HashSet<Pair<Int, Int>>()
    private var lastStep: Pair<Int, Int>? = null

    private enum class CatchUp { REROUTE, RESTORE }
    private var pendingCatchUp: CatchUp? = null

    private val chainM get() = if (walk) VoiceConstants.CHAIN_WALK_M else VoiceConstants.CHAIN_CAR_M

    /** Manoeuvre m+1 is chained to m: ≤ 150 m (car) / ≤ 40 m (walk) after it, and not `arrive` (§4.4). */
    fun chainTarget(plan: GuidancePlan, m: Int): KeyResult? {
        val next = plan.steps.getOrNull(m + 1) ?: return null
        if (next.key.key.isArrive) return null
        return if (plan.steps[m].distance <= chainM) next.key else null
    }

    fun thenVisible(plan: GuidancePlan, stepIndex: Int): KeyResult? {
        val m = stepIndex + 1
        if (m >= plan.steps.size) return null
        val target = chainTarget(plan, m) ?: return null
        return if ((plan.generation to m) in chainedAnnounced) target else null
    }

    private fun markChained(gen: Int, m: Int) {
        chainedAnnounced += gen to m
        for (k in listOf(PromptKind.EARLY, PromptKind.MAIN, PromptKind.CONTINUE_ON, PromptKind.CATCH_UP)) fired += Triple(gen, m + 1, k)
    }

    /** §4.3 start: the depart text, chained with the first manoeuvre when it is within the chaining distance. */
    fun start(plan: GuidancePlan, nowMs: Long): ScheduledPrompt {
        val gen = plan.generation
        lastStep = gen to 0
        val then = if (plan.steps.size > 1) chainTarget(plan, 0) else null
        spoken += gen to 0
        lastPromptAt[gen to 0] = nowMs
        if (then != null) markChained(gen, 0)
        return ScheduledPrompt(VoiceContent.Depart(plan.steps[0].key, then), gen to 0, PromptKind.DEPART, nowMs)
    }

    /** §4.3: one catch-up prompt within 1 s after a new route is active (or the driver is back on the old one). */
    fun onRouteActive() {
        pendingCatchUp = CatchUp.REROUTE
        lastStep = null
    }

    /** §4.3: after «GPS дохио сэргэлээ», a catch-up if the upcoming manoeuvre had no prompt yet and is > 20 m ahead. */
    fun onGpsRestored() {
        pendingCatchUp = CatchUp.RESTORE
    }

    data class Input(
        val nowMs: Long,
        val plan: GuidancePlan,
        /** Current step k (Ferrostar); the upcoming manoeuvre is m = k + 1. */
        val stepIndex: Int,
        val distanceToNext: Double,
        val speedMps: Double,
    )

    private fun earlyThreshold(fast: Boolean, gap: Double, key: ManeuverKey): Double? = when {
        walk || key.isArrive || key == ManeuverKey.ROUNDABOUT_LEAVE -> null
        fast && gap >= VoiceConstants.EARLY_FAST_MIN_GAP_M -> VoiceConstants.EARLY_FAST_M
        !fast && gap >= VoiceConstants.EARLY_SLOW_MIN_GAP_M -> VoiceConstants.EARLY_SLOW_M
        else -> null
    }

    private fun mainThreshold(fast: Boolean, gap: Double, v: Double, key: ManeuverKey): Double? = when {
        key == ManeuverKey.ROUNDABOUT_LEAVE -> null
        walk -> VoiceConstants.WALK_MAIN_M
        fast && gap >= VoiceConstants.MAIN_FAST_MIN_GAP_M -> VoiceConstants.MAIN_FAST_M
        else -> (v * VoiceConstants.MAIN_SECONDS).coerceIn(VoiceConstants.MAIN_MIN_M, VoiceConstants.MAIN_MAX_M)
    }

    private fun nowThreshold(v: Double, key: ManeuverKey): Double? = when {
        key.isArrive -> null // the arrival prompt comes from the arrival detector (§7)
        walk -> VoiceConstants.WALK_NOW_M
        else -> (v * VoiceConstants.NOW_SECONDS).coerceIn(VoiceConstants.NOW_MIN_M, VoiceConstants.NOW_MAX_M)
    }

    private fun maneuverContent(plan: GuidancePlan, m: Int, d: Double, chain: Boolean): VoiceContent? {
        val key = plan.steps[m].key
        if (key.key.isArrive) return if (d >= VoiceConstants.ARRIVE_MIN_M) VoiceContent.Approaching(d) else null
        val then = if (chain) chainTarget(plan, m) else null
        return VoiceContent.Maneuver(key, d, then)
    }

    private fun emit(gen: Int, m: Int, kind: PromptKind, content: VoiceContent, now: Long): ScheduledPrompt {
        lastPromptAt[gen to m] = now
        spoken += gen to m
        if (content is VoiceContent.Maneuver && content.then != null) markChained(gen, m)
        return ScheduledPrompt(content, gen to m, kind, now)
    }

    /**
     * One evaluation (every snapshot and ticker step); at most one prompt. Precondition (NAV-005-D9): [Input] comes from
     * a trusted position, i.e. the latest fix is on the current step or was caught up. [mn.navmn.app.engine.GuidanceCore]
     * does not call this for a good fix > 50 m from the current step, because Ferrostar snaps such an outlier to the end
     * of the step and d ≈ 0 would fire the "now" prompt early and mark the main prompt as handled.
     */
    fun evaluate(input: Input): ScheduledPrompt? {
        val plan = input.plan
        val gen = plan.generation
        val k = input.stepIndex
        val m = k + 1
        if (m >= plan.steps.size) return null
        val id = gen to m
        val now = input.nowMs
        val d = input.distanceToNext
        val v = input.speedMps
        val fast = v >= VoiceConstants.FAST_MPS
        val gap = plan.steps[k].distance
        val key = plan.steps[m].key.key
        val stepChanged = lastStep != (gen to k)
        val firstObservation = lastStep == null
        lastStep = gen to k
        val early = earlyThreshold(fast, gap, key)
        val main = mainThreshold(fast, gap, v, key)
        val nowThr = nowThreshold(v, key)

        pendingCatchUp?.let { catchUp ->
            pendingCatchUp = null
            if (catchUp == CatchUp.RESTORE && (id in spoken || d <= VoiceConstants.RESTORE_MIN_AHEAD_M)) return@let
            if (!walk && d >= VoiceConstants.CONTINUE_ON_MIN_M && !key.isArrive) {
                fired += Triple(gen, m, PromptKind.CONTINUE_ON)
                return emit(gen, m, PromptKind.CATCH_UP, VoiceContent.ContinueOn(d), now)
            }
            if (early == null || d <= early) {
                fired += Triple(gen, m, PromptKind.EARLY)
                fired += Triple(gen, m, PromptKind.MAIN)
                if (nowThr != null && d <= nowThr) fired += Triple(gen, m, PromptKind.NOW)
                val content = maneuverContent(plan, m, d, chain = true) ?: return null
                return emit(gen, m, PromptKind.CATCH_UP, content, now)
            }
        }

        // Continue on (A12): right after passing a manoeuvre, if the next one is ≥ 2 km away (car only).
        if (stepChanged && !firstObservation && !walk && d >= VoiceConstants.CONTINUE_ON_MIN_M &&
            Triple(gen, m, PromptKind.CONTINUE_ON) !in fired
        ) {
            fired += Triple(gen, m, PromptKind.CONTINUE_ON)
            return emit(gen, m, PromptKind.CONTINUE_ON, VoiceContent.ContinueOn(d), now)
        }

        val order = listOf(PromptKind.NOW to nowThr, PromptKind.MAIN to main, PromptKind.EARLY to early)
        for ((i, pair) in order.withIndex()) {
            val (kind, thr) = pair
            if (thr == null || d > thr || Triple(gen, m, kind) in fired) continue
            for (j in i until order.size) fired += Triple(gen, m, order[j].first)
            val last = lastPromptAt[id]
            if (last != null && now - last < VoiceConstants.SAME_MANEUVER_GAP_MS) return null // rule 2
            val content = maneuverContent(plan, m, d, chain = kind != PromptKind.EARLY) ?: return null // rule 3
            return emit(gen, m, kind, content, now)
        }
        return null
    }
}
