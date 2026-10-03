package mn.navmn.app.qa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.FerrostarNavigatorFactory
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.engine.GuidanceCore
import mn.navmn.app.engine.GuidanceEvent
import mn.navmn.app.engine.GuidanceState
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
import mn.navmn.app.route.GuidancePlan
import mn.navmn.app.route.OsrmPlanParser
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteClassifier
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.VirtualClock
import mn.navmn.app.voiceplan.Speaker
import mn.navmn.app.voiceplan.SpokenPrompt
import java.io.File
import java.time.Instant

/*
 * QA support for NAV-005 (owner: qa-engineer). Test plan docs/qa/test-plans/NAV-005.md.
 * Independent of the mobile engineer's support/Replay.kt on purpose: timestamped states, request bodies as sent,
 * per-request latency, and oracles computed from the GPX and the recorded route only (never from app output).
 */

val P1 = LatLon(47.9189, 106.9176)
val P2 = LatLon(47.9139, 106.9044)
val P3 = LatLon(47.8858, 106.9173)
val X1 = LatLon(49.0270, 104.0440)

fun repoFile(rel: String): File = File(TestStrings.repoRoot, rel)

/** The QA GPX set tests/gpx/nav005 (make_gpx.py): real <time> stamps and the nav:acc/speed/course extension. */
object QaGpx {
    private val TRKPT = Regex("<trkpt\\s+lat=\"([-0-9.]+)\"\\s+lon=\"([-0-9.]+)\"\\s*>([\\s\\S]*?)</trkpt>")
    private fun tag(body: String, name: String): String? = Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)

    val manifest: JsonObject by lazy { Json.parseToJsonElement(repoFile("tests/gpx/nav005/manifest.json").readText()).jsonObject }

    fun meta(id: String): JsonObject =
        manifest["tracks"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }

    /** Fixes on a virtual timeline starting at 0 ms (the GPX's first <time>). */
    fun fixes(id: String): List<Fix> {
        val text = repoFile(meta(id)["file"]!!.jsonPrimitive.content).readText()
        var t0: Long? = null
        return TRKPT.findAll(text).map { m ->
            val body = m.groupValues[3]
            val wall = Instant.parse(tag(body, "time")!!).toEpochMilli()
            val base = t0 ?: wall.also { t0 = it }
            val course = tag(body, "nav:course")?.toDouble()
            Fix(
                lat = m.groupValues[1].toDouble(),
                lon = m.groupValues[2].toDouble(),
                accuracyM = tag(body, "nav:acc")?.toDouble() ?: 5.0,
                bearingDeg = course,
                bearingAccuracyDeg = if (course != null) 10.0 else null,
                speedMps = tag(body, "nav:speed")?.toDouble(),
                elapsedMs = wall - base,
                wallTimeMs = wall,
            )
        }.toList()
    }

    fun routeBytes(id: String, key: String = "route"): ByteArray = repoFile(meta(id)[key]!!.jsonPrimitive.content).readBytes()

    fun reroutes(id: String): List<ByteArray> = meta(id)["reroutes"]!!.jsonArray.map { repoFile(it.jsonPrimitive.content).readBytes() }
}

/** Every Valhalla text field replaced by the sentinel (AC 27). */
fun sentinel(bytes: ByteArray): ByteArray {
    fun walk(el: JsonElement, key: String?): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { (k, v) -> walk(v, k) })
        is JsonArray -> JsonArray(el.map { walk(it, key) })
        is JsonPrimitive -> if (el.isString && key in setOf("instruction", "text", "announcement", "ssmlAnnouncement")) JsonPrimitive("VALHALLA_TEXT_SENTINEL") else el
    }
    return walk(Json.parseToJsonElement(bytes.decodeToString()), null).toString().encodeToByteArray()
}

fun planOf(bytes: ByteArray, generation: Int = 0): GuidancePlan = OsrmPlanParser.plan(OsrmPlanParser.parseJson(bytes)!!, generation)!!

/**
 * Oracle from the recorded route only: along-route position of a point (monotonic window projection) and the
 * along-route position of each manoeuvre (cumulative OSRM step distances, scaled to the decoded geometry length).
 */
class RouteOracle(val plan: GuidancePlan) {
    private val pts = plan.geometry
    private val cum = DoubleArray(pts.size).also { c -> for (i in 1 until pts.size) c[i] = c[i - 1] + Geo.distance(pts[i - 1], pts[i]) }
    val length = cum.last()
    val maneuverAlong: List<Double> = run {
        val total = plan.steps.sumOf { it.distance }.takeIf { it > 0 } ?: 1.0
        val k = length / total
        var c = 0.0
        plan.steps.map { s -> (c * k).also { c += s.distance } }
    }
    private var last = 0.0

