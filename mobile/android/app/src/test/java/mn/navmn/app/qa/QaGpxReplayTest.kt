package mn.navmn.app.qa

import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidanceEvent
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.geo.Geo
import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.ManeuverInput
import mn.navmn.app.instructions.ManeuverRules
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.location.Fix
import mn.navmn.app.route.TravelMode
import mn.navmn.app.service.notification.RichNotification
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.distanceToLine
import mn.navmn.app.voiceplan.PromptClass
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NAV-005 QA: the GPX replay set tests/gpx/nav005 (G1–G9, G3b) through the guidance core and the REAL Ferrostar
 * navigation session, on a virtual clock (story AC 72). Test plan docs/qa/test-plans/NAV-005.md, ids TC-R*.
 * Oracles come from the GPX positions and the recorded route geometry, never from the app's own state.
 */
class QaGpxReplayTest {
    @Before
    fun need() = HostFerrostar.require()

    private val coordinate = Regex("-?\\d{1,3}\\.\\d{4,}")

    /** Tracks that start on the P1 → P3 car route (destination P3). Exact, so that G10 is not taken for a G1 variant. */
    private val P1_P3_TRACK = Regex("G[1234567][a-z]?")

    /** D68: an `arrive` whose final step is shorter than this (m, along the route) is exempt from AC 34's 20–250 m check. */
    private val D68_FINAL_STEP_M = 30.0

    /** D67 (AC 32): no generated voice text contains these. "1000 meter" also catches "1000 meters". */
    private val D67_BANNED = Regex("1000 метрт|1000 meter")

    private fun car(id: String, lang: Lang = Lang.MN, routeKey: String = "route", responses: MutableList<Resp> = ArrayList(), muted: Boolean = false) =
        QaRun(QaGpx.routeBytes(id, routeKey), TravelMode.CAR, lang, P3.takeIf { P1_P3_TRACK.matches(id) }, responses, muted = muted)

    /** AC 31: never blank; AC 28 / AC 30 scans on every non-recalculating banner. */
    private fun bannerProblems(r: QaRun, lang: Lang): List<String> {
        val out = ArrayList<String>()
        for ((t, s) in r.states) {
            val text = r.bannerText(s.banner, lang)
            if (text.isBlank()) out += "t=$t blank banner"
            if (s.banner is Banner.Rerouting) continue
            if (lang == Lang.MN) Ac27.ac28Problems(text).forEach { out += "t=$t «$text»: $it" }
            if (lang == Lang.EN) {
                if (text !in Ac27.en) out += "t=$t «$text»: not an AC 27 en text"
                if (Regex("[Ѐ-ӿ]").containsMatchIn(text)) out += "t=$t «$text»: Cyrillic"
            }
        }
        return out.distinct()
    }

    private fun voiceProblems(r: QaRun): List<String> =
        r.spokenTexts().flatMap { t -> Ac27.ac33Problems(t).map { "«$t»: $it" } }.distinct()

    private fun privacyProblems(r: QaRun): List<String> {
        val out = ArrayList<String>()
        r.log.filter { coordinate.containsMatchIn(it) }.forEach { out += "log: $it" }
        val strings = TestStrings.of(r.lang)
        for ((_, s) in r.states) {
            // NAV-012 (AC 1, 12, 47): the N1 rich notification replaced the NAV-005 S8 text; every visible field is scanned.
            val n = RichNotification.of(s, r.lang, strings)
            val all = listOfNotNull(n.title, n.text, n.bigText, n.subText, n.voiceAction, n.endAction).joinToString(" ")
            if (coordinate.containsMatchIn(all)) out += "notification: $all"
        }
        return out.distinct()
    }

    /**
     * AC 34 against the oracle (generation [gen] only): start ≤ 1 s after the trigger; nothing for a passed manoeuvre;
     * stated distance within max(30 m, 20 %) of the true remaining distance at speaking time; ≥ 1 prompt covering each
     * car manoeuvre except depart while it is 20–250 m ahead; no two prompts for one manoeuvre within 8 s.
     * D68 (PO 2026-10-01): a final `arrive` < 30 m after the previous manoeuvre is exempt from the 20–250 m check only,
     * provided it is not chained and its arrival prompt is produced exactly once ([d68ArriveProblems]).
     */
    private fun ac34Problems(r: QaRun, fixes: List<Fix>, gen: Int = 0, checkCoverage: Boolean = true, coverUntilAlong: Double = Double.MAX_VALUE): List<String> {
        val oracle = RouteOracle(r.initial.plan)
        val timeline = oracle.alongTimeline(fixes)
        val p = ArrayList<String>()
        val covered = HashSet<Int>()
        val lastFor = HashMap<Int, Long>()
        for ((t, sp) in r.spoken) {
            val man = sp.maneuver ?: continue
            if (man.first != gen) continue
            val m = man.second
            val along = interpolate(timeline, t)
            // AC 34: start within 1 s of the trigger when the channel is free; a queued prompt may wait up to 3 s (then dropped).
            val busy = r.spoken.any { (s0, o) -> o.id != sp.id && s0 <= sp.triggerAtMs && sp.triggerAtMs < s0 + minOf(5_000L, 300L + o.text.length * 55L) }
            if (t - sp.triggerAtMs > (if (busy) 3_000 else 1_000)) p += "«${sp.text}» started ${t - sp.triggerAtMs} ms after its trigger (busy=$busy)"
            if (sp.cls == PromptClass.ARRIVAL) continue
            if (m >= 1) {
                val d = oracle.maneuverAlong[m] - along
                if (d < -5) p += "«${sp.text}» for manoeuvre $m spoken ${-d} m after passing it"
                Ac27.statedMetres(sp.text)?.let { s ->
                    if (Math.abs(s - d) > maxOf(30.0, 0.2 * d)) p += "«${sp.text}» states ${s.toInt()} m, true ${d.toInt()} m"
                }
                if (d in 20.0..250.0) covered += m
                lastFor[m]?.let { if (t - it < 8_000) p += "navigation-ux §4.2 rule 2: two prompts for manoeuvre $m started ${t - it} ms apart («${sp.text}»)" }
                lastFor[m] = t
            }
            if (sp.text.contains(", дараа нь ") && m + 1 < oracle.maneuverAlong.size) {
                val d2 = oracle.maneuverAlong[m + 1] - along
                if (d2 in 20.0..250.0) covered += m + 1
            }
        }
        if (checkCoverage) {
            val steps = r.initial.plan.steps
            for (m in 1 until steps.size) {
                if (oracle.maneuverAlong[m] > coverUntilAlong) continue
                if (d68ExemptArrive(r, oracle, m)) {
                    // Checked even if a (forbidden) chained prompt "covered" it: D68 / §4.4 never chain `arrive`.
                    p += d68ArriveProblems(r, oracle, gen, m)
                    continue
                }
                if (m !in covered) p += "manoeuvre $m (${steps[m].maneuver}) had no prompt while 20–250 m ahead"
            }
        }
        return p
    }

    /**
     * NAV-005 AC 34 exemption (PO decision D68, navigation-ux §4.2 rule 6 / §4.4): ONLY the last manoeuvre, ONLY if it is
     * `arrive`, and ONLY if its final step (previous manoeuvre → route end) is < 30 m along the route. The length comes
     * from the recorded route geometry (oracle), never from the app's state. Every other manoeuvre keeps the 20–250 m check.
     */
    private fun d68ExemptArrive(r: QaRun, oracle: RouteOracle, m: Int): Boolean {
        val steps = r.initial.plan.steps
        if (m != steps.lastIndex || m < 1 || steps[m].maneuver.type != "arrive") return false
        return oracle.maneuverAlong[m] - oracle.maneuverAlong[m - 1] < D68_FINAL_STEP_M
    }

