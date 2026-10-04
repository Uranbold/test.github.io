package mn.navmn.app.support

import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.Clock
import mn.navmn.app.engine.FerrostarNavigatorFactory
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.engine.GuidanceCore
import mn.navmn.app.engine.GuidanceEvent
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.NavigatorFactory
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.instructions.BannerText
import mn.navmn.app.location.Fix
import mn.navmn.app.log.DebugLog
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteClassifier
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteParser
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.voiceplan.Speaker
import mn.navmn.app.voiceplan.SpokenPrompt
import java.io.File

class VirtualClock(var now: Long = 0, private val wallBase: Long = 1_790_000_000_000L) : Clock {
    override fun elapsedMs() = now
    override fun wallMs() = wallBase + now
}

/** GPX 1.1 track points → fixes at 1 Hz on the virtual timeline (QA's tests/gpx/nav005 files can be fed in). */
object Gpx {
    private val TRKPT = Regex("<trkpt[^>]*lat=\"([-0-9.]+)\"[^>]*lon=\"([-0-9.]+)\"[^>]*>([\\s\\S]*?)</trkpt>")

    fun parse(file: File, accuracyM: Double = 5.0): List<Fix> {
        val pts = TRKPT.findAll(file.readText()).map { LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }.toList()
        return Tracks.fromPoints(pts, accuracyM)
    }
}

/** Synthetic GPX-style tracks along route geometry (story G1–G9 shapes). */
object Tracks {
    fun fix(p: LatLon, t: Long, bearing: Double?, speed: Double?, accuracy: Double = 5.0) =
        Fix(p.lat, p.lon, accuracy, bearing, if (bearing != null) 10.0 else null, speed, t, 1_790_000_000_000L + t)

    /** 1 Hz fixes along [line] from [fromM] to [toM] at [speed], starting at [t0]. */
    fun along(line: List<LatLon>, speed: Double, t0: Long = 0, fromM: Double = 0.0, toM: Double = Geo.length(line)): List<Fix> {
        val out = ArrayList<Fix>()
        var d = fromM
        var t = t0
        while (d <= toM) {
            val p = Geo.along(line, d)
            val ahead = Geo.along(line, minOf(d + 5.0, Geo.length(line)))
            val bearing = if (Geo.distance(p, ahead) > 0.5) Geo.bearing(p, ahead) else out.lastOrNull()?.bearingDeg
            out += fix(p, t, bearing, speed)
            d += speed
            t += 1_000
        }
        return out
    }

    fun fromPoints(points: List<LatLon>, accuracyM: Double, t0: Long = 0): List<Fix> = points.mapIndexed { i, p ->
        val next = points.getOrNull(i + 1)
        val prev = points.getOrNull(i - 1)
        val bearing = when {
            next != null && Geo.distance(p, next) > 0.5 -> Geo.bearing(p, next)
            prev != null && Geo.distance(prev, p) > 0.5 -> Geo.bearing(prev, p)
            else -> null
        }
        val speed = if (next != null) Geo.distance(p, next) else 0.0
        fix(p, t0 + i * 1_000L, bearing, speed, accuracyM)
    }

    /** Straight line from [from] in [bearing] for [meters] at [speed]. */
    fun straight(from: LatLon, bearing: Double, meters: Double, speed: Double, t0: Long): List<Fix> {
        val out = ArrayList<Fix>()
        var d = speed
        var t = t0
        while (d <= meters) {
            out += fix(Geo.offset(from, bearing, d), t, bearing, speed)
            d += speed
            t += 1_000
        }
        return out
    }

    fun retime(fixes: List<Fix>, t0: Long): List<Fix> = fixes.mapIndexed { i, f -> f.copy(elapsedMs = t0 + i * 1_000L, wallTimeMs = 1_790_000_000_000L + t0 + i * 1_000L) }
}

/**
 * Drives [GuidanceCore] exactly like the engine thread does, on a virtual clock: fixes at their times, the 500 ms
 * ticker, speaker completions and route responses after a latency. Captures banners, TTS input, requests and the
 * debug log (ADR-0009 §10, NAV-005 AC 72).
 */
