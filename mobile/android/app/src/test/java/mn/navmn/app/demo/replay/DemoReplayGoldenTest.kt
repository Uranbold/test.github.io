package mn.navmn.app.demo.replay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.geo.Geo
import mn.navmn.app.i18n.Lang
import mn.navmn.app.qa.QaGpx
import mn.navmn.app.qa.QaRun
import mn.navmn.app.qa.repoFile
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.RouteClient
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.support.HostFerrostar
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * NAV-019 AC 14, 22, 24, 25, 32 (ADR-0016 §4.2, §10, §11, §13) with the REAL Ferrostar core on the host JVM: the demo
 * routes R1–R3 replayed through the demo path ([DemoHarness]: picker entry → recorded route → [ReplayLocationSource] on
 * the [ReplayClock] → unchanged [mn.navmn.app.engine.GuidanceCore]) give exactly the NAV-005 voice golden and banner
 * sequence; one arrival each; pause produces nothing and repeats nothing; an off-route deviation sends 0 requests.
 */
class DemoReplayGoldenTest {
    @Before
    fun need() = HostFerrostar.require()

    private val entries: List<DemoEntry> by lazy { DemoCatalogue.load(RepoAssets::read) }
    private val manifest by lazy { DemoCatalogue.parseManifest(RepoAssets.read(DemoCatalogue.MANIFEST_ASSET)) }

    private fun trackId(path: String) = path.substringAfterLast('/').removeSuffix(".gpx")

    private data class GoldenRow(val t: Long, val maneuver: String, val text: String)

