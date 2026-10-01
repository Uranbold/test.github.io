package mn.navmn.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import mn.navmn.app.support.distanceToLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GPX-style replays (story G1–G8 shapes, synthesised from the recorded routes) through the guidance core and the REAL
 * Ferrostar navigation session (host library), on a virtual clock. NAV-005 AC 27, 28, 30, 33, 41–53, 55–56, 67, 72.
 * QA's own GPX set (tests/gpx/nav005) can be fed through the same harness (support/Replay.kt, Gpx.parse).
 */
class GuidanceReplayTest {
    private val p3 = LatLon(47.8858, 106.9173)

    @Before
    fun need() = HostFerrostar.require()

    private fun car(fixture: String = "p1-p3-car-mn.json", lang: Lang = Lang.MN, responses: MutableList<Triple<Int, ByteArray, String?>> = ArrayList(), muted: Boolean = false) =
        Replay(Fixtures.route(fixture), TravelMode.CAR, lang, responses, destination = p3, muted = muted)

    private val latin = Regex("[A-Za-z]")
    private val cyrillic = Regex("[Ѐ-ӿ]")

    private fun assertBannerScans(banners: List<String>) {
        for (b in banners) {
            assertFalse("Latin in «$b»", latin.containsMatchIn(b))
            assertFalse("token in «$b»", Regex("[<>{}]").containsMatchIn(b))
            assertFalse("zero-width in «$b»", Regex("[\u200B\u200C\u200D\uFEFF]").containsMatchIn(b))
            for (avoid in listOf("навигаци", "зорьсон газар", "налуу зам", "Төвлөрүүлэх")) assertFalse(b.contains(avoid))
            assertFalse(Regex("км/ц(?!аг)").containsMatchIn(b))
            for (m in Regex("(зүүн|баруун)\\s+(\\S+)", RegexOption.IGNORE_CASE).findAll(b)) {
                assertTrue("bare ${m.value} in «$b»", m.groupValues[2] in setOf("тийш", "талаа", "талд", "талын", "талаас", "зүг", "хойд", "өмнө"))
            }
        }
    }

    private fun assertVoiceScans(texts: List<String>) {
        for (t in texts) {
            assertFalse("unit abbreviation in «$t»", Regex("\\d\\s?(м|км)(\\s|-|/|$)").containsMatchIn(t))
            assertFalse(Regex("\\d+-р").containsMatchIn(t))
            assertFalse("Latin in «$t»", latin.containsMatchIn(t.replace("GPS", "")))
            assertFalse(Regex("[<>{}]").containsMatchIn(t))
        }
    }

    @Test
    fun g1OnRouteBannersVoiceArrivalNoRequests() {
        val r = car()
        r.run(Tracks.along(r.initial.plan.geometry, 14.0), tailMs = 10_000)
        assertEquals(0, r.requests.size)
        assertEquals(1, r.events.count { it.second == GuidanceEvent.Arrived })
        val texts = r.banners.map { it.second }
        assertTrue(texts.toString(), texts.containsAll(listOf("Зүүн тийш эргэнэ үү", "Баруун тийш эргэнэ үү", "Таны очих газар баруун талд байна")))
        assertBannerScans(texts)
        val spoken = r.spokenTexts()
        assertTrue(spoken.toString(), spoken.first().endsWith("зүг рүү явна уу"))
        assertTrue(spoken.any { it.endsWith("метрт зүүн тийш эргэнэ үү") })
        assertTrue(spoken.any { it.endsWith("баруун тийш эргэнэ үү") })
        assertEquals("Таны очих газар баруун талд байна", spoken.last())
        assertEquals(1, spoken.count { it == "Таны очих газар баруун талд байна" })
        assertVoiceScans(spoken)
        assertTrue(r.log.none { Regex("-?\\d{1,3}\\.\\d{4,}").containsMatchIn(it) })
        assertEquals(GuidancePhase.ARRIVED, r.last!!.phase)
    }