class Replay(
    routeFixture: ByteArray,
    val mode: TravelMode,
    val lang: Lang = Lang.MN,
    /** Responses for successive reroute requests: (status, body, Retry-After). */
    private val responses: MutableList<Triple<Int, ByteArray, String?>> = ArrayList(),
    private val latencyMs: Long = 300,
    private val parser: RouteParser = FerrostarRouteParser(),
    navigators: NavigatorFactory = FerrostarNavigatorFactory(),
    var online: Boolean = true,
    muted: Boolean = false,
    destinationName: String? = null,
    destination: LatLon? = null,
    /** A real requester (live gateway): its synchronous result is delivered right away. */
    private val liveRequester: RouteRequester? = null,
) {
    val clock = VirtualClock()
    val processor = RouteProcessor(parser)
    val initial: ParsedRoute = (processor.process(routeFixture, 0) as? RouteOutcome.Ok)?.route ?: error("fixture does not parse")
    val trip = Trip(destination ?: initial.plan.end, destinationName, mode, avoidUnpaved = false)

    val spoken = ArrayList<Pair<Long, SpokenPrompt>>()
    val requests = ArrayList<Pair<Long, RouteRequest>>()
    val banners = ArrayList<Pair<Long, String>>()
    val states = ArrayList<GuidanceState>()
    val events = ArrayList<Pair<Long, GuidanceEvent>>()
    val log = ArrayList<String>()
    var last: GuidanceState? = null

    private val pendingDone = ArrayList<Pair<Long, Long>>()
    private val pendingResults = ArrayList<Triple<Long, Int, () -> RouteOutcome>>()
    private var token = 0

    private val speaker = object : Speaker {
        override fun play(prompt: SpokenPrompt) {
            spoken += clock.now to prompt
            pendingDone += (clock.now + minOf(5_000L, 300L + prompt.text.length * 55L)) to prompt.id
        }

        override fun stop() {
            pendingDone.clear()
        }
    }

    private val requester = object : RouteRequester {
        override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
            liveRequester?.let { live ->
                requests += clock.now to request
                return live.start(request, generation, onResult)
            }
            if (!online) {
                onResult(RouteOutcome.Offline)
                return Cancelable { }
            }
            requests += clock.now to request
            val id = ++token
            val r = if (responses.isEmpty()) Triple(503, ByteArray(0), null) else responses.removeAt(0)
            val compute = {
                if (r.first == 200) processor.process(r.second, generation) else RouteClassifier.classify(r.first, r.third, r.second)
            }
            pendingResults += Triple(clock.now + latencyMs, id) { compute() }
            callbacks[id] = onResult
            return Cancelable { pendingResults.removeAll { it.second == id }; callbacks.remove(id)?.invoke(RouteOutcome.Cancelled) }
        }
    }
    private val callbacks = HashMap<Int, (RouteOutcome) -> Unit>()

    val core = GuidanceCore(
        clock = clock,
        initialRoute = initial,
        trip = trip,
        navigators = navigators,
        requester = requester,
        speaker = speaker,
        stringsFor = { TestStrings.of(it) },
        lang = lang,
        muted = muted,
        online = online,
        onState = { s ->
            states += s
            last = s
            val text = bannerText(s.banner, lang)
            if (banners.lastOrNull()?.second != text) banners += clock.now to text
        },
        onEvent = { events += clock.now to it },
        log = DebugLog { log += it },
    )

    fun bannerText(b: Banner, l: Lang): String {
        val strings = TestStrings.of(l)
        return when (b) {
            is Banner.Maneuver -> BannerText.text(b.key, l, strings)
            is Banner.Rerouting -> strings[StringKey.NAV_REROUTING] + (
                b.secondary?.let {
                    " / " + strings[
                        when (it) {
                            RerouteSecondary.UNAVAILABLE -> StringKey.ROUTE_UNAVAILABLE
                            RerouteSecondary.NO_ROUTE -> StringKey.ROUTE_NO_ROUTE
                            RerouteSecondary.ERROR -> StringKey.STATUS_GENERIC_ERROR
                            RerouteSecondary.OFFLINE -> StringKey.STATUS_OFFLINE
                        },
                    ]
                } ?: ""
                )
            is Banner.Arrival -> BannerText.text(b.key, l, strings)
            Banner.Restoring -> strings[StringKey.STATUS_LOADING] // NAV-012 restoring banner
        }
    }

    /**
     * Starts with the first fix, then plays [fixes] (including the first) and [tailMs] more of ticks. NAV-012:
     * [restoredAt] ≠ null starts a restored session at that time instead ([GuidanceCore.startRestored]); every fix,
     * including the first, is then delivered as an update.
     */
    fun run(
        fixes: List<Fix>,
        tailMs: Long = 5_000,
        networkChanges: Map<Long, Boolean> = emptyMap(),
        restoredAt: Long? = null,
        callSignals: Map<Long, Boolean> = emptyMap(),
    ) {
        require(fixes.isNotEmpty() || restoredAt != null)
        val i0: Int
        if (restoredAt != null) {
            clock.now = restoredAt
            core.startRestored(showNotice = true)
            i0 = 0
        } else {
            clock.now = fixes.first().elapsedMs
            core.start(fixes.first())
            i0 = 1
        }
        val end = (fixes.lastOrNull()?.elapsedMs ?: clock.now) + tailMs
        var i = i0
        var t = clock.now
        while (t <= end) {
            clock.now = t
            networkChanges[t]?.let { online = it; core.onNetwork(it) }
            pendingDone.filter { it.first <= t }.forEach { core.onSpeakerDone(it.second) }
            pendingDone.removeAll { it.first <= t }
            val due = pendingResults.filter { it.first <= t }
            pendingResults.removeAll { it.first <= t }
            for ((_, id, compute) in due) callbacks.remove(id)?.invoke(compute())
            callSignals[t]?.let { core.onCallSignal(it) }
            while (i < fixes.size && fixes[i].elapsedMs <= t) core.onFix(fixes[i++])
            if (t % 500 == 0L) core.onTick()
            t += 100
        }
    }

    fun spokenTexts(): List<String> = spoken.map { it.second.text }
}

/** Local metric distance from a point to a polyline (equirectangular, fine at city scale). */
fun distanceToLine(p: LatLon, line: List<LatLon>): Double {
    val k = Math.cos(Math.toRadians(p.lat)) * 111_320.0
    fun xy(q: LatLon) = Pair((q.lon - p.lon) * k, (q.lat - p.lat) * 110_540.0)
    var best = Double.MAX_VALUE
    for (i in 0 until line.size - 1) {
        val (ax, ay) = xy(line[i])
        val (bx, by) = xy(line[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        best = minOf(best, Math.sqrt(cx * cx + cy * cy))
    }
    return best
}