    /** D68 conditions for an exempt `arrive`: not chained with the previous manoeuvre; the arrival prompt exactly once. */
    private fun d68ArriveProblems(r: QaRun, oracle: RouteOracle, gen: Int, m: Int): List<String> {
        val p = ArrayList<String>()
        val finalStep = "%.1f".format(oracle.maneuverAlong[m] - oracle.maneuverAlong[m - 1])
        val arrivalPrompts = r.spoken.filter { it.second.cls == PromptClass.ARRIVAL && it.second.maneuver == (gen to m) }
        if (arrivalPrompts.size != 1) {
            p += "D68: manoeuvre $m (arrive, final step $finalStep m) is exempt from the 20–250 m check only if its arrival prompt is produced exactly once; produced ${arrivalPrompts.size} times: ${arrivalPrompts.map { "${it.first / 1000.0}s «${it.second.text}»" }}"
        }
        r.spoken.filter { it.second.maneuver == (gen to m - 1) && (it.second.text.contains(", дараа нь ") || it.second.text.contains(", then ")) }
            .forEach { p += "D68 / navigation-ux §4.4: `arrive` must never be chained, but manoeuvre ${m - 1} was announced chained: «${it.second.text}»" }
        return p
    }

    /** AC 31: after the position passes manoeuvre m, the banner shows manoeuvre m+1 (or arrival) within 1 s. */
    private fun bannerAdvanceProblems(r: QaRun, fixes: List<Fix>): List<String> {
        val oracle = RouteOracle(r.initial.plan)
        val timeline = oracle.alongTimeline(fixes)
        val steps = r.initial.plan.steps
        val p = ArrayList<String>()
        for (m in 1 until steps.size - 1) {
            val passAt = timeline.firstOrNull { it.second >= oracle.maneuverAlong[m] + 5 }?.first ?: continue
            val expected = r.bannerText(Banner.Maneuver(steps[m + 1].key, 0.0, "", null, false))
            val ok = r.states.any { (t, s) -> t in passAt..(passAt + 1_000) && s.generation == 0 && r.bannerText(s.banner) == expected && (s.banner as? Banner.Maneuver)?.let { b -> b.key == steps[m + 1].key } != false }
            if (!ok) {
                val shown = r.stateAt(passAt + 1_000)?.let { r.bannerText(it.banner) }
                p += "manoeuvre $m passed at ${passAt / 1000} s: banner after 1 s = «$shown», expected «$expected»"
            }
        }
        return p
    }

    // ------------------------------------------------------------------------------------------------ G1