    @Test
    fun g1EnglishBannersHaveNoCyrillicOutsideStreetNames() {
        val r = car("p1-p3-car-en.json", Lang.EN)
        r.run(Tracks.along(r.initial.plan.geometry, 14.0))
        val texts = r.banners.map { it.second }
        assertTrue(texts.containsAll(listOf("Turn left", "Turn right", "Your destination is on the right")))
        texts.forEach { assertFalse(it, cyrillic.containsMatchIn(it)) }
        r.spokenTexts().forEach { assertFalse(it, cyrillic.containsMatchIn(it)) }
        assertTrue(r.spokenTexts().any { it.startsWith("In ") && it.endsWith(", turn left") })
    }

    /** The recorded route with every Valhalla text field replaced by the sentinel (AC 27). */
    private fun sentinel(name: String): ByteArray {
        fun walk(el: JsonElement, key: String?): JsonElement = when (el) {
            is JsonObject -> JsonObject(el.mapValues { (k, v) -> walk(v, k) })
            is JsonArray -> JsonArray(el.map { walk(it, key) })
            is JsonPrimitive -> if (el.isString && key in setOf("instruction", "text", "announcement", "ssmlAnnouncement")) JsonPrimitive("VALHALLA_TEXT_SENTINEL") else el
        }
        return walk(Json.parseToJsonElement(Fixtures.route(name).decodeToString()), null).toString().encodeToByteArray()
    }

    @Test
    fun ac27SentinelNeverShownOrSpoken() {
        for (name in listOf("p1-p3-car-mn.json", "g8-roundabout-car-mn.json")) {
            val r = Replay(sentinel(name), TravelMode.CAR, Lang.MN)
            r.run(Tracks.along(r.initial.plan.geometry, 12.0))
            assertTrue(r.banners.none { it.second.contains("SENTINEL") })
            assertTrue(r.spokenTexts().none { it.contains("SENTINEL") })
            assertTrue(r.states.none { s -> s.trip.destinationName?.contains("SENTINEL") == true })
        }
    }

    @Test
    fun g8RoundaboutBannerAndVoice() {
        val r = car("g8-roundabout-car-mn.json")
        r.run(Tracks.along(r.initial.plan.geometry, 10.0))
        val texts = r.banners.map { it.second }
        assertTrue(texts.toString(), texts.contains("Тойрог: 2-р гарц"))
        assertTrue(r.spokenTexts().toString(), r.spokenTexts().any { it.contains("тойрогт ороод хоёрдугаар гарцаар гарна уу") || it.startsWith("Тойрогт ороод хоёрдугаар") })
        assertBannerScans(texts)
        assertVoiceScans(r.spokenTexts())
        assertEquals(0, r.requests.size)
    }

    private fun g2Track(r: Replay, speed: Double = 14.0): Pair<List<mn.navmn.app.location.Fix>, Long> {
        val geo = r.initial.plan.geometry
        val turnAt = r.initial.plan.steps[0].distance
        val first = Tracks.along(geo, speed, 0, 0.0, turnAt)
        val meta = Json.parseToJsonElement(Fixtures.route("g2-offroute-point.json").decodeToString()).jsonObject
        val turn = meta["turn"]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        val bearing = meta["bearing"]!!.jsonPrimitive.double
        // The recorded reroute response starts where the app sends the request (about 100 m past the turn); the new
        // route then continues along this wrong road (≥ 300 m on another road, story G2) before turning to P3.
        val origin = meta["rerouteOrigin"]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        val wrong = Tracks.straight(turn, bearing, Geo.distance(turn, origin), speed, first.last().elapsedMs + 1_000)
        val reroute = mn.navmn.app.route.OsrmPlanParser.plan(mn.navmn.app.route.OsrmPlanParser.parseJson(Fixtures.route("g2-reroute-car-mn.json"))!!, 1)!!
        val rest = Tracks.along(reroute.geometry, speed, wrong.last().elapsedMs + 1_000)
        val t50 = wrong.first { distanceToLine(it.latLon, geo.drop(1).let { g -> g.subList(g.indexOfFirst { p -> Geo.distance(p, turn) < 1.0 }.coerceAtLeast(0), g.size) }) > 50.0 }.elapsedMs
        return (first + wrong + rest) to t50
    }

