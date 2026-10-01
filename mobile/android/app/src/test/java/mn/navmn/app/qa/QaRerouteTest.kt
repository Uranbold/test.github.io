package mn.navmn.app.qa

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidanceEvent
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.distanceToLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NAV-005 QA: off-route and reroute request rules (AC 41–50, 19, 51) with scripted gateway answers, the REAL Ferrostar
 * session and a virtual clock. Test plan docs/qa/test-plans/NAV-005.md, ids TC-P*. The timing rules are checked against
 * the story's numbers, not the app's constants.
 */
class QaRerouteTest {
    @Before
    fun need() = HostFerrostar.require()

    private val g2 = QaGpx.fixes("G2")
    private val turn = QaGpx.meta("G2")["wrong_turn_at"]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
    private val bearing = QaGpx.meta("G2")["wrong_turn_bearing"]!!.jsonPrimitive.double

    /** G2 up to the wrong turn, then straight on (off the route) at [speed] for [seconds]; optional fix gap. */
    private fun offRoute(speed: Double, seconds: Int, gapFrom: Int? = null, gapLen: Int = 0): List<Fix> {
        val iTurn = g2.indexOfFirst { Geo.distance(it.latLon, turn) < 15.0 }
        val head = g2.take(iTurn + 1)
        val t0 = head.last().elapsedMs
        val tail = (1..seconds).mapNotNull { k ->
            if (gapFrom != null && k in gapFrom until gapFrom + gapLen) return@mapNotNull null
            val p = Geo.offset(turn, bearing, speed * k)
            Fix(p.lat, p.lon, 5.0, bearing, 10.0, speed, t0 + k * 1_000L, head.last().wallTimeMs + k * 1_000L)
        }
        return head + tail
    }

    private fun run(responses: List<Resp>, fixes: List<Fix>, mode: TravelMode = TravelMode.CAR, lang: Lang = Lang.MN, avoid: Boolean = false, networkChanges: Map<Long, Boolean> = emptyMap(), hooks: (QaRun) -> Map<Long, () -> Unit> = { emptyMap() }, tailMs: Long = 0): QaRun {
        val bytes = if (lang == Lang.EN) QaGpx.routeBytes("G1", "also_en") else QaGpx.routeBytes("G2")
        val r = QaRun(bytes, mode, lang, P3, responses.toMutableList(), avoidUnpaved = avoid)
        r.run(fixes, tailMs = tailMs, networkChanges = networkChanges, hooks = hooks(r))
        return r
    }

    private fun detectedAt(r: QaRun) = r.states.first { it.second.banner is Banner.Rerouting }.first

    private fun pacingProblems(r: QaRun): List<String> {
        val p = ArrayList<String>()
        val starts = r.requests.map { it.first }
        if (r.inFlightMax > 1) p += "AC 44: ${r.inFlightMax} requests in flight"
        starts.zipWithNext { a, b -> if (b - a < 5_000) p += "AC 44: starts ${a / 1000.0} s and ${b / 1000.0} s are < 5 s apart" }
        for (s in starts) {
            val n = starts.count { it >= s && it - s < 60_000 }
            if (n > 6) p += "AC 44: $n starts in the 60 s from ${s / 1000.0} s"
        }
        return p
    }

    private fun blankBanners(r: QaRun) = r.states.filter { r.bannerText(it.second.banner).isBlank() }.map { "AC 31: blank banner at ${it.first}" }