    @Test
    fun tcR01_g1OnRoute() {
        val fixes = QaGpx.fixes("G1")
        val r = car("G1")
        r.run(fixes, tailMs = 15_000)
        val problems = ArrayList<String>()
        // AC 15 / 54: 0 route requests on the route.
        if (r.requests.isNotEmpty()) problems += "AC 15: ${r.requests.size} requests on the route"
        // AC 35: depart prompt once within 2 s.
        val depart = r.spoken.firstOrNull()
        if (depart == null || depart.first > 2_000 || !depart.second.text.endsWith("зүг рүү явна уу") && !depart.second.text.contains("зүг рүү явна уу, дараа нь")) problems += "AC 35: depart = $depart"
        if (r.spoken.count { it.second.maneuver?.second == 0 } != 1) problems += "AC 35: depart spoken ${r.spoken.count { it.second.maneuver?.second == 0 }} times"
        problems += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        problems += voiceProblems(r).map { "AC 33: $it" }
        problems += ac34Problems(r, fixes).map { "AC 34: $it" }
        problems += bannerAdvanceProblems(r, fixes).map { "AC 31: $it" }
        // AC 21: the banner distance updates on every fix (while a manoeuvre banner is shown and moving).
        val fixTimes = fixes.drop(1).map { it.elapsedMs }.toSet()
        val distAtFix = r.states.filter { it.first in fixTimes && it.second.banner is Banner.Maneuver }.groupBy { it.first }.mapValues { (it.value.last().second.banner as Banner.Maneuver).distanceM }
        val unchanged = distAtFix.entries.sortedBy { it.key }.zipWithNext().count { (a, b) -> a.value == b.value }
        if (unchanged > 2) problems += "AC 21: banner distance unchanged between $unchanged consecutive fixes"
        // AC 22: progress recomputed at least every 5 s while moving.
        val prog = r.states.filter { it.second.phase == GuidancePhase.NAVIGATING }.map { it.first to it.second.progress.distanceRemaining }
        var lastChange = prog.first().first
        var maxGap = 0L
        prog.zipWithNext { a, b -> if (a.second != b.second) { maxGap = maxOf(maxGap, b.first - lastChange); lastChange = b.first } }
        if (maxGap > 5_000) problems += "AC 22: progress unchanged for $maxGap ms"
        // AC 55: arrival once, side variant, approaching prompt before it, nothing after.
        val arrivals = r.events.filter { it.second == GuidanceEvent.Arrived }
        if (arrivals.size != 1) problems += "AC 55: ${arrivals.size} arrival events"
        val arriveText = r.spokenTexts().filter { it.startsWith("Таны очих газар") || it.startsWith("Та очих газартаа") }
        if (arriveText != listOf("Таны очих газар баруун талд байна")) problems += "AC 55: arrival prompts $arriveText"
        val iArr = r.spokenTexts().indexOfFirst { it.startsWith("Таны очих газар") }
        if (r.spokenTexts().take(maxOf(iArr, 0)).none { it.endsWith("метрт очих газартаа хүрнэ") }) problems += "AC 55: no approaching prompt «{n} метрт очих газартаа хүрнэ» before arrival: ${r.spokenTexts()}"
        if (iArr >= 0 && iArr != r.spoken.lastIndex) problems += "AC 55: prompts after arrival: ${r.spokenTexts().drop(iArr + 1)}"
        val arrivedBanner = r.states.last().second.banner
        if (r.bannerText(arrivedBanner) != "Таны очих газар баруун талд байна") problems += "AC 55: final banner «${r.bannerText(arrivedBanner)}»"
        problems += privacyProblems(r).map { "AC 67: $it" }
        println("G1 spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
        println("G1 banners: " + r.bannerChanges().map { "${it.first / 1000}s ${it.second}" })
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun tcR02_g1AndG8English() {
        val problems = ArrayList<String>()
        for (id in listOf("G1", "G8")) {
            val fixes = QaGpx.fixes(id)
            val r = car(id, Lang.EN, routeKey = "also_en")
            r.run(fixes, tailMs = 10_000)
            problems += bannerProblems(r, Lang.EN).map { "$id AC 30: $it" }
            r.spokenTexts().filter { Regex("[Ѐ-ӿ]").containsMatchIn(it) }.forEach { problems += "$id AC 32 en: Cyrillic in voice «$it»" }
            // NAV-005-D4 (round 3): English singular only when the formatted number is exactly "1" (navigation-ux §4.1).
            r.spokenTexts().filter { Regex("(^|[^\\d.,])1 (kilometers|meters)\\b").containsMatchIn(it) }.forEach { problems += "$id AC 32 en / D4: plural after 1 «$it»" }
            r.spokenTexts().filter { Regex("(\\d\\.\\d+|(^|\\D)([02-9]|\\d{2,})) (kilometer|meter)(?!s)\\b").containsMatchIn(it) }.forEach { problems += "$id AC 32 en / D4: singular after a number other than 1 «$it»" }
            if (r.requests.isNotEmpty()) problems += "$id: ${r.requests.size} requests"
            println("$id en spoken: " + r.spokenTexts())
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun tcR03_sentinelG1G8() {
        val problems = ArrayList<String>()
        for (id in listOf("G1", "G8")) {
            val r = QaRun(sentinel(QaGpx.routeBytes(id)), TravelMode.CAR, Lang.MN)
            r.run(QaGpx.fixes(id), tailMs = 10_000)
            val strings = TestStrings.of(Lang.MN)
            val screen = r.states.flatMap { (_, s) ->
                val n = RichNotification.of(s, Lang.MN, strings) // NAV-012 N1 (replaces the NAV-005 S8 text)
                listOf(r.bannerText(s.banner), (s.banner as? Banner.Maneuver)?.street.orEmpty(), (s.banner as? Banner.Arrival)?.street.orEmpty(), n.title.orEmpty(), n.text.orEmpty(), n.bigText.orEmpty())
            }
            val hits = (screen + r.spokenTexts()).count { it.contains("SENTINEL") }
            if (hits != 0) problems += "$id AC 27: sentinel appears $hits times"
            if (r.spoken.isEmpty() || r.states.isEmpty()) problems += "$id: nothing captured"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ G2

    @Test
    fun tcR04_g2WrongTurnReroute() {
        val fixes = QaGpx.fixes("G2")
        val rr = QaGpx.reroutes("G2").first()
        val r = car("G2", responses = mutableListOf(Resp(200, rr, latencyMs = 300)))
        r.run(fixes, tailMs = 15_000)
        val p = ArrayList<String>()
        val geo0 = r.initial.plan.geometry
        val firstFar = fixes.first { it.accuracyM <= 25 && distanceToLine(it.latLon, geo0) > 50.0 }.elapsedMs
        val detected = r.states.firstOrNull { it.second.banner is Banner.Rerouting }?.first
        if (detected == null) {
            p += "AC 41: off-route never detected"
        } else {
            if (detected - firstFar !in 0..8_000) p += "AC 41: detected ${detected - firstFar} ms after the first good fix > 50 m"
            val offPrompt = r.spoken.filter { it.second.text == "Та маршрутаас гарлаа" }
            if (offPrompt.size != 1) p += "AC 42: off-route prompt ${offPrompt.size} times"
            if (offPrompt.isNotEmpty() && offPrompt[0].first - detected > 1_000) p += "AC 42: off-route prompt ${offPrompt[0].first - detected} ms after detection"
            if (r.requests.isEmpty() || r.requests[0].first - detected > 1_000) p += "AC 42: first request at ${r.requests.firstOrNull()?.first} (detected $detected)"
            println("G2: first fix > 50 m at ${firstFar / 1000.0} s, detected at ${detected / 1000.0} s (+${(detected - firstFar) / 1000.0} s), request at ${r.requests.firstOrNull()?.first?.div(1000.0)} s")
        }
        if (r.requests.size != 1) p += "AC 44/45: ${r.requests.size} reroute requests (expected 1)"
        r.requests.firstOrNull()?.let { (t, req) ->
            val body = r.bodies[0]
            val fixAt = fixes.last { it.elapsedMs <= t }
            val expectedHeading = Math.round(fixAt.bearingDeg!!).toInt() % 360
            val expected = "{\"locations\":[{\"lat\":%.6f,\"lon\":%.6f,\"heading\":%d},{\"lat\":47.885800,\"lon\":106.917300}],\"costing\":\"auto\",\"alternates\":0,\"format\":\"osrm\",\"banner_instructions\":true,\"voice_instructions\":true,\"units\":\"kilometers\",\"language\":\"mn-MN\"}"
                .format(java.util.Locale.ROOT, fixAt.lat, fixAt.lon, expectedHeading)
            if (body != expected) p += "AC 43: body\n  $body\nexpected\n  $expected"
            if (req.destination != P3) p += "AC 43: destination ${req.destination}"
        }
        // AC 45: new route on the map and in the banner within 500 ms of the response.
        val okAt = r.responsesAt.firstOrNull()?.first
        val newAt = r.states.firstOrNull { it.second.generation == 1 && it.second.banner is Banner.Maneuver }?.first
        if (okAt == null || newAt == null || newAt - okAt > 500) p += "AC 45: response at $okAt, new route banner at $newAt"
        // after the reroute: catch-up prompt within 1 s, normal prompts, AC 28/33 scans, arrival once.
        // navigation-ux §4.3: catch-up triggered within 1 s of the new route (it may wait for the playing off-route prompt, §4.5 rule 1).
        if (newAt != null && r.spoken.none { it.second.triggerAtMs in newAt..(newAt + 1_000) && it.second.maneuver?.first == 1 && it.first - it.second.triggerAtMs <= 3_000 }) p += "UX §4.3: no catch-up prompt within 1 s after the new route"
        if (r.spoken.any { it.second.maneuver?.first == 1 && it.second.maneuver?.second == 0 }) p += "UX §4.2 rule 5: depart spoken after reroute"
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        p += voiceProblems(r).map { "AC 33: $it" }
        if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "AC 55: arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
        p += privacyProblems(r).map { "AC 67: $it" }
        println("G2 spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
        println("G2 banners: " + r.bannerChanges().map { "${it.first / 1000.0}s ${it.second}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ G3, G3b

    private fun gpsLossProblems(id: String, mk: () -> QaRun = { car(id) }): Pair<QaRun, List<String>> {
        val fixes = QaGpx.fixes(id)
        val r = mk()
        r.run(fixes, tailMs = 10_000)
        val p = ArrayList<String>()
        val gapAt = fixes.zipWithNext().first { (a, b) -> b.elapsedMs - a.elapsedMs > 2_000 }
        val lastBefore = gapAt.first.elapsedMs
        val resume = gapAt.second.elapsedMs
        val lostAt = r.states.firstOrNull { it.second.gpsLost }?.first
        // AC 51: within 1 s after 10 s without a good fix.
        if (lostAt == null || lostAt - (lastBefore + 10_000) !in 0..1_000) p += "AC 51: lost state at $lostAt, last fix $lastBefore"
        val sp = r.spokenTexts()
        if (sp.count { it == "GPS дохио тасарлаа" } != 1) p += "AC 51/53: lost spoken ${sp.count { it == "GPS дохио тасарлаа" }} times"
        if (sp.count { it == "GPS дохио сэргэлээ" } != 1) p += "AC 52/53: restored spoken ${sp.count { it == "GPS дохио сэргэлээ" }} times"
        val during = r.states.filter { it.first in (lostAt ?: resume)..(resume - 1) }
        if (during.any { !it.second.gpsLost }) p += "AC 51: lost state not continuous"
        if (during.map { it.second.progress }.toSet().size > 1) p += "AC 51: progress changed during the loss"
        if (during.any { it.second.puck?.stale != true }) p += "AC 51: puck not stale during the loss"
        if (r.spoken.any { it.first in (lostAt ?: resume)..(resume - 1) && it.second.maneuver != null }) p += "AC 51: manoeuvre prompt during the loss"
        if (r.requests.isNotEmpty()) p += "AC 51/53: ${r.requests.size} requests"
        // AC 52: restored visible ~3 s; resumes within 2 s: banner = next manoeuvre by the oracle.
        val restoredVisible = r.states.filter { it.second.gpsRestoredVisible }.map { it.first }
        // The arrival panel (S6) replaces the guidance screen, so arrival within 3 s of the restore may end the message early.
        val arrivedAt = r.events.firstOrNull { it.second == GuidanceEvent.Arrived }?.first ?: Long.MAX_VALUE
        val endsAtArrival = restoredVisible.isNotEmpty() && arrivedAt - restoredVisible.first() in 0..3_000 && restoredVisible.last() >= arrivedAt - 1_000
        if (restoredVisible.isEmpty() || restoredVisible.first() - resume > 1_000 || (!endsAtArrival && restoredVisible.last() - restoredVisible.first() !in 2_500..3_100)) {
            p += "AC 52: restored message visible ${restoredVisible.firstOrNull()}..${restoredVisible.lastOrNull()} (resume $resume)"
        }
        val oracle = RouteOracle(r.initial.plan)
        val along = oracle.along(gapAt.second.latLon, global = true).first
        val m = oracle.maneuverAlong.indexOfFirst { it > along + 5 }
        val expected = r.bannerText(Banner.Maneuver(r.initial.plan.steps[m].key, 0.0, "", null, false))
        val shown = r.stateAt(resume + 2_000)?.let { r.bannerText(it.banner) }
        if (shown != expected) p += "AC 53: banner 2 s after resume «$shown», expected «$expected»"
        // AC 52/53 (round 1): the banner distance is to the TRUE next manoeuvre, not a passed one with the same text.
        val at2 = fixes.last { it.elapsedMs <= resume + 2_000 }
        val along2 = oracle.along(at2.latLon, global = true).first
        val m2 = oracle.maneuverAlong.indexOfFirst { it > along2 + 5 }.takeIf { it >= 0 } ?: (oracle.maneuverAlong.size - 1)
        val dTrue = (if (m2 == oracle.maneuverAlong.size - 1) oracle.length else oracle.maneuverAlong[m2]) - along2
        (r.stateAt(resume + 2_000)?.banner as? Banner.Maneuver)?.let { b ->
            if (Math.abs(b.distanceM - dTrue) > maxOf(30.0, 0.2 * dTrue)) p += "AC 52/53: banner distance 2 s after resume ${b.distanceM.toInt()} m, true distance to manoeuvre $m2 ${dTrue.toInt()} m"
        }
        val remTrue = oracle.length - along
        val remShown = r.stateAt(resume + 2_000)?.progress?.distanceRemaining
        if (remShown == null || Math.abs(remShown - remTrue) > 60) p += "AC 52: remaining distance 2 s after resume ${remShown?.toInt()} m, true ${remTrue.toInt()} m"
        // Every manoeuvre after the resume point is still announced (navigation-ux §4.3 / AC 52 "guidance resumes").
        for (k in m until r.initial.plan.steps.size - 1) {
            if (r.spoken.none { it.first > resume && it.second.maneuver == (0 to k) }) p += "AC 52: manoeuvre $k (${r.initial.plan.steps[k].maneuver.type} ${r.initial.plan.steps[k].maneuver.modifier}) never announced after the restore"
        }
        if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
        p += privacyProblems(r).map { "AC 67: $it" }
        println("$id spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
        return r to p
    }

    @Test
    fun tcR05_g3ThirtySecondGap() {
        val (_, p) = gpsLossProblems("G3")
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcR06_g3bManoeuvrePassedDuringLossIsNotAnnounced() {
        val (r, p0) = gpsLossProblems("G3b")
        val p = ArrayList(p0)
        val fixes = QaGpx.fixes("G3b")
        val resume = fixes.zipWithNext().first { (a, b) -> b.elapsedMs - a.elapsedMs > 2_000 }.second.elapsedMs
        // Manoeuvre 1 (left turn) lies inside the gap: nothing may be spoken for it after the restore.
        if (r.spoken.any { it.first >= resume && it.second.maneuver == (0 to 1) }) p += "AC 52: manoeuvre passed during the loss announced after restore"
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ G4, G5

    @Test
    fun tcR07_g4ArrivalExactlyOnce() {
        val r = car("G4")
        r.run(QaGpx.fixes("G4"), tailMs = 10_000)
        val p = ArrayList<String>()
        if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "AC 56: ${r.events.count { it.second == GuidanceEvent.Arrived }} arrival events"
        val arr = r.spokenTexts().filter { it.startsWith("Таны очих газар") || it.startsWith("Та очих газартаа") }
        if (arr.size != 1) p += "AC 56: arrival spoken ${arr.size} times"
        val arrivedAt = r.events.firstOrNull { it.second == GuidanceEvent.Arrived }?.first ?: Long.MAX_VALUE
        if (r.spoken.any { it.first > arrivedAt && it.second.cls != PromptClass.ARRIVAL }) p += "AC 55: prompts after arrival"
        if (r.requests.isNotEmpty()) p += "AC 55: requests ${r.requests.size}"
        if (r.states.last().second.phase != GuidancePhase.ARRIVED) p += "phase ${r.states.last().second.phase}"
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcR08_g5Walking() {
        val p = ArrayList<String>()
        for (lang in listOf(Lang.MN, Lang.EN)) {
            val r = QaRun(QaGpx.routeBytes("G5", if (lang == Lang.MN) "route" else "also_en"), TravelMode.WALK, lang)
            r.run(QaGpx.fixes("G5"), tailMs = 10_000)
            if (r.requests.isNotEmpty()) p += "$lang: ${r.requests.size} requests"
            if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "$lang: arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
            p += bannerProblems(r, lang).map { "$lang AC 28/30: $it" }
            if (lang == Lang.MN) {
                p += voiceProblems(r).map { "AC 33: $it" }
                // navigation-ux §4.2 walk: main at 50 m, now at 15 m, never early or continue-on.
                if (r.spokenTexts().any { it.contains("километр") }) p += "walk: kilometre prompt ${r.spokenTexts()}"
            }
            println("G5 $lang spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ G6, G7

    @Test
    fun tcR09_g6OutlierAndG7PoorAccuracyNoReroute() {
        val p = ArrayList<String>()
        for (id in listOf("G6", "G7")) {
            val r = car(id)
            r.run(QaGpx.fixes(id), tailMs = 10_000)
            if (r.requests.isNotEmpty()) p += "$id AC 41: ${r.requests.size} reroute requests"
            if (r.states.any { it.second.banner is Banner.Rerouting }) p += "$id AC 41: recalculating banner shown"
            if (r.spokenTexts().contains("Та маршрутаас гарлаа")) p += "$id AC 41: off-route prompt"
            if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "$id: arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ G8, G9

    @Test
    fun tcR10_g8Roundabout() {
        val fixes = QaGpx.fixes("G8")
        val r = car("G8")
        r.run(fixes, tailMs = 10_000)
        val p = ArrayList<String>()
        val banners = r.bannerChanges().map { it.second }
        if ("Тойрог: 2-р гарц" !in banners) p += "AC 26: no «Тойрог: 2-р гарц» banner: $banners"
        if (r.spokenTexts().none { it.contains("тойрогт ороод хоёрдугаар гарцаар гарна уу") || it.startsWith("Тойрогт ороод хоёрдугаар гарцаар гарна уу") }) p += "AC 32: no roundabout exit-2 voice prompt: ${r.spokenTexts()}"
        if (r.requests.isNotEmpty()) p += "requests ${r.requests.size}"
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        p += voiceProblems(r).map { "AC 33: $it" }
        p += ac34Problems(r, fixes).map { "AC 34: $it" }
        p += bannerAdvanceProblems(r, fixes).map { "AC 31: $it" }
        if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
        // D68 (NAV-005-D3 closed): G8's final `arrive` is the exempt case; prove the fixture still exercises it, so the
        // exemption cannot hide a different gap, and that the arrival prompt (AC 55) is produced exactly once.
        val oracle = RouteOracle(r.initial.plan)
        val last = r.initial.plan.steps.lastIndex
        if (!d68ExemptArrive(r, oracle, last)) p += "D68: G8 fixture no longer has a final `arrive` < 30 m after the previous manoeuvre (final step ${oracle.maneuverAlong[last] - oracle.maneuverAlong[last - 1]} m); revisit tcR10"
        val arrivalTexts = r.spoken.filter { it.second.cls == PromptClass.ARRIVAL }.map { it.second.text }
        if (arrivalTexts != listOf("Таны очих газар зүүн талд байна")) p += "AC 55 / D68: arrival prompts $arrivalTexts, expected exactly once «Таны очих газар зүүн талд байна»"
        println("G8 spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
        println("G8 banners: " + r.bannerChanges().map { "${it.first / 1000}s ${it.second}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    @Test
    fun tcR11_g9IntercityKilometrePrompts() {
        val fixes = QaGpx.fixes("G9")
        val r = car("G9")
        r.run(fixes, tailMs = 0)
        val p = ArrayList<String>()
        val sp = r.spokenTexts()
        if (sp.none { Regex("^\\d+ километр үргэлжлүүлэн явна уу$").matches(it) }) p += "AC 32 (A12): no «… километр үргэлжлүүлэн явна уу»: $sp"
        if (sp.none { it.startsWith("2 километрт") }) p += "navigation-ux §4.2: no early prompt at 2 km (fast): $sp"
        if (r.requests.isNotEmpty()) p += "requests ${r.requests.size}"
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        p += voiceProblems(r).map { "AC 33: $it" }
        val oracle = RouteOracle(r.initial.plan)
        val endAlong = oracle.alongTimeline(fixes).last().second
        p += ac34Problems(r, fixes, coverUntilAlong = endAlong - 100).map { "AC 34: $it" }
        p += bannerAdvanceProblems(r, fixes).map { "AC 31: $it" }
        println("G9 spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
        println("G9 banners: " + r.bannerChanges().map { "${it.first / 1000}s ${it.second}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ mute, language, theme-like re-render

    @Test
    fun tcR12_muteLanguageSwitchMidRoute() {
        val fixes = QaGpx.fixes("G1")
        val p = ArrayList<String>()
        // AC 37: muted → 0 utterances or chimes; banners unaffected.
        val muted = car("G1", muted = true)
        muted.run(fixes, tailMs = 10_000)
        if (muted.spoken.isNotEmpty()) p += "AC 37: muted produced ${muted.spoken.size} prompts"
        val unmuted = car("G1")
        unmuted.run(fixes, tailMs = 10_000)
        if (muted.bannerChanges().map { it.second } != unmuted.bannerChanges().map { it.second }) p += "AC 37: banners differ when muted"
        // AC 60: switch to English at 150 s (mid-route), and mute toggled at 100 s / 120 s.
        val r = car("G1")
        val switchAt = 150_000L
        r.run(fixes, tailMs = 10_000, hooks = mapOf(100_000L to { r.core.setMuted(true) }, 120_000L to { r.core.setMuted(false) }, switchAt to { r.core.setLanguage(Lang.EN) }))
        if (r.spoken.any { it.first in 100_000L..119_999L }) p += "AC 37: prompt while muted"
        val after = r.spoken.filter { it.first > switchAt }.map { it.second }
        if (after.isEmpty() || after.any { it.lang != Lang.EN || Regex("[Ѐ-ӿ]").containsMatchIn(it.text) }) p += "AC 60: prompts after the switch ${after.map { it.text }}"
        if (r.requests.isNotEmpty()) p += "AC 60: requests ${r.requests.size}"
        val ids = r.spoken.mapNotNull { it.second.maneuver?.let { m -> m to it.second.text } }
        // 0 repeated prompts: no manoeuvre prompt text repeated for the same manoeuvre because of the switch.
        if (ids.groupBy { it.first }.any { (_, v) -> v.size > 4 }) p += "AC 60: repeated prompts $ids"
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /** AC 40: the generators run on the JVM; the golden set feeds NAV-007 AC 12 (snapshot owned by QA). */
    @Test
    fun tcR13_goldenAnnouncementSet() {
        val sb = StringBuilder()
        sb.append("# NAV-005 voice golden set (AC 40, feeds NAV-007 AC 12). Generated by QaGpxReplayTest.tcR13 from tests/gpx/nav005.\n")
        sb.append("# Format: track<TAB>lang<TAB>t_s<TAB>manoeuvre<TAB>text. Regenerate with QA_UPDATE_GOLDEN=1.\n")
        for ((id, lang, key) in listOf(Triple("G1", Lang.MN, "route"), Triple("G1", Lang.EN, "also_en"), Triple("G5", Lang.MN, "route"), Triple("G8", Lang.MN, "route"), Triple("G8", Lang.EN, "also_en"), Triple("G9", Lang.MN, "route"))) {
            val mode = if (id == "G5") TravelMode.WALK else TravelMode.CAR
            val r = QaRun(QaGpx.routeBytes(id, key), mode, lang)
            r.run(QaGpx.fixes(id), tailMs = if (id == "G9") 0 else 10_000)
            for ((t, s) in r.spoken) sb.append("$id\t${lang.name.lowercase()}\t${t / 1000}\t${s.maneuver?.second ?: "-"}\t${s.text}\n")
        }
        // AC 32 (D67): checked on the generated set, so a regeneration can never bake «1000 метрт» / "1000 meters" in.
        val thousand = sb.lines().filter { !it.startsWith("#") && D67_BANNED.containsMatchIn(it) }
        assertTrue("AC 32 (D67): golden set contains «1000 метрт» / \"1000 meters\":\n" + thousand.joinToString("\n"), thousand.isEmpty())
        val golden = repoFile("tests/gpx/nav005/golden/voice-golden.tsv")
        if (System.getenv("QA_UPDATE_GOLDEN") == "1" || !golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(sb.toString())
        }
        assertEquals("golden set changed (review, then regenerate with QA_UPDATE_GOLDEN=1)", golden.readText(), sb.toString())
    }

    // ------------------------------------------------------------------------------------------------ round 1: D1 catch-up

    /** Manoeuvres whose along-route position lies inside the gap must not be announced after the restore (AC 52). */
    private fun passedInGapAnnounced(r: QaRun, fixes: List<Fix>): List<String> {
        val gap = fixes.zipWithNext().first { (a, b) -> b.elapsedMs - a.elapsedMs > 2_000 }
        val oracle = RouteOracle(r.initial.plan)
        val a0 = oracle.along(gap.first.latLon, global = true).first
        val a1 = oracle.along(gap.second.latLon, global = true).first
        val inside = oracle.maneuverAlong.indices.filter { it >= 1 && oracle.maneuverAlong[it] in a0..a1 }
        return r.spoken.filter { it.first >= gap.second.elapsedMs && it.second.maneuver?.first == 0 && it.second.maneuver?.second in inside }
            .map { "AC 52: manoeuvre ${it.second.maneuver?.second} passed during the loss announced after restore at ${it.first / 1000.0} s: «${it.second.text}»" }
    }

    /**
     * TC-R14 (NAV-005-D1 re-verification): the 30 s gap covers the left turn and fixes resume only 40 m past it.
     * Last fix before the gap is 360 m before the turn (Ferrostar's 30 m step-advance entry never armed); the first fix
     * after it is 40 m from the passed step, so StepCatchUp (> 50 m) does not apply on that fix.
     */
    @Test
    fun tcR14_g3cResumeFortyMetresPastTheTurn() {
        val (r, p0) = gpsLossProblems("G3c")
        val p = ArrayList(p0)
        p += passedInGapAnnounced(r, QaGpx.fixes("G3c"))
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /** TC-R15: one 53 s gap covers BOTH turns; resume 60 m past the right turn, 55 m before the end (AC 52, 53, 55). */
    @Test
    fun tcR15_g3dGapCoversTwoManoeuvres() {
        val (r, p0) = gpsLossProblems("G3d")
        val p = ArrayList(p0)
        p += passedInGapAnnounced(r, QaGpx.fixes("G3d"))
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        println("G3d banners: " + r.bannerChanges().map { "${it.first / 1000.0}s ${it.second}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /** TC-R16: walking, a 57 s gap covers the right turn onto Seoul st.; fixes resume 40 m past it (AC 52, 53 for walk). */
    @Test
    fun tcR16_g5bWalkingGapOverATurn() {
        val (r, p0) = gpsLossProblems("G5b") { QaRun(QaGpx.routeBytes("G5b"), TravelMode.WALK, Lang.MN) }
        val p = ArrayList(p0)
        p += passedInGapAnnounced(r, QaGpx.fixes("G5b"))
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        println("G5b banners: " + r.bannerChanges().map { "${it.first / 1000.0}s ${it.second}" })
        println("G5b banner distance/street after the restore: " + (270..330 step 5).map { s -> (r.stateAt(s * 1_000L)?.banner as? Banner.Maneuver)?.let { "${s}s ${it.distanceM.toInt()} m «${it.street}»" } })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /**
     * TC-R17: a single 5 m-accuracy outlier that lands ON a later step (Dunjingarav st., 200 m past the left turn) must not
     * skip steps or reroute: G6b during continuous tracking, G6c as the first fix after a 12 s gap (AC 41 "one outlier
     * → 0 reroute requests", AC 31 banner, AC 34 the left turn still announced 20–250 m ahead, AC 52/53).
     */
    @Test
    fun tcR17_outlierOnALaterStepDoesNotSkipSteps() {
        val p = ArrayList<String>()
        for (id in listOf("G6b", "G6c")) {
            val fixes = QaGpx.fixes(id)
            val r = car(id)
            r.run(fixes, tailMs = 10_000)
            val oi = QaGpx.meta(id)["outlier_index"]!!.toString().toInt()
            val outlierAt = fixes[oi].elapsedMs
            if (r.requests.isNotEmpty()) p += "$id AC 41: ${r.requests.size} reroute requests (first at ${r.requests[0].first / 1000.0} s, outlier at ${outlierAt / 1000.0} s)"
            if (r.states.any { it.second.banner is Banner.Rerouting }) p += "$id AC 41/31: recalculating banner shown"
            if (r.spokenTexts().contains("Та маршрутаас гарлаа")) p += "$id AC 41: off-route prompt"
            val left = r.bannerText(Banner.Maneuver(r.initial.plan.steps[1].key, 0.0, "", null, false))
            for (dt in listOf(2_000L, 5_000L)) {
                val shown = r.stateAt(outlierAt + dt)?.let { r.bannerText(it.banner) }
                if (shown != left) p += "$id AC 31/53: banner ${dt / 1000} s after the outlier «$shown», expected «$left» (still before the left turn)"
            }
            p += ac34Problems(r, fixes.filterIndexed { i, _ -> i != oi }).map { "$id AC 34: $it" }
            // AC 34: a prompt starts only after the TRUE position passes its trigger. A "now" prompt (no stated distance)
            // fires at 58–80 m (fast) or less (navigation-ux §4.2), so a true distance > 100 m means it fired early.
            val oracle = RouteOracle(r.initial.plan)
            val tl = oracle.alongTimeline(fixes.filterIndexed { i, _ -> i != oi })
            for ((t, sp) in r.spoken) {
                val m = sp.maneuver ?: continue
                if (m.first != 0 || m.second < 1 || sp.cls != PromptClass.MANEUVER || Ac27.statedMetres(sp.text) != null || sp.text.contains("километр")) continue
                val d = oracle.maneuverAlong[m.second] - interpolate(tl, t)
                if (d > 100) p += "$id AC 34: «${sp.text}» (now prompt, manoeuvre ${m.second}) spoken at ${t / 1000.0} s while the manoeuvre is ${d.toInt()} m ahead"
            }
            p += bannerAdvanceProblems(r, fixes.filterIndexed { i, _ -> i != oi }).map { "$id AC 31: $it" }
            if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "$id: arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
            println("$id spoken: " + r.spoken.map { "${it.first / 1000}s ${it.second.text}" })
            println("$id banners: " + r.bannerChanges().map { "${it.first / 1000.0}s ${it.second}" })
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /**
     * TC-R18 (round 2, NAV-005-D9 re-verification near the turn): G6d puts the same single 5 m-accuracy outlier on the
     * next step while the car is ~120 m before the left turn, after the main (150 m) prompt and before the "now"
     * prompt. The held position must keep the "now" prompt at the true distance (AC 34), keep the banner on the left
     * turn (AC 31), and send 0 reroute requests (AC 41). Same oracles as TC-R17.
     */
    @Test
    fun tcR18_outlierOnALaterStepNearTheTurn() {
        val id = "G6d"
        val p = ArrayList<String>()
        val fixes = QaGpx.fixes(id)
        val r = car(id)
        r.run(fixes, tailMs = 10_000)
        val oi = QaGpx.meta(id)["outlier_index"]!!.toString().toInt()
        val outlierAt = fixes[oi].elapsedMs
        val trueFixes = fixes.filterIndexed { i, _ -> i != oi }
        if (r.requests.isNotEmpty()) p += "AC 41: ${r.requests.size} reroute requests (first at ${r.requests[0].first / 1000.0} s, outlier at ${outlierAt / 1000.0} s)"
        if (r.states.any { it.second.banner is Banner.Rerouting }) p += "AC 41/31: recalculating banner shown"
        if (r.spokenTexts().contains("Та маршрутаас гарлаа")) p += "AC 41: off-route prompt"
        val left = r.bannerText(Banner.Maneuver(r.initial.plan.steps[1].key, 0.0, "", null, false))
        val shown = r.stateAt(outlierAt + 1_000L)?.let { r.bannerText(it.banner) }
        if (shown != left) p += "AC 31: banner 1 s after the outlier «$shown», expected «$left» (still before the left turn)"
        p += ac34Problems(r, trueFixes).map { "AC 34: $it" }
        val oracle = RouteOracle(r.initial.plan)
        val tl = oracle.alongTimeline(trueFixes)
        for ((t, sp) in r.spoken) {
            val m = sp.maneuver ?: continue
            if (m.first != 0 || m.second < 1 || sp.cls != PromptClass.MANEUVER || Ac27.statedMetres(sp.text) != null || sp.text.contains("километр")) continue
            val d = oracle.maneuverAlong[m.second] - interpolate(tl, t)
            if (d > 100) p += "AC 34: «${sp.text}» (now prompt, manoeuvre ${m.second}) spoken at ${t / 1000.0} s while the manoeuvre is ${d.toInt()} m ahead"
        }
        p += bannerAdvanceProblems(r, trueFixes).map { "AC 31: $it" }
        // Banner distance right after the outlier must stay close to the true distance (held position, not the snapped turn).
        val b = r.stateAt(outlierAt + 200L)?.banner as? Banner.Maneuver
        val trueD = oracle.maneuverAlong[1] - interpolate(tl, outlierAt)
        if (b != null && kotlin.math.abs(b.distanceM - trueD) > maxOf(30.0, 0.2 * trueD)) p += "AC 21: banner distance ${b.distanceM.toInt()} m at the outlier, true ${trueD.toInt()} m"
        if (r.events.count { it.second == GuidanceEvent.Arrived } != 1) p += "arrivals ${r.events.count { it.second == GuidanceEvent.Arrived }}"
        println("$id outlier at ${outlierAt / 1000.0} s, spoken: " + r.spoken.map { "${it.first / 1000.0}s ${it.second.text}" })
        println("$id banners: " + r.bannerChanges().map { "${it.first / 1000.0}s ${it.second}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ round 3: arrival gating

    /**
     * Arrival problems shared by TC-R19 / TC-R20 (AC 55–57, ADR-0009 Amendment 1 / Amendment 3 §5): exactly one arrival;
     * it fires only after the TRUE position (oracle on [trueFixes]) is on the last leg (past the last manoeuvre before
     * `arrive`) and within 60 m of the route end; the arrival prompt is spoken once and nothing after it; no arrival text
     * is spoken or shown before that.
     */
    private fun arrivalGatingProblems(r: QaRun, trueFixes: List<Fix>, label: String): List<String> {
        val p = ArrayList<String>()
        val oracle = RouteOracle(r.initial.plan)
        val tl = oracle.alongTimeline(trueFixes)
        val lastManeuverAlong = oracle.maneuverAlong[r.initial.plan.steps.size - 2]
        val arrivals = r.events.filter { it.second == GuidanceEvent.Arrived }
        if (arrivals.size != 1) p += "$label AC 56: ${arrivals.size} arrival events at ${arrivals.map { it.first / 1000.0 }} s"
        val arrivedAt = arrivals.firstOrNull()?.first
        if (arrivedAt != null) {
            val along = interpolate(tl, arrivedAt)
            if (along < lastManeuverAlong) p += "$label AC 55/57 (Amendment 1 §5): arrival at ${arrivedAt / 1000.0} s while the true position is ${along.toInt()} m along, before the last manoeuvre at ${lastManeuverAlong.toInt()} m"
            if (oracle.length - along > 60) p += "$label AC 55: arrival at ${arrivedAt / 1000.0} s, ${(oracle.length - along).toInt()} m before the route end"
        }
        val arrivalTexts = r.spoken.filter { it.second.text.startsWith("Таны очих газар") || it.second.text.startsWith("Та очих газартаа") }
        if (arrivalTexts.size != 1) p += "$label AC 55: arrival spoken ${arrivalTexts.size} times ${arrivalTexts.map { "${it.first / 1000.0}s ${it.second.text}" }}"
        arrivalTexts.firstOrNull()?.let { (t, _) -> if (arrivedAt != null && t < arrivedAt) p += "$label AC 55: arrival prompt at ${t / 1000.0} s before the arrival event" }
        if (arrivedAt != null && r.spoken.any { it.first > arrivedAt && it.second.cls != PromptClass.ARRIVAL }) p += "$label AC 55: prompts after arrival"
        val earlyArrivalBanner = r.states.firstOrNull { (t, s) -> s.banner is Banner.Arrival && interpolate(tl, t) < lastManeuverAlong }
        if (earlyArrivalBanner != null) p += "$label AC 55: arrival banner at ${earlyArrivalBanner.first / 1000.0} s before the last manoeuvre"
        if (r.states.last().second.phase != GuidancePhase.ARRIVED) p += "$label: final phase ${r.states.last().second.phase}"
        if (r.requests.isNotEmpty()) p += "$label AC 41/55: ${r.requests.size} route requests"
        if (r.spokenTexts().contains("Та маршрутаас гарлаа")) p += "$label AC 41: off-route prompt"
        return p
    }

    /**
     * TC-R19 (round 3, ADR-0009 Amendment 3 §5 review probe): G6e = G1 with ONE 5 m-accuracy fix exactly on the route's
     * last coordinate after ~300 m of driving (step 0 of 4), and the probe's own variant 20 m north of it. Before the
     * fix guidance ended at the outlier (rule (c) without step gating). Expected: no arrival, banner still the left turn,
     * every manoeuvre announced, exactly one arrival at the true end (AC 41 one outlier, AC 31, AC 34, AC 55–57).
     */
    @Test
    fun tcR19_g6eOutlierOnTheRouteEndDoesNotArrive() {
        val p = ArrayList<String>()
        val base = QaGpx.fixes("G6e")
        val oi = QaGpx.meta("G6e")["outlier_index"]!!.toString().toInt()
        val g1 = QaGpx.fixes("G1")
        for ((label, fixes) in listOf(
            "G6e (outlier on the end coordinate)" to base,
            "G6e-20m (outlier 20 m from the end, review probe)" to base.mapIndexed { i, f ->
                if (i != oi) f else f.copy(lat = Geo.offset(f.latLon, 0.0, 20.0).lat, lon = Geo.offset(f.latLon, 0.0, 20.0).lon)
            },
        )) {
            val r = car("G6e")
            val end = r.initial.plan.end
            // fixture precondition: the outlier is within 30 m of the route end while the true position is on step 0
            val dEnd = Geo.distance(fixes[oi].latLon, end)
            assertTrue("$label: fixture outlier is ${dEnd.toInt()} m from the route end", dEnd <= 25.0)
            r.run(fixes, tailMs = 10_000)
            val outlierAt = fixes[oi].elapsedMs
            val trueFixes = fixes.mapIndexed { i, f -> if (i == oi) g1[i] else f }
            p += arrivalGatingProblems(r, trueFixes, label)
            val left = r.bannerText(Banner.Maneuver(r.initial.plan.steps[1].key, 0.0, "", null, false))
            for (dt in listOf(200L, 1_000L, 5_000L)) {
                val shown = r.stateAt(outlierAt + dt)?.let { r.bannerText(it.banner) }
                if (shown != left) p += "$label AC 31/55: banner ${dt / 1000.0} s after the outlier «$shown», expected «$left»"
            }
            if (r.stateAt(outlierAt + 1_000L)?.phase != GuidancePhase.NAVIGATING) p += "$label AC 55: phase 1 s after the outlier ${r.stateAt(outlierAt + 1_000L)?.phase}"
            p += ac34Problems(r, trueFixes).map { "$label AC 34: $it" }
            p += bannerAdvanceProblems(r, trueFixes).map { "$label AC 31: $it" }
            println("$label outlier at ${outlierAt / 1000.0} s, arrival ${r.events.filter { it.second == GuidanceEvent.Arrived }.map { it.first / 1000.0 }} s, spoken: " + r.spoken.map { "${it.first / 1000.0}s ${it.second.text}" })
        }
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /**
     * TC-R20 (round 3, ADR-0009 Amendment 1 §5 / Amendment 3 §5 fixture): G10, a recorded route on the divided Peace
     * Ave that passes 22.7 m from its own end on its first step (3 fixes within 30 m of the end at 29–31 s), U-turns at
     * ~759 m and arrives 391 m later. Expected: arrival exactly once, on the last leg, at the end; the U-turn and the
     * arrival announced (AC 34), banner advances (AC 31), 0 requests.
     */
    @Test
    fun tcR20_g10RoutePassingCloseToItsOwnEnd() {
        val fixes = QaGpx.fixes("G10")
        val r = QaRun(QaGpx.routeBytes("G10"), TravelMode.CAR, Lang.MN)
        val p = ArrayList<String>()
        val oracle = RouteOracle(r.initial.plan)
        val end = r.initial.plan.end
        val steps = r.initial.plan.steps
        // fixture precondition: >= 1 fix within 30 m of the end while the true position is before the last manoeuvre
        val tl = oracle.alongTimeline(fixes)
        val nearEarly = fixes.indices.filter { Geo.distance(fixes[it].latLon, end) <= 30.0 && tl[it].second < oracle.maneuverAlong[steps.size - 2] - 50 }
        assertTrue("fixture: no fix within 30 m of the end before the U-turn", nearEarly.isNotEmpty())
        assertTrue("fixture: the route has a manoeuvre between the near pass and arrive (${steps.size} steps)", steps.size >= 3)
        r.run(fixes, tailMs = 10_000)
        p += arrivalGatingProblems(r, fixes, "G10")
        // AC 55 at the TRUE end (stricter than the shared 60 m bound): the single arrival fires when the GPX position is
        // within 30 m of the route end along the route and within 30 m straight-line, after the U-turn.
        r.events.filter { it.second == GuidanceEvent.Arrived }.forEach { (t, _) ->
            val along = interpolate(tl, t)
            val fixAt = fixes.last { it.elapsedMs <= t }
            val straight = Geo.distance(fixAt.latLon, end)
            if (oracle.length - along > 30.0) p += "AC 55: arrival at ${t / 1000.0} s, true position ${(oracle.length - along).toInt()} m (along) before the route end"
            if (straight > 30.0) p += "AC 55: arrival at ${t / 1000.0} s, last fix ${straight.toInt()} m straight-line from the route end"
            if (along < oracle.maneuverAlong[steps.size - 2]) p += "Amendment 3 §5: arrival at ${t / 1000.0} s before the U-turn"
        }
        val passAt = fixes[nearEarly.last()].elapsedMs
        if (r.stateAt(passAt + 1_000L)?.phase != GuidancePhase.NAVIGATING) p += "AC 55: phase 1 s after the near pass ${r.stateAt(passAt + 1_000L)?.phase}"
        p += bannerProblems(r, Lang.MN).map { "AC 28/31: $it" }
        p += voiceProblems(r).map { "AC 33: $it" }
        p += ac34Problems(r, fixes).map { "AC 34: $it" }
        p += bannerAdvanceProblems(r, fixes).map { "AC 31: $it" }
        p += privacyProblems(r).map { "AC 67: $it" }
        println("G10 near-end fixes ${nearEarly.map { "${fixes[it].elapsedMs / 1000}s ${Geo.distance(fixes[it].latLon, end).toInt()} m" }}")
        println("G10 spoken: " + r.spoken.map { "${it.first / 1000.0}s ${it.second.text}" })
        println("G10 banners: " + r.bannerChanges().map { "${it.first / 1000.0}s ${it.second}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /**
     * TC-R21 (round 3, ADR-0009 Amendment 3 §5 rule (b) trust): G6f = G1 with ONE 5 m-accuracy fix 60 m beside the
     * route end while the car is on the last leg ~100 m before the end. The fix is > 50 m off the current step
     * (untrusted) and Ferrostar snaps it to the end (distanceRemaining ≈ 0), and it is 60 m from the end (rule (c) does
     * not apply). Expected: no arrival at the outlier; exactly one arrival when the true position is at the end.
     */
    @Test
    fun tcR21_g6fUntrustedFixDoesNotArriveByRemainingDistance() {
        val id = "G6f"
        val fixes = QaGpx.fixes(id)
        val g1 = QaGpx.fixes("G1")
        val oi = QaGpx.meta(id)["outlier_index"]!!.toString().toInt()
        val r = car(id)
        val end = r.initial.plan.end
        val oracle = RouteOracle(r.initial.plan)
        val trueFixes = fixes.mapIndexed { i, f -> if (i == oi) g1[i] else f }
        val tl = oracle.alongTimeline(trueFixes)
        val outlierAt = fixes[oi].elapsedMs
        // fixture preconditions: on the last leg, ~100 m before the end, outlier > 50 m off the route and > 30 m from the end
        val trueRemaining = oracle.length - tl[oi].second
        assertTrue("fixture: true remaining at the outlier ${trueRemaining.toInt()} m", trueRemaining in 70.0..115.0 && tl[oi].second > oracle.maneuverAlong[r.initial.plan.steps.size - 2])
        assertTrue("fixture: outlier ${Geo.distance(fixes[oi].latLon, end).toInt()} m from the end", Geo.distance(fixes[oi].latLon, end) > 50.0)
        r.run(fixes, tailMs = 10_000)
        val p = ArrayList<String>()
        p += arrivalGatingProblems(r, trueFixes, id)
        if (r.stateAt(outlierAt + 200L)?.phase != GuidancePhase.NAVIGATING) p += "AC 55 (Amendment 3 §5 rule (b)): phase 0.2 s after the untrusted fix ${r.stateAt(outlierAt + 200L)?.phase}"
        p += ac34Problems(r, trueFixes).map { "AC 34: $it" }
        p += bannerAdvanceProblems(r, trueFixes).map { "AC 31: $it" }
        println("$id outlier at ${outlierAt / 1000.0} s (true remaining ${trueRemaining.toInt()} m), arrival ${r.events.filter { it.second == GuidanceEvent.Arrived }.map { it.first / 1000.0 }} s, spoken: " + r.spoken.map { "${it.first / 1000.0}s ${it.second.text}" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ D4 English check

    /** English unit agreement problems in one voice text (navigation-ux §4.1: singular only when the number is "1"). */
    private fun englishUnitProblems(t: String): List<String> {
        val p = ArrayList<String>()
        if (Regex("(^|[^\\d.,])1 (kilometers|meters)\\b").containsMatchIn(t)) p += "plural after 1 «$t»"
        if (Regex("(\\d\\.\\d+|(^|\\D)([02-9]|\\d{2,})) (kilometer|meter)(?!s)\\b").containsMatchIn(t)) p += "singular after a number other than 1 «$t»"
        return p
    }

    /**
     * TC-R22 (NAV-005-D4 regression, AC 32 en, AC 40, navigation-ux §4.1): no English voice text says "1 kilometers" or
     * "1 meters", and "1.5 kilometers" stays plural. Sources: every `en` line of the golden set, English replays of G1, G8
     * (en routes), G5 (en walk route) and G9 (mn route, English voice: the "In 2 kilometers" / "Continue for 19
     * kilometers" path), and the production renderer at 1,040 m / 1,500 m, because no replay reaches a fractional
     * kilometre (early prompts fire at the 1 km / 2 km thresholds, "continue on" needs ≥ 2 km).
     */
    @Test
    fun tcR22_englishSingularPluralInEveryVoiceText() {
        val p = ArrayList<String>()
        val golden = repoFile("tests/gpx/nav005/golden/voice-golden.tsv").readLines().filter { !it.startsWith("#") && it.split('\t').getOrNull(1) == "en" }
        if (golden.isEmpty()) p += "golden set has no en lines"
        golden.forEach { line -> englishUnitProblems(line.split('\t')[4]).forEach { p += "golden: $it" } }
        val texts = golden.map { it.split('\t')[4] }
        for (expected in listOf("In 1 kilometer, turn slightly left", "In 1 kilometer, enter the roundabout and take the second exit")) {
            if (expected !in texts) p += "golden: G8 en has no «$expected»"
        }
        val replays = listOf(
            "G1" to QaRun(QaGpx.routeBytes("G1", "also_en"), TravelMode.CAR, Lang.EN),
            "G8" to QaRun(QaGpx.routeBytes("G8", "also_en"), TravelMode.CAR, Lang.EN),
            "G5" to QaRun(QaGpx.routeBytes("G5", "also_en"), TravelMode.WALK, Lang.EN),
            "G9" to QaRun(QaGpx.routeBytes("G9"), TravelMode.CAR, Lang.EN),
        )
        var kmTexts = 0
        for ((id, r) in replays) {
            r.run(QaGpx.fixes(id), tailMs = if (id == "G9") 0 else 10_000)
            if (r.spoken.isEmpty()) p += "$id en: nothing spoken"
            r.spokenTexts().forEach { t -> englishUnitProblems(t).forEach { p += "$id en: $it" } }
            r.spokenTexts().filter { Regex("[Ѐ-ӿ]").containsMatchIn(it) }.forEach { p += "$id en: Cyrillic in «$it»" }
            kmTexts += r.spokenTexts().count { it.contains("kilometer") }
            println("$id en spoken: " + r.spoken.map { "${it.first / 1000.0}s ${it.second.text}" })
        }
        if (kmTexts == 0) p += "no replay produced a kilometre prompt (scan would be vacuous)"
        if (replays.first { it.first == "G9" }.second.spokenTexts().none { it.startsWith("In 2 kilometers, ") }) p += "G9 en: no «In 2 kilometers, …» prompt"
        // Fractional kilometres through the production renderer (navigation-ux §4.1 examples; mn unchanged).
        val en = TestStrings.of(Lang.EN)
        val mn = TestStrings.of(Lang.MN)
        val slightLeft = ManeuverRules.key(ManeuverInput("turn", "slight left"))
        val roundabout2 = ManeuverRules.key(ManeuverInput("roundabout", "right", 2.0))
        val cases = listOf(
            VoiceText.render(VoiceContent.Maneuver(slightLeft, 1_500.0), Lang.EN, en) to "In 1.5 kilometers, turn slightly left",
            VoiceText.render(VoiceContent.Maneuver(roundabout2, 1_500.0), Lang.EN, en) to "In 1.5 kilometers, enter the roundabout and take the second exit",
            VoiceText.render(VoiceContent.ContinueOn(1_500.0), Lang.EN, en) to "Continue for 1.5 kilometers",
            VoiceText.render(VoiceContent.Maneuver(slightLeft, 1_040.0), Lang.EN, en) to "In 1 kilometer, turn slightly left",
            VoiceText.render(VoiceContent.Maneuver(slightLeft, 1_500.0), Lang.MN, mn) to "1,5 километрт бага зэрэг зүүн тийш эргэнэ үү",
        )
        for ((actual, expected) in cases) if (actual != expected) p += "renderer: «$actual», expected «$expected»"
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ D67 (PO 2026-10-01)

    /**
     * TC-R23 (AC 32 as changed by D67): no generated voice text contains «1000 метрт» / "1000 meters", over EVERY track
     * in the manifest (reroutes served as recorded, so the §4.3 route-active and GPS-restored catch-up prompts are
     * included), in Mongolian and in English. The golden set (TC-R13) covers G1, G5, G8, G9 only.
     */
    @Test
    fun tcR23_d67NoThousandMetresInAnyReplay() {
        val p = ArrayList<String>()
        val ids = QaGpx.manifest["tracks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        var texts = 0
        var g1Km = false
        for (id in ids) {
            val meta = QaGpx.meta(id)
            val walk = meta["mode"]!!.jsonPrimitive.content == "walk"
            for (lang in listOf(Lang.MN, Lang.EN)) {
                val key = if (lang == Lang.EN && meta.containsKey("also_en")) "also_en" else "route"
                val responses = QaGpx.reroutes(id).map { Resp(200, it, latencyMs = 300) }.toMutableList()
                val r = if (walk) QaRun(QaGpx.routeBytes(id, key), TravelMode.WALK, lang, responses = responses) else car(id, lang, key, responses)
                r.run(QaGpx.fixes(id), tailMs = if (id == "G9") 0 else 10_000)
                texts += r.spoken.size
                r.spokenTexts().filter { D67_BANNED.containsMatchIn(it) }.forEach { p += "$id $lang: «$it»" }
                if (id == "G1") g1Km = g1Km || r.spokenTexts().any { it.startsWith("1 километрт ") || it.startsWith("In 1 kilometer, ") }
            }
        }
        // The early prompt that triggered at 975–994 m on G1 (t = 176 s) must now be the kilometre form (not vacuous).
        if (!g1Km) p += "G1: no «1 километрт …» / \"In 1 kilometer, …\" prompt (the D67 case is no longer exercised)"
        if (texts == 0) p += "nothing spoken (scan would be vacuous)"
        println("TC-R23: ${ids.size} tracks × 2 languages, $texts prompts scanned")
        assertTrue("AC 32 (D67):\n" + p.joinToString("\n"), p.isEmpty())
    }

    @Suppress("unused")
    private fun dist(a: Fix, b: Fix) = Geo.distance(a.latLon, b.latLon)
}