    @Test
    fun g2WrongTurnDetectsWithinEightSecondsAndReroutesOnce() {
        val r = car(responses = mutableListOf(Triple(200, Fixtures.route("g2-reroute-car-mn.json"), null)))
        val (track, t50) = g2Track(r)
        r.run(track, tailMs = 10_000)
        val detected = r.banners.first { it.second.startsWith("Маршрутыг дахин тооцоолж байна") }.first
        assertTrue("detected ${detected - t50} ms after the first fix > 50 m away", detected - t50 in 0..8_000)
        assertEquals(1, r.requests.size)
        val (reqAt, req) = r.requests.single()
        assertTrue("first request ≤ 1 s after detection", reqAt - detected <= 1_000)
        assertEquals(TravelMode.CAR, req.mode)
        assertEquals(Lang.MN, req.lang)
        assertEquals(p3, req.destination)
        assertTrue("heading ${req.heading}", req.heading != null && Math.abs(req.heading!! - 165) <= 2)
        assertEquals(1, r.spokenTexts().count { it == "Та маршрутаас гарлаа" })
        // new route on screen ≤ 500 ms after the response (latency 300 ms)
        val back = r.banners.first { it.first > detected && !it.second.startsWith("Маршрутыг") }.first
        assertTrue(back - reqAt <= 300 + 500)
        assertEquals(1, r.last!!.generation)
        assertEquals(1, r.events.count { it.second == GuidanceEvent.Arrived })
        assertBannerScans(r.banners.map { it.second }.filterNot { it.startsWith("Маршрутыг") })
        assertVoiceScans(r.spokenTexts())
    }

    @Test
    fun g2With429WaitsRetryAfterThenOneAutomaticRequest() {
        val r = car(responses = mutableListOf(Triple(429, ByteArray(0), "3"), Triple(200, Fixtures.route("g2-reroute-car-mn.json"), null)))
        val (track, _) = g2Track(r, speed = 8.0)
        r.run(track, tailMs = 10_000)
        assertTrue(r.requests.size >= 2)
        val firstResponse = r.requests[0].first + 300
        assertTrue("second request ${r.requests[1].first - firstResponse} ms after the 429", r.requests[1].first - firstResponse >= 3_000)
        assertTrue(r.requests[1].first - r.requests[0].first >= 5_000)
        val during = r.states.filter { s -> s.banner is Banner.Rerouting }
        assertTrue(during.all { (it.banner as Banner.Rerouting).secondary == null || it.generation > 0 })
    }