    /** Projection restricted to [last − 60 m, last + 800 m] so self-approaching geometry (rotaries) cannot jump. */
    fun along(p: LatLon, reset: Boolean = false, global: Boolean = false): Pair<Double, Double> {
        if (reset) last = 0.0
        val kx = Math.cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        var bestAlong = last
        for (i in 0 until pts.size - 1) {
            if (!global && (cum[i + 1] < last - 60 || cum[i] > last + 800)) continue
            val ax = (pts[i].lon - p.lon) * kx
            val ay = (pts[i].lat - p.lat) * ky
            val bx = (pts[i + 1].lon - p.lon) * kx
            val by = (pts[i + 1].lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = Math.sqrt(cx * cx + cy * cy)
            if (d < best) {
                best = d
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        last = maxOf(last, bestAlong)
        return bestAlong to best
    }

    /** Along-route position at time [t] (linear between fixes), from the fixes' own positions. */
    fun alongTimeline(fixes: List<Fix>): List<Pair<Long, Double>> {
        last = 0.0
        return fixes.map { it.elapsedMs to along(it.latLon).first }
    }
}

fun interpolate(timeline: List<Pair<Long, Double>>, t: Long): Double {
    if (t <= timeline.first().first) return timeline.first().second
    for (i in 1 until timeline.size) {
        val (t1, a1) = timeline[i]
        if (t <= t1) {
            val (t0, a0) = timeline[i - 1]
            // A gap in fixes (GPS loss) is not interpolated: the device knows only the last fix.
            if (t1 - t0 > 2_000) return a0
            return a0 + (a1 - a0) * (t - t0).toDouble() / (t1 - t0).toDouble()
        }
    }
    return timeline.last().second
}

/** A scripted response to one reroute request. */
data class Resp(val status: Int, val body: ByteArray = ByteArray(0), val retryAfter: String? = null, val latencyMs: Long = 300)

/**
 * Drives [GuidanceCore] like the engine thread on a virtual clock (100 ms steps, 500 ms ticker, speaker completions,
 * route responses after their latency) with the REAL Ferrostar session. Captures timestamped states, spoken prompts
 * (fake TTS input), requests with the exact body JSON, events and the debug log.
 */
class QaRun(
    routeBytes: ByteArray,
    val mode: TravelMode,
    val lang: Lang = Lang.MN,
    destination: LatLon? = null,
    private val responses: MutableList<Resp> = ArrayList(),
    var online: Boolean = true,
    muted: Boolean = false,
    /** Synchronous real requester (live gateway): the callback is delivered inside start(). */
    private val live: RouteRequester? = null,
    /** Simulated speech duration per prompt. */
    private val speechMs: (SpokenPrompt) -> Long = { minOf(5_000L, 300L + it.text.length * 55L) },
    avoidUnpaved: Boolean = false,
) {
    val clock = VirtualClock()
    private val processor = RouteProcessor(FerrostarRouteParser())
    val initial: ParsedRoute = (processor.process(routeBytes, 0) as? RouteOutcome.Ok)?.route ?: error("route does not parse")
    val trip = Trip(destination ?: initial.plan.end, null, mode, avoidUnpaved)

    val states = ArrayList<Pair<Long, GuidanceState>>()
    val spoken = ArrayList<Pair<Long, SpokenPrompt>>()
    val requests = ArrayList<Pair<Long, RouteRequest>>()
    val bodies = ArrayList<String>()
    val responsesAt = ArrayList<Pair<Long, RouteOutcome>>()
    val events = ArrayList<Pair<Long, GuidanceEvent>>()
    val log = ArrayList<String>()
    val stops = ArrayList<Long>()
    var inFlightMax = 0
    private var inFlightNow = 0

    private val done = ArrayList<Pair<Long, Long>>()
    private val pending = ArrayList<Triple<Long, Int, () -> RouteOutcome>>()
    private val callbacks = HashMap<Int, (RouteOutcome) -> Unit>()
    private var token = 0

    private val speaker = object : Speaker {
        override fun play(prompt: SpokenPrompt) {
            spoken += clock.now to prompt
            done += (clock.now + speechMs(prompt)) to prompt.id
        }

        override fun stop() {
            stops += clock.now
            done.clear()
        }
    }

    private val requester = object : RouteRequester {
        override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
            requests += clock.now to request
            bodies += RouteBody.json(request)
            live?.let { l ->
                return l.start(request, generation) { out -> responsesAt += clock.now to out; onResult(out) }
            }
            if (!online) {
                onResult(RouteOutcome.Offline)
                return Cancelable { }
            }
            inFlightNow++
            inFlightMax = maxOf(inFlightMax, inFlightNow)
            val id = ++token
            val r = if (responses.isEmpty()) Resp(503) else responses.removeAt(0)
            val compute = { if (r.status == 200) processor.process(r.body, generation) else RouteClassifier.classify(r.status, r.retryAfter, r.body) }
            pending += Triple(clock.now + r.latencyMs, id) { compute() }
            callbacks[id] = onResult
            return Cancelable {
                if (pending.removeAll { it.second == id }) inFlightNow--
                callbacks.remove(id)?.invoke(RouteOutcome.Cancelled)
            }
        }
    }

    val core = GuidanceCore(
        clock = clock,
        initialRoute = initial,
        trip = trip,
        navigators = FerrostarNavigatorFactory(),
        requester = requester,
        speaker = speaker,
        stringsFor = { TestStrings.of(it) },
        lang = lang,
        muted = muted,
        online = online,
        onState = { s -> states += clock.now to s },
        onEvent = { events += clock.now to it },
        log = DebugLog { log += it },
    )

    fun bannerText(b: Banner, l: Lang = lang): String {
        val s = TestStrings.of(l)
        return when (b) {
            is Banner.Maneuver -> BannerText.text(b.key, l, s)
            is Banner.Arrival -> BannerText.text(b.key, l, s)
            Banner.Restoring -> s[StringKey.STATUS_LOADING] // NAV-012 restoring banner
            is Banner.Rerouting -> s[StringKey.NAV_REROUTING] + (
                b.secondary?.let {
                    " / " + s[
                        when (it) {
                            RerouteSecondary.UNAVAILABLE -> StringKey.ROUTE_UNAVAILABLE
                            RerouteSecondary.NO_ROUTE -> StringKey.ROUTE_NO_ROUTE
                            RerouteSecondary.ERROR -> StringKey.STATUS_GENERIC_ERROR
                            RerouteSecondary.OFFLINE -> StringKey.STATUS_OFFLINE
                        },
                    ]
                } ?: ""
                )
        }
    }

    /** (time, banner text) at every change. */
    fun bannerChanges(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for ((t, s) in states) {
            val text = bannerText(s.banner)
            if (out.lastOrNull()?.second != text) out += t to text
        }
        return out
    }

    fun stateAt(t: Long): GuidanceState? = states.lastOrNull { it.first <= t }?.second

    fun spokenTexts(): List<String> = spoken.map { it.second.text }

    /** Plays [fixes] (the first one starts guidance), [tailMs] of ticks after the last; [hooks] run at exact times. */
    fun run(fixes: List<Fix>, tailMs: Long = 5_000, networkChanges: Map<Long, Boolean> = emptyMap(), hooks: Map<Long, () -> Unit> = emptyMap()) {
        clock.now = fixes.first().elapsedMs
        core.start(fixes.first())
        val end = fixes.last().elapsedMs + tailMs
        var i = 1
        var t = clock.now
        while (t <= end) {
            clock.now = t
            hooks[t]?.invoke()
            networkChanges[t]?.let { online = it; core.onNetwork(it) }
            done.filter { it.first <= t }.forEach { core.onSpeakerDone(it.second) }
            done.removeAll { it.first <= t }
            val due = pending.filter { it.first <= t }
            pending.removeAll { it.first <= t }
            for ((_, id, compute) in due) {
                inFlightNow--
                val out = compute()
                responsesAt += t to out
                callbacks.remove(id)?.invoke(out)
            }
            while (i < fixes.size && fixes[i].elapsedMs <= t) core.onFix(fixes[i++])
            if (t % 500 == 0L) core.onTick()
            t += 100
        }
    }
}

/** QA's own transcription of NAV-004 AC 27 (copied from tests/e2e/nav004/helpers.mjs, transcribed from the story). */
object Ac27 {
    val pairs: List<Pair<String, String>> = listOf(
        "Хойд зүг рүү явна уу" to "Head north", "Зүүн хойд зүг рүү явна уу" to "Head northeast",
        "Зүүн зүг рүү явна уу" to "Head east", "Зүүн өмнө зүг рүү явна уу" to "Head southeast",
        "Өмнө зүг рүү явна уу" to "Head south", "Баруун өмнө зүг рүү явна уу" to "Head southwest",
        "Баруун зүг рүү явна уу" to "Head west", "Баруун хойд зүг рүү явна уу" to "Head northwest",
        "Зүүн тийш эргэнэ үү" to "Turn left", "Баруун тийш эргэнэ үү" to "Turn right",
        "Бага зэрэг зүүн тийш эргэнэ үү" to "Turn slightly left", "Бага зэрэг баруун тийш эргэнэ үү" to "Turn slightly right",
        "Огцом зүүн тийш эргэнэ үү" to "Turn sharp left", "Огцом баруун тийш эргэнэ үү" to "Turn sharp right",
        "Буцаж эргэнэ үү" to "Make a U-turn", "Чигээрээ явна уу" to "Continue straight",
        "Зүүн талаа барина уу" to "Keep left", "Баруун талаа барина уу" to "Keep right",
        "Замд нийлнэ үү" to "Merge", "Зүүн талаас замд нийлнэ үү" to "Merge from the left",
        "Баруун талаас замд нийлнэ үү" to "Merge from the right", "Орох зам руу эргэнэ үү" to "Take the ramp",
        "Зүүн талын орох зам руу эргэнэ үү" to "Take the ramp on the left", "Баруун талын орох зам руу эргэнэ үү" to "Take the ramp on the right",
        "Гарах зам руу эргэнэ үү" to "Take the exit", "Зүүн талын гарах зам руу эргэнэ үү" to "Take the exit on the left",
        "Баруун талын гарах зам руу эргэнэ үү" to "Take the exit on the right",
        "Тойрогт орно уу" to "Enter the roundabout", "Тойргоос гарна уу" to "Exit the roundabout",
        "Та очих газартаа ирлээ" to "You have arrived", "Таны очих газар зүүн талд байна" to "Your destination is on the left",
        "Таны очих газар баруун талд байна" to "Your destination is on the right",
    )
    val mn: Set<String> = pairs.map { it.first }.toSet() + (1..99).map { "Тойрог: $it-р гарц" }
    val en: Set<String> = pairs.map { it.second }.toSet() + (1..99).map { "Roundabout: exit $it" }

    private val BARE = Regex("(зүүн|баруун)(?!\\s+(тийш|талаа|талаас|талын|талд|зүг|хойд|өмнө|эгнээ))", RegexOption.IGNORE_CASE)

    /** NAV-005 AC 28 scan (street-name line excluded: only the instruction text is passed). */
    fun ac28Problems(text: String): List<String> {
        val p = ArrayList<String>()
        if (Regex("[A-Za-z]").containsMatchIn(text)) p += "Latin letter"
        if (Regex("[<>]|\\{[^}]*\\}").containsMatchIn(text)) p += "token"
        if (BARE.containsMatchIn(text)) p += "bare зүүн/баруун"
        if (Regex("[\u200B\u200C\u200D\uFEFF]").containsMatchIn(text)) p += "zero-width"
        if (Regex("навигаци|зорьсон газар|налуу зам|Төвлөрүүлэх", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("км/ц(?!аг)").containsMatchIn(text) || Regex("\\d+\\s*(дахь|дэх)").containsMatchIn(text)
        ) p += "Avoid term"
        if (text !in mn) p += "not an AC 27 text"
        return p
    }

    /** NAV-005 AC 33 voice scan (NAV-007 AC 13 rules). */
    fun ac33Problems(text: String): List<String> {
        val p = ArrayList<String>()
        if (Regex("\\d\\s?(м|км)(\\s|-|/|$)").containsMatchIn(text)) p += "unit abbreviation"
        if (Regex("\\d+-р").containsMatchIn(text)) p += "digit ordinal"
        if (Regex("(\\d+|нэг|хоёр|гурав|дөрөв|тав|зургаа|долоо|найм|ес|арав)\\s(дахь|дэх)").containsMatchIn(text)) p += "old ordinal"
        if (text.contains("ШТС")) p += "ШТС"
        if (Regex("[A-Za-z]").containsMatchIn(text.replace("GPS", ""))) p += "Latin"
        if (Regex("[<>{}]").containsMatchIn(text)) p += "token"
        if (Regex("[\u200B\u200C\u200D\uFEFF]").containsMatchIn(text)) p += "zero-width"
        if (BARE.containsMatchIn(text)) p += "bare зүүн/баруун"
        return p
    }

    /** Distance stated by a Mongolian prompt (prefix, approaching or continue-on), metres; null when none. */
    fun statedMetres(text: String): Double? {
        val m = Regex("^(\\d+(?:,\\d)?) (метрт|километрт|километр үргэлжлүүлэн)").find(text) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDouble()
        return if (m.groupValues[2] == "метрт") n else n * 1000.0
    }
}