    private fun golden(track: String, lang: Lang): List<GoldenRow> =
        repoFile("tests/gpx/nav005/golden/voice-golden.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it[0] == track && it[1] == lang.name.lowercase() }
            .map { GoldenRow(it[2].toLong(), it[3], it[4]) }

    private fun bannerText(ref: QaRun): (Banner) -> String = { b -> ref.bannerText(b) }

    @Test
    fun pickerRoutesThroughTheDemoPathEqualTheVoiceGoldenAndTheBannerSequence() {
        val problems = ArrayList<String>()
        val findings = ArrayList<String>()
        for (entry in entries) {
            val id = trackId(entry.trackAsset)
            val routeBytes = RepoAssets.read(entry.routeAsset)
            val track = ReplayTrack.parse(RepoAssets.read(entry.trackAsset))
            for (lang in listOf(Lang.MN, Lang.EN)) runTest {
                val h = DemoHarness(this, routeBytes, entry.mode, lang)
                h.start(track)
                advanceTimeBy(track.durationMs + 15_000)
                runCurrent()
                val label = "${entry.id}/$id/${lang.name.lowercase()}"
                // The NAV-005 replay test on the same recorded route and track (reference for the banner and for tracks
                // without golden rows).
                val ref = QaRun(routeBytes, entry.mode, lang).also { it.run(QaGpx.fixes(id), tailMs = 10_000) }
                val spoken = h.spoken.map { (t, p) -> GoldenRow(t / 1000, p.maneuver?.second?.toString() ?: "-", p.text) }
                val replayed = ref.spoken.map { (t, p) -> GoldenRow(t / 1000, p.maneuver?.second?.toString() ?: "-", p.text) }
                // The golden rows were generated from QA's manifest route for that language ("route" for mn, "also_en" for
                // en). The demo replays the manifest route (recorded in mn) in both UI languages, so the golden is the
                // oracle only where it was made from the same bytes; otherwise the oracle is the NAV-005 replay of the
                // same inputs, and a difference to the golden is reported as a data finding (route recorded per language).
                val goldenRoute = QaGpx.meta(id)[if (lang == Lang.MN) "route" else "also_en"]?.jsonPrimitive?.content
                val demoRoute = entry.routeAsset.removePrefix("demo/files/")
                val rows = golden(id, lang)
                val expected = if (rows.isNotEmpty() && goldenRoute == demoRoute) rows else replayed
                fun same(a: List<GoldenRow>, b: List<GoldenRow>) = a.map { it.maneuver to it.text } == b.map { it.maneuver to it.text }
                if (!same(spoken, expected)) {
                    problems += "$label AC 14: voice\n  demo     ${spoken.map { "${it.t}s ${it.maneuver} ${it.text}" }}\n  expected ${expected.map { "${it.t}s ${it.maneuver} ${it.text}" }}"
                } else {
                    spoken.zip(expected).filter { (a, b) -> abs(a.t - b.t) > 2 }.forEach { (a, b) -> problems += "$label AC 14: «${a.text}» at ${a.t} s, expected ${b.t} s" }
                }
                if (rows.isNotEmpty() && expected !== rows && !same(spoken, rows)) {
                    val diff = spoken.indices.firstOrNull { it >= rows.size || spoken[it].text != rows[it].text } ?: rows.size
                    findings += "$label: golden rows come from $goldenRoute, the demo replays $demoRoute; from prompt $diff: " +
                        "demo ${spoken.drop(diff).map { it.text }} vs golden ${rows.drop(diff).map { it.text }}"
                }
                val banners = h.bannerChanges(bannerText(ref))
                val refBanners = ref.bannerChanges().map { it.second }
                if (banners != refBanners) problems += "$label AC 14: banner sequence\n  demo $banners\n  ref  $refBanners"
                if (h.arrivals != 1) problems += "$label AC 24: ${h.arrivals} arrivals"
                if (h.trackEnded != 0) problems += "$label AC 25: track end called although arrival was detected"
                if (NoRequests.count != 0) problems += "$label AC 12: ${NoRequests.count} route requests"
                if (h.states.any { it.second.gpsLost }) problems += "$label: GPS lost during a replay"
                if (h.wakeLock.held) problems += "$label ADR-0016 §4.5: wake lock still held after arrival"
                println("$label: ${spoken.size} prompts, ${banners.size} banners, arrival ${h.events.firstOrNull()?.first?.div(1000.0)} s")
            }
        }
        findings.forEach { println("FINDING (route data, not the demo path): $it") }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** AC 24: the test-only arrival track G4 (manifest `picker: false`) also arrives exactly once through the demo path. */
    @Test
    fun g4ArrivesExactlyOnce() = runTest {
        val g4 = manifest.routes.first { it.id == "g4" }
        val h = DemoHarness(this, repoFile(g4.route).readBytes(), TravelMode.CAR, Lang.MN)
        val track = ReplayTrack.parse(repoFile(g4.track).readBytes())
        h.start(track)
        advanceTimeBy(track.durationMs + 15_000)
        runCurrent()
        assertEquals(1, h.arrivals)
        assertEquals(1, h.spoken.count { it.second.cls == mn.navmn.app.voiceplan.PromptClass.ARRIVAL })
    }

    /**
     * AC 22 (ADR-0016 §11): «Түр зогсоох» for 60 s right after a prompt started: the utterance stops, 0 prompts, 0 GPS-lost,
     * remaining distance and «Хүрэх цаг» frozen; «Үргэлжлүүлэх»: the same prompts as an unpaused replay (0 repeats, no
     * depart prompt again), arrival once.
     */
    @Test
    fun pauseProducesNothingAndResumeRepeatsNothing() {
        val r1 = entries.first { it.id == "r1" }
        val routeBytes = RepoAssets.read(r1.routeAsset)
        val track = ReplayTrack.parse(RepoAssets.read(r1.trackAsset))
        var unpaused: List<String> = emptyList()
        runTest {
            val h = DemoHarness(this, routeBytes, r1.mode, Lang.MN)
            h.start(track)
            advanceTimeBy(track.durationMs + 15_000)
            runCurrent()
            unpaused = h.spoken.map { it.second.text }
        }
        runTest {
            val h = DemoHarness(this, routeBytes, r1.mode, Lang.MN)
            h.start(track)
            // Pause 100 ms after the second prompt starts (an utterance is playing).
            advanceTimeBy(1)
            while (h.spoken.size < 2) advanceTimeBy(100)
            advanceTimeBy(100)
            runCurrent()
            val stopsBefore = h.voice.stops
            val spokenBefore = h.spoken.size
            h.pause()
            runCurrent()
            assertTrue("AC 22: the utterance stopped", h.voice.stops > stopsBefore)
            val frozen = h.states.last().second.progress
            val statesBefore = h.states.size
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("AC 22: 0 prompts while paused", spokenBefore, h.spoken.size)
            val during = h.states.drop(statesBefore).map { it.second }
            assertTrue("AC 22: no GPS-lost message while paused", during.none { it.gpsLost })
            assertTrue("AC 22: remaining distance and «Хүрэх цаг» frozen", during.all { it.progress == frozen })
            h.resume()
            advanceTimeBy(track.durationMs + 15_000)
            runCurrent()
            val texts = h.spoken.map { it.second.text }
            // The prompt that was cut by the pause is not repeated: everything else is the unpaused sequence.
            val cut = texts[spokenBefore - 1]
            assertEquals("AC 22: 0 repeated prompts, no second depart prompt", unpaused, texts)
            assertTrue("depart once", texts.count { it == unpaused.first() } == 1 || cut == unpaused.first())
            assertEquals(1, h.arrivals)
            assertTrue(h.states.none { it.second.gpsLost })
        }
    }

    /**
     * AC 32 (ADR-0016 §10): the off-route track G2 (not in the picker) through the demo configuration: the real
     * [RouteClient] behind [DemoNetworkBlock] against a MockWebServer. The deviation is detected, the banner shows
     * «Маршрутыг дахин тооцоолж байна» with «Маршрутын үйлчилгээ түр ажиллахгүй байна», the replay keeps following the
     * track, and 0 requests reach the server.
     */
    @Test
    fun offRouteDuringAReplaySendsNoRequestAndShowsRerouteUnavailable() {
        val server = MockWebServer()
        server.start()
        try {
            val block = DemoNetworkBlock()
            val client = RouteClient(server.url("/").toString(), RouteClient.httpClient(OkHttpClient.Builder().addInterceptor(block).build()), { true }, RouteProcessor(FerrostarRouteParser()))
            val attempts = ArrayList<RouteRequest>()
            // Synchronous on the test thread (the harness posts the result like GuidanceEngine does).
            val requester = object : RouteRequester {
                override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
                    attempts += request
                    onResult(runBlocking(Dispatchers.IO) { client.fetch(request, generation) })
                    return Cancelable { }
                }
            }
            val g2 = QaGpx.meta("G2")
            val routeBytes = repoFile(g2["route"]!!.jsonPrimitive.content).readBytes()
            val track = ReplayTrack.parse(repoFile("tests/gpx/nav005/G2.gpx").readBytes())
            runTest {
                val h = DemoHarness(this, routeBytes, TravelMode.CAR, Lang.MN, requester)
                h.start(track)
                advanceTimeBy(track.durationMs + 15_000)
                runCurrent()
                val rerouting = h.states.filter { it.second.banner is Banner.Rerouting }
                assertTrue("AC 32: deviation detected", rerouting.isNotEmpty())
                assertTrue("AC 32: reroute attempts were made and blocked", attempts.isNotEmpty() && block.blocked == attempts.size)
                assertTrue(
                    "AC 32: «Маршрутын үйлчилгээ түр ажиллахгүй байна» under the reroute banner",
                    rerouting.any { (it.second.banner as Banner.Rerouting).secondary == RerouteSecondary.UNAVAILABLE },
                )
                val detectedAt = rerouting.first().first
                assertTrue("AC 32: the replay continues along the track", h.fixes.any { it.first > detectedAt + 30_000 })
                // If the track comes back within 50 m of the route, normal guidance resumes within 2 s.
                val geometry = h.route.plan.geometry
                val back = h.fixes.firstOrNull { it.first > detectedAt + 5_000 && Geo.distanceToLine(it.second.latLon, geometry) <= 50.0 }
                if (back != null) {
                    val resumed = h.states.firstOrNull { it.first >= back.first && it.second.banner !is Banner.Rerouting }
                    assertTrue("AC 32: guidance resumed ${resumed?.first?.minus(back.first)} ms after the track came back", resumed != null && resumed.first - back.first <= 2_000)
                }
                println("G2 demo: detected at ${detectedAt / 1000.0} s, ${attempts.size} blocked attempts, back on route at ${back?.first?.div(1000.0)} s")
            }
            assertEquals("AC 32/33: 0 requests reached the server", 0, server.requestCount)
        } finally {
            server.close()
        }
    }

    /** AC 9: selecting an entry parses the recorded route through the normal preview path (Ferrostar parser). */
    @Test
    fun openingAnEntryUsesTheNormalPreviewParse() {
        val processor = RouteProcessor(FerrostarRouteParser())
        for (e in entries) {
            val opened = DemoCatalogue.open(e, RepoAssets::read) { PreviewRoutes.process(processor, it, 0) }.getOrThrow()
            assertTrue(opened.outcome.routes.isNotEmpty())
            assertEquals(e.id, opened.entry.id)
            if (e.id == "r3") assertTrue("R3 start has no OSM feature → «Сонгосон цэг»", opened.origin is RoutePoint.MapPoint)
            else assertTrue(opened.origin is RoutePoint.Place)
            assertTrue(opened.destination is RoutePoint.Place)
        }
    }
}