    @Test
    fun g2OfflineSendsNothingThenOneRequestWithinThreeSecondsOfTheNetwork() {
        val r = car(responses = mutableListOf(Triple(200, Fixtures.route("g2-reroute-car-mn.json"), null)))
        val (track, t50) = g2Track(r, speed = 6.0)
        val offAt = (t50 - 20_000) / 500 * 500
        val onAt = (t50 + 20_000) / 500 * 500
        r.run(track, tailMs = 5_000, networkChanges = mapOf(offAt to false, onAt to true))
        assertTrue(r.requests.isNotEmpty())
        assertTrue("no request while offline", r.requests.first().first >= onAt)
        assertTrue("within 3 s after the network returned", r.requests.first().first - onAt <= 3_000)
        assertTrue(r.states.any { (it.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.OFFLINE })
    }

    @Test
    fun g2Always503BacksOffAndKeepsPacing() {
        val r = car(responses = MutableList(20) { Triple(503, ByteArray(0), null) })
        val (track, _) = g2Track(r, speed = 3.0)
        r.run(track.take(track.size), tailMs = 0)
        val starts = r.requests.map { it.first }
        assertTrue(starts.size >= 2)
        starts.zipWithNext { a, b -> assertTrue(b - a >= 5_000) }
        for (s in starts) assertTrue(starts.count { it >= s && it - s <= 60_000 } <= 6)
        assertTrue(r.states.any { (it.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.UNAVAILABLE })
    }

    @Test
    fun g3ThirtySecondGapGivesOneLostOneRestoredNoReroute() {
        val r = car()
        val all = Tracks.along(r.initial.plan.geometry, 400.0 / 30.0)
        val gapStart = 100
        val track = all.take(gapStart) + all.drop(gapStart + 30)
        r.run(track)
        val spoken = r.spokenTexts()
        assertEquals(1, spoken.count { it == "GPS дохио тасарлаа" })
        assertEquals(1, spoken.count { it == "GPS дохио сэргэлээ" })
        assertEquals(0, r.requests.size)
        val resumeAt = all[gapStart + 30].elapsedMs
        val lostStates = r.states.filter { it.gpsLost }
        assertTrue(lostStates.isNotEmpty())
        // progress frozen during the loss
        assertEquals(1, lostStates.map { it.progress.distanceRemaining }.toSet().size)
        assertTrue(r.states.any { it.gpsRestoredVisible })
        val bannerAfter = r.banners.lastOrNull { it.first <= resumeAt + 2_000 }!!.second
        assertEquals("Зүүн тийш эргэнэ үү", bannerAfter)
        assertEquals(1, r.events.count { it.second == GuidanceEvent.Arrived })
    }

    @Test
    fun g4StationaryBesideTheEndArrivesExactlyOnce() {
        val r = car()
        val geo = r.initial.plan.geometry
        val len = Geo.length(geo)
        val last = Tracks.along(geo, 10.0, 0, len - 500, len - 40)
        val beside = Geo.offset(geo.last(), 90.0, 10.0)
        val stay = (1..30).map { Tracks.fix(beside, last.last().elapsedMs + it * 1_000L, null, 0.0) }
        r.run(last + stay)
        assertEquals(1, r.events.count { it.second == GuidanceEvent.Arrived })
        assertEquals(1, r.spokenTexts().count { it.startsWith("Таны очих газар") || it.startsWith("Та очих газартаа") })
        assertEquals(0, r.requests.size)
    }

    @Test
    fun g5WalkingGuidance() {
        val r = Replay(Fixtures.route("p1-p2-walk-mn.json"), TravelMode.WALK, Lang.MN)
        r.run(Tracks.along(r.initial.plan.geometry, 1.4), tailMs = 10_000)
        assertEquals(1, r.events.count { it.second == GuidanceEvent.Arrived })
        assertEquals(0, r.requests.size)
        assertTrue(r.spokenTexts().any { it.startsWith("50 метрт") })
        assertVoiceScans(r.spokenTexts())
    }

    @Test
    fun g6SingleOutlierNoReroute() {
        val r = car()
        val track = Tracks.along(r.initial.plan.geometry, 14.0).toMutableList()
        val f = track[60]
        track[60] = f.copy(lat = Geo.offset(f.latLon, (f.bearingDeg ?: 0.0) + 90, 80.0).lat, lon = Geo.offset(f.latLon, (f.bearingDeg ?: 0.0) + 90, 80.0).lon)
        r.run(track)
        assertEquals(0, r.requests.size)
        assertTrue(r.banners.none { it.second.startsWith("Маршрутыг") })
    }

    @Test
    fun g7PoorAccuracyDriftNoReroute() {
        val r = car()
        val track = Tracks.along(r.initial.plan.geometry, 14.0).toMutableList()
        for (i in 0 until 20) {
            val f = track[60 + i]
            val off = Geo.offset(f.latLon, (f.bearingDeg ?: 0.0) + 90, 4.0 * (i + 1))
            track[60 + i] = f.copy(lat = off.lat, lon = off.lon, accuracyM = 60.0)
        }
        r.run(track)
        assertEquals(0, r.requests.size)
        assertTrue(r.banners.none { it.second.startsWith("Маршрутыг") })
    }

    @Test
    fun mutedProducesNoUtterancesAndLanguageSwitchChangesTheNextPrompt() {
        val muted = car(muted = true)
        muted.run(Tracks.along(muted.initial.plan.geometry, 14.0))
        assertEquals(0, muted.spoken.size)

        val r = car()
        val track = Tracks.along(r.initial.plan.geometry, 14.0)
        r.run(track.take(150), tailMs = 0)
        r.core.setLanguage(Lang.EN)
        var t = r.clock.now
        for (f in track.drop(150)) {
            r.clock.now = f.elapsedMs
            r.core.onFix(f)
            r.core.onTick()
            t = f.elapsedMs
        }
        val after = r.spoken.filter { it.first > track[150].elapsedMs }.map { it.second }
        assertTrue(after.isNotEmpty())
        assertTrue(after.all { it.lang == Lang.EN && !cyrillic.containsMatchIn(it.text) })
        assertEquals(0, r.requests.size)
        assertTrue(t > 0)
    }
}