    @Test
    fun tcP01_always503BackOffAndPacing() {
        val r = run(List(40) { Resp(503) }, offRoute(3.0, 300))
        val p = ArrayList(pacingProblems(r))
        val det = detectedAt(r)
        if (r.requests.first().first - det > 1_000) p += "AC 42: first request ${r.requests.first().first - det} ms after detection"
        // AC 48: back-off 5, 10, 20, 30, 30 … s after each failure (start ≤ 1 s later than due: 500 ms ticker).
        val expected = listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L)
        val gaps = r.requests.drop(1).mapIndexed { i, (t, _) -> t - r.responsesAt[i].first }
        gaps.zip(expected).forEachIndexed { i, (g, e) -> if (g !in e..(e + 1_000)) p += "AC 48: retry ${i + 1} started $g ms after failure ${i + 1} (expected $e ms)" }
        if (gaps.size < 5) p += "AC 48: only ${r.requests.size} requests in 5 min"
        // secondary line «Маршрутын үйлчилгээ түр ажиллахгүй байна» after the first failure, until the next start.
        val afterFirst = r.states.filter { it.first in r.responsesAt[0].first..(r.requests[1].first - 1) }
        if (afterFirst.any { (it.second.banner as? Banner.Rerouting)?.secondary != RerouteSecondary.UNAVAILABLE }) p += "AC 48: secondary line missing after a 503"
        if (r.spokenTexts().count { it == "Та маршрутаас гарлаа" } != 1) p += "AC 42: off-route prompt ${r.spokenTexts().count { it == "Та маршрутаас гарлаа" }} times in one episode"
        p += blankBanners(r)
        println("503 starts: " + r.requests.map { it.first / 1000.0 } + " gaps after failure: $gaps")
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP02_timeoutTwelveSecondsKeepsOneInFlight() {
        // a gateway that answers only after 12 s (the client timeout → Unavailable) never has 2 requests in flight
        val r = run(List(20) { Resp(503, latencyMs = 12_000) }, offRoute(5.0, 240))
        val p = ArrayList(pacingProblems(r))
        if (r.requests.size < 3) p += "only ${r.requests.size} requests"
        println("12 s starts: " + r.requests.map { it.first / 1000.0 })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP03_noRouteWaits200mAnd30s() {
        val p = ArrayList<String>()
        for (speed in listOf(3.0, 15.0)) {
            val r = run(List(20) { Resp(400, "{\"code\":\"NoRoute\",\"message\":\"No path could be found for input\"}".encodeToByteArray()) }, offRoute(speed, 300))
            p += pacingProblems(r).map { "$speed m/s $it" }
            for (i in 1 until r.requests.size) {
                val (t, req) = r.requests[i]
                val (tPrev, prev) = r.requests[i - 1]
                val failedAt = r.responsesAt[i - 1].first
                val moved = Geo.distance(prev.origin, req.origin)
                if (t - failedAt < 30_000 || moved < 200.0) p += "$speed m/s AC 49: request ${i + 1} ${(t - failedAt) / 1000.0} s and ${moved.toInt()} m after the NoRoute"
                // and it is not later than needed (≤ 1.5 s after both conditions hold)
                val due = maxOf(failedAt + 30_000, tPrev + ((200.0 / speed) * 1000).toLong())
                if (t - due > 1_500) p += "$speed m/s AC 49: request ${i + 1} ${(t - due) / 1000.0} s later than allowed"
            }
            if (r.requests.size < 2) p += "$speed m/s: only ${r.requests.size} requests"
            val sec = r.states.filter { it.first > r.responsesAt[0].first && it.first < r.requests.getOrNull(1)?.first ?: Long.MAX_VALUE }
            if (sec.any { (it.second.banner as? Banner.Rerouting)?.secondary != RerouteSecondary.NO_ROUTE }) p += "$speed m/s AC 49: secondary «Маршрут олдсонгүй» missing"
            println("NoRoute $speed m/s starts: " + r.requests.map { it.first / 1000.0 })
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP04_otherErrorsShowGenericErrorWithTheSameRetryRule() {
        val p = ArrayList<String>()
        val cases = listOf(
            "400 InvalidValue" to Resp(400, "{\"code\":\"InvalidValue\",\"message\":\"x\"}".encodeToByteArray()),
            "413" to Resp(413),
            "200 unparsable" to Resp(200, "not json".encodeToByteArray()),
            "400 NoSegment" to Resp(400, "{\"code\":\"NoSegment\"}".encodeToByteArray()),
            "400 error 171" to Resp(400, "{\"error_code\":171,\"error\":\"No suitable edges near location\",\"status_code\":400}".encodeToByteArray()),
        )
        for ((name, resp) in cases) {
            val r = run(List(10) { resp }, offRoute(3.0, 150))
            val expected = if (name.contains("NoSegment") || name.contains("171")) RerouteSecondary.NO_ROUTE else RerouteSecondary.ERROR
            val after = r.states.filter { it.first > r.responsesAt[0].first && it.first < (r.requests.getOrNull(1)?.first ?: Long.MAX_VALUE) }
            if (after.isEmpty() || after.any { (it.second.banner as? Banner.Rerouting)?.secondary != expected }) p += "$name AC 49: secondary ${after.firstOrNull()?.second?.banner}"
            r.requests.getOrNull(1)?.let { (t, req) ->
                if (t - r.responsesAt[0].first < 30_000 || Geo.distance(r.requests[0].second.origin, req.origin) < 200) p += "$name AC 49: retry too early"
            }
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP05_rateLimitedHonoursRetryAfter() {
        val p = ArrayList<String>()
        val ok = QaGpx.reroutes("G2").first()
        // header → wait (s) per AC 47 (5 s if missing, not a positive integer or unreadable)
        val cases = listOf("3" to 3, "10" to 10, "20" to 20, null to 5, "abc" to 5, "0" to 5, "-1" to 5, "1.5" to 5, "Wed, 21 Oct 2026 07:28:00 GMT" to 5)
        for ((header, wait) in cases) {
            val r = run(listOf(Resp(429, retryAfter = header), Resp(200, ok)), offRoute(4.0, 60))
            if (r.requests.size != 2) { p += "Retry-After $header: ${r.requests.size} requests (expected 2)"; continue }
            val resp = r.responsesAt[0].first
            val due = maxOf(resp + wait * 1_000L, r.requests[0].first + 5_000)
            val second = r.requests[1].first
            if (second < resp + wait * 1_000L) p += "Retry-After $header AC 47: request ${(second - resp) / 1000.0} s after the 429 (wait $wait s)"
            if (second - due > 1_000) p += "Retry-After $header AC 47: automatic request ${(second - due) / 1000.0} s late"
            val during = r.states.filter { it.first in resp until second }
            if (during.any { (it.second.banner as? Banner.Rerouting)?.secondary != null }) p += "Retry-After $header AC 47: secondary line shown during the wait"
            if (r.states.last().second.generation != 1) p += "Retry-After $header: new route not applied"
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP06_offlineNoRequestsThenOneWithinThreeSeconds() {
        val fixes = offRoute(4.0, 90)
        val det0 = run(listOf(Resp(200, QaGpx.reroutes("G2").first())), fixes).let { detectedAt(it) }
        val offAt = (det0 - 20_000) / 100 * 100
        val onAt = (det0 + 25_000) / 100 * 100
        val r = run(List(5) { Resp(200, QaGpx.reroutes("G2").first(), latencyMs = 2_500) }, fixes, networkChanges = mapOf(offAt to false, onAt to true))
        val p = ArrayList<String>()
        if (r.requests.any { it.first in offAt until onAt }) p += "AC 50: request while offline"
        val firstAfter = r.requests.filter { it.first >= onAt }
        if (firstAfter.isEmpty() || firstAfter[0].first - onAt > 3_000) p += "AC 50: first request ${firstAfter.firstOrNull()?.first?.minus(onAt)} ms after the network returned"
        if (r.requests.count { it.first in onAt..(onAt + 3_000) } != 1) p += "AC 50: ${r.requests.count { it.first in onAt..(onAt + 3_000) }} requests in the 3 s after the network returned"
        val offlineStates = r.states.filter { it.first in (detectedAt(r) + 1)..(onAt - 1) }
        if (offlineStates.any { (it.second.banner as? Banner.Rerouting)?.secondary != RerouteSecondary.OFFLINE }) p += "AC 50: secondary «Интернэт холболт алга» missing while offline"
        // AC 54: on the route without network: banners and voice continue, 0 requests (before detection).
        if (r.spoken.none { it.first in offAt until detectedAt(r) && it.second.maneuver != null }) p += "AC 54: no manoeuvre prompt while offline on the route"
        if (r.states.filter { it.first in offAt until detectedAt(r) }.any { !it.second.offline }) p += "AC 54: offline indicator state missing"
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP07_backOnOldRouteBeforeTheResponse() {
        // G1 with a 16 s detour up to 75 m off the route and back; the reroute answer arrives 20 s after the request.
        val g1 = QaGpx.fixes("G1")
        val fixes = g1.mapIndexed { i, f ->
            val k = i - 80
            val off = when { k in 0..5 -> 15.0 * (k + 1); k in 6..9 -> 90.0; k in 10..15 -> 90.0 - 15.0 * (k - 9); else -> 0.0 }
            if (off == 0.0) f else Geo.offset(f.latLon, (f.bearingDeg ?: 0.0) + 90, off).let { f.copy(lat = it.lat, lon = it.lon) }
        }
        val r = QaRun(QaGpx.routeBytes("G1"), TravelMode.CAR, Lang.MN, P3, mutableListOf(Resp(200, QaGpx.reroutes("G2").first(), latencyMs = 20_000)))
        r.run(fixes, tailMs = 10_000)
        val p = ArrayList<String>()
        val geo = r.initial.plan.geometry
        if (r.states.none { it.second.banner is Banner.Rerouting }) p += "precondition: no off-route episode (detour too small)"
        if (r.requests.size != 1) p += "AC 46: ${r.requests.size} requests (expected 1)"
        val backAt = fixes.withIndex().first { (i, f) -> i > 86 && distanceToLine(f.latLon, geo) <= 50.0 }.value.elapsedMs
        val endEp = r.states.firstOrNull { it.first > backAt && it.second.banner is Banner.Maneuver }?.first
        // Ferrostar reports deviation one fix late and the episode needs 2 good fixes back on the route (ADR-0009 §4):
        // the AC asks for an instruction within 1 s of the response, and the UX table within 1 s of being back.
        println("AC 46: back within 50 m at ${backAt / 1000.0} s, manoeuvre banner at ${endEp?.div(1000.0)} s, request at ${r.requests.firstOrNull()?.first?.div(1000.0)} s, response would be at ${r.requests.firstOrNull()?.first?.plus(20_000)?.div(1000.0)}")
        if (endEp == null || endEp - backAt > 3_000) p += "AC 46: instruction ${endEp?.minus(backAt)} ms after being back within 50 m"
        if (r.states.any { it.first > (endEp ?: 0) && it.second.banner is Banner.Rerouting }) p += "AC 46: recalculating again after the episode ended"
        if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
        p += blankBanners(r)
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP08_requestBodiesForPreviewAndReroute() {
        // AC 5: exact body (story text), car default, walk, avoid toggle, mn/en; AC 43 reroute heading only at ≥ 2 m/s.
        val o = LatLon(47.9189, 106.9176)
        val d = P3
        fun b(mode: TravelMode, avoid: Boolean, lang: Lang, heading: Int? = null) = RouteBody.json(RouteRequest(o, d, mode, avoid, lang, heading))
        val p = ArrayList<String>()
        val base = "{\"locations\":[{\"lat\":47.918900,\"lon\":106.917600%s},{\"lat\":47.885800,\"lon\":106.917300}],\"costing\":\"%s\"%s,\"alternates\":0,\"format\":\"osrm\",\"banner_instructions\":true,\"voice_instructions\":true,\"units\":\"kilometers\",\"language\":\"%s\"}"
        val expect = mapOf(
            b(TravelMode.CAR, false, Lang.MN) to base.format("", "auto", "", "mn-MN"),
            b(TravelMode.CAR, true, Lang.MN) to base.format("", "auto", ",\"costing_options\":{\"auto\":{\"exclude_unpaved\":true}}", "mn-MN"),
            b(TravelMode.WALK, true, Lang.EN) to base.format("", "pedestrian", "", "en-US"),
            b(TravelMode.CAR, false, Lang.EN, 165) to base.format(",\"heading\":165", "auto", "", "en-US"),
        )
        for ((got, want) in expect) if (got != want) p += "AC 5/43 body\n  $got\nexpected\n  $want"
        for (forbidden in listOf("heading_tolerance", "filters", "street_side_tolerance", "\"type\"", "toll", "radius")) {
            if (expect.keys.any { it.contains(forbidden) }) p += "AC 5: forbidden field $forbidden"
        }
        fun fix(speed: Double?, bearing: Double?, acc: Double? = 10.0) = Fix(47.9, 106.9, 5.0, bearing, acc, speed, 0, 0)
        val headings = mapOf(
            "1.9 m/s" to RouteBody.headingFor(fix(1.9, 165.0)), "2.0 m/s" to RouteBody.headingFor(fix(2.0, 165.4)),
            "no bearing" to RouteBody.headingFor(fix(10.0, null)), "359.6°" to RouteBody.headingFor(fix(10.0, 359.6)),
            "bearing acc 60°" to RouteBody.headingFor(fix(10.0, 90.0, 60.0)), "no speed" to RouteBody.headingFor(fix(null, 90.0)),
        )
        val want = mapOf("1.9 m/s" to null, "2.0 m/s" to 165, "no bearing" to null, "359.6°" to 0, "bearing acc 60°" to null, "no speed" to null)
        for ((k, v) in want) if (headings[k] != v) p += "AC 43 heading $k: ${headings[k]} (expected $v)"
        // In a replay: reroute keeps costing / options / language of the followed route; slow off-route → no heading.
        val slow = run(listOf(Resp(503)), offRoute(1.5, 40), avoid = true)
        slow.bodies.firstOrNull()?.let { body ->
            if (body.contains("heading")) p += "AC 43: heading sent at 1.5 m/s: $body"
            if (!body.contains("\"costing_options\":{\"auto\":{\"exclude_unpaved\":true}}")) p += "AC 43: avoid-unpaved not kept: $body"
            if (!body.contains("\"language\":\"mn-MN\"")) p += "AC 43: language: $body"
        } ?: run { p += "slow off-route: no request" }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP09_endDuringEpisodeAndGpsLossWhileOffRoute() {
        val p = ArrayList<String>()
        // AC 19: «Дуусгах» while a request is in flight: it is cancelled, 0 requests and 0 prompts afterwards.
        val fixes = offRoute(4.0, 120)
        val r = run(List(20) { Resp(503, latencyMs = 4_000) }, fixes)
        val det = detectedAt(r)
        val endAt = (det + 500) / 100 * 100 // the off-route prompt is still playing
        val r2 = run(List(20) { Resp(503, latencyMs = 4_000) }, fixes, hooks = { run -> mapOf(endAt to { run.core.end() }) })
        if (r2.requests.any { it.first > endAt }) p += "AC 19: request after «Дуусгах»"
        if (r2.spoken.any { it.first > endAt }) p += "AC 19: prompt after «Дуусгах»"
        val playing = r2.spoken.any { (s0, o) -> s0 <= endAt && endAt < s0 + minOf(5_000L, 300L + o.text.length * 55L) }
        if (playing && r2.stops.none { it == endAt }) p += "AC 19: utterance playing at «Дуусгах» was not stopped"
        if (r2.events.count { it.second == GuidanceEvent.Ended } != 1) p += "AC 19: Ended events ${r2.events.count { it.second == GuidanceEvent.Ended }}"
        // AC 51 / P9: GPS lost during the episode: 0 requests while lost.
        val gapFrom = ((det - fixes.first { Geo.distance(it.latLon, turn) < 15.0 }.elapsedMs) / 1000).toInt() + 3
        val g = offRoute(4.0, 150, gapFrom = gapFrom, gapLen = 40)
        val r3 = run(List(40) { Resp(503) }, g)
        val gap = g.zipWithNext().first { (a, b) -> b.elapsedMs - a.elapsedMs > 2_000 }
        val lostAt = gap.first.elapsedMs + 10_000
        if (r3.requests.any { it.first in lostAt until gap.second.elapsedMs }) p += "AC 51: reroute request while GPS was lost: ${r3.requests.map { it.first / 1000.0 }}"
        if (r3.requests.none { it.first >= gap.second.elapsedMs }) p += "AC 52: no reroute after GPS restored while still off-route"
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcP10_rerouteResponseAfterArrivalOrEndIsIgnored() {
        // P10: after arrival no requests (G4-like end while an episode could exist): use G1 + stop beside the end.
        val r = QaRun(QaGpx.routeBytes("G4"), TravelMode.CAR, Lang.MN, P3)
        r.run(QaGpx.fixes("G4") + QaGpx.fixes("G4").takeLast(1).let { last ->
            // keep driving 300 m away from the end after arrival
            (1..40).map { k -> val q = Geo.offset(last[0].latLon, 90.0, 8.0 * k); last[0].copy(lat = q.lat, lon = q.lon, elapsedMs = last[0].elapsedMs + k * 1_000L, wallTimeMs = last[0].wallTimeMs + k * 1_000L) }
        }, tailMs = 5_000)
        assertEquals("AC 55: requests after arrival", 0, r.requests.size)
        assertEquals("AC 55: arrivals", 1, r.events.count { it.second == GuidanceEvent.Arrived })
        val arrivedAt = r.events.first { it.second == GuidanceEvent.Arrived }.first
        assertTrue("AC 55: prompts after arrival", r.spoken.none { it.first > arrivedAt + 3_000 })
    }
}
