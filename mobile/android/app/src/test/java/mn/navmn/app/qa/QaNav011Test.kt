package mn.navmn.app.qa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.typinglock.TypingLockRule
import mn.navmn.app.voiceplan.PromptClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/*
 * NAV-011 QA tests (owner: qa-engineer). Test plan docs/qa/test-plans/NAV-011.md.
 * Oracles come from the story / navigation-ux numbers and the GPX + recorded route only, never from app output.
 *  - TC-K01…K05: typing-lock rule boundaries the mobile suite does not pin (AC 30, 32, 35).
 *  - TC-B01/B02: «Дугуй» guidance replays on the QA stand-in tracks tests/gpx/nav011 (AC 25, 26).
 */
class QaNav011Test {

    // ------------------------------------------------------------------------------------------ typing lock (AC 30–35)

    private val origin = LatLon(47.9189, 106.9176)

    /** Fixes 1 apart in time by [dtMs], moving at [mps] (reported unless [noSpeed]), accuracy [acc]. */
    private fun fixes(n: Int, mps: Double, dtMs: Long = 1_000, t0: Long = 10_000, acc: Double = 5.0, noSpeed: Boolean = false, from: LatLon = origin): List<Fix> {
        var p = from
        return (0 until n).map { i ->
            if (i > 0) p = Geo.offset(p, 90.0, mps * dtMs / 1000.0)
            Fix(p.lat, p.lon, acc, 90.0, 10.0, if (noSpeed) null else mps, t0 + i * dtMs, 0)
        }
    }

    private fun TypingLockRule.feed(f: List<Fix>): List<Boolean> = f.map { onFix(it, it.elapsedMs) }

    /** TC-K01, AC 30: the 15 km/h threshold is inclusive (4.2 m/s engages on fix 3), just below never engages. */
    @Test
    fun tcK01_engageThresholdIsInclusiveAt4_2mps() {
        assertEquals(listOf(false, false, true), TypingLockRule().feed(fixes(3, 4.2)))
        assertFalse(TypingLockRule().feed(fixes(120, 4.19)).any { it })
    }

    /**
     * TC-K02, AC 30: three fast fixes must span ≥ 2 s; three at 0.5 s apart (span 1 s) do not engage on fix 3. Exactly 2 s
     * (1 Hz) engages on fix 3. (Observation for the plan, not asserted: the rule looks only at the LAST 3 fixes, so at a
     * delivery rate above 1 Hz it never engages; see test plan §7 O2.)
     */
    @Test
    fun tcK02_threeFastFixesSpanningLessThan2sDoNotEngage() {
        assertEquals(listOf(false, false, false), TypingLockRule().feed(fixes(3, 6.0, dtMs = 500)))
        assertEquals(listOf(false, false, true), TypingLockRule().feed(fixes(3, 6.0, dtMs = 1_000)))
    }

    /**
     * TC-K03, AC 30 / Terms "good fix": accuracy exactly 25 m counts; > 25 m is ignored and never engages, and a poor fix
     * between fast good fixes neither counts nor breaks the run of consecutive good fixes.
     */
    @Test
    fun tcK03_accuracyGateAndPoorFixInsideARun() {
        assertEquals(listOf(false, false, true), TypingLockRule().feed(fixes(3, 6.0, acc = 25.0)))
        assertFalse(TypingLockRule().feed(fixes(60, 6.0, acc = 25.1)).any { it })
        val f = fixes(4, 6.0).toMutableList()
        f[2] = f[2].copy(accuracyM = 60.0)
        assertEquals("poor fix 3 ignored; engaged on the 3rd good fix", listOf(false, false, false, true), TypingLockRule().feed(f))
    }

    /**
     * TC-K04, AC 32: release needs every good fix of the last 10 s below 5 km/h AND ≥ 5 such fixes. Slow fixes every 3 s
     * (≤ 4 in any 10 s window) never release; a speed of 14 km/h (between 5 and 15) never releases (hysteresis).
     */
    @Test
    fun tcK04_releaseNeedsFiveSlowFixesInTenSecondsAndHysteresisHolds() {
        val r = TypingLockRule()
        val fast = fixes(4, 6.0)
        assertTrue(r.feed(fast).last())
        val sparse = fixes(20, 0.5, dtMs = 3_000, t0 = fast.last().elapsedMs + 3_000, from = fast.last().latLon)
        assertTrue("4 slow fixes per 10 s never release", r.feed(sparse).all { it })

        val h = TypingLockRule()
        val fast2 = fixes(4, 6.0)
        h.feed(fast2)
        val mid = fixes(120, 14.0 / 3.6, t0 = fast2.last().elapsedMs + 1_000, from = fast2.last().latLon)
        assertTrue("14 km/h keeps the lock engaged", h.feed(mid).all { it })
        // And it releases within 1 s once 10 s of ≥ 5 slow fixes exist (1 Hz, 1 m/s).
        val slowStart = mid.last().elapsedMs + 1_000
        val slow = fixes(12, 1.0, t0 = slowStart, from = mid.last().latLon)
        val states = h.feed(slow)
        val releasedAt = slow[states.indexOfFirst { !it }].elapsedMs
        assertTrue("released ${releasedAt - slowStart} ms after the first slow fix (window 10 s)", releasedAt - slowStart in 9_000..10_000)
    }

    /** TC-K05, AC 35 / AC 36 (ix): the derived speed needs a previous fix, so the lock is engaged after the 4th fix (3 derived speeds), not earlier. */
    @Test
    fun tcK05_derivedSpeedNeedsAPreviousFix() {
        val states = TypingLockRule().feed(fixes(4, 50.0, noSpeed = true))
        // Matches AC 36 (ix) as reworded: "engaged after the 4th fix (3 derived speeds)"; fixes 1-3 stay unlocked.
        assertEquals(listOf(false, false, false, true), states)
        // Re-engaging after a release starts the run again: no carry-over of the old history.
        val r = TypingLockRule()
        r.feed(fixes(4, 6.0))
        r.onUnavailable()
        assertEquals(listOf(false, false, true), r.feed(fixes(3, 6.0, t0 = 100_000)))
    }

    // ------------------------------------------------------------------------------------------ «Дугуй» replays (AC 25–26)

    private object Nav011Gpx {
        private val TRKPT = Regex("<trkpt\\s+lat=\"([-0-9.]+)\"\\s+lon=\"([-0-9.]+)\"\\s*>([\\s\\S]*?)</trkpt>")
        private fun tag(body: String, name: String): String? = Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)
        val manifest: JsonObject by lazy { Json.parseToJsonElement(repoFile("tests/gpx/nav011/manifest.json").readText()).jsonObject }
        fun meta(id: String): JsonObject = manifest["tracks"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }
        fun route(id: String): ByteArray = repoFile(meta(id)["route"]!!.jsonPrimitive.content).readBytes()
        fun fixes(id: String): List<Fix> {
            val text = repoFile(meta(id)["file"]!!.jsonPrimitive.content).readText()
            var t0: Long? = null
            return TRKPT.findAll(text).map { m ->
                val body = m.groupValues[3]
                val wall = Instant.parse(tag(body, "time")!!).toEpochMilli()
                val base = t0 ?: wall.also { t0 = it }
                val course = tag(body, "nav:course")?.toDouble()
                Fix(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), tag(body, "nav:acc")!!.toDouble(), course,
                    if (course != null) 10.0 else null, tag(body, "nav:speed")?.toDouble(), wall - base, wall)
            }.toList()
        }
    }

    /**
     * TC-B01, AC 26 with navigation-ux §4.2 rule 8 (the «Дугуй» column): on G11s (4.5 m/s, jitter) every manoeuvre except
     * `depart` (and a D68-exempt `arrive`) gets a prompt that starts while it is 15–150 m ahead (a chained «…, дараа нь …»
     * prompt covers the second manoeuvre); no manoeuvre prompt starts more than 150 m (+ one fix step) ahead; stated
     * distances within max(30 m, 20 %); start ≤ 1 s after the trigger (≤ 3 s when the channel is busy); no prompt for a
     * manoeuvre already passed; the same text never twice for one manoeuvre; 0 route requests; one arrival.
     */
    @Test
    fun tcB01_bikePromptBandOnG11s() {
        HostFerrostar.require()
        val fixes = Nav011Gpx.fixes("G11s")
        val r = QaRun(Nav011Gpx.route("G11s"), TravelMode.BICYCLE)
        r.run(fixes, tailMs = 10_000)
        val oracle = RouteOracle(r.initial.plan)
        val timeline = oracle.alongTimeline(fixes)
        val steps = r.initial.plan.steps
        val v = Nav011Gpx.meta("G11s")["speed_mps"]!!.jsonPrimitive.content.toDouble()
        val p = ArrayList<String>()
        val covered = HashSet<Int>()
        val seen = HashSet<Pair<Int, String>>()
        for ((t, sp) in r.spoken) {
            val man = sp.maneuver ?: continue
            val m = man.second
            val busy = r.spoken.any { (s0, o) -> o.id != sp.id && s0 <= sp.triggerAtMs && sp.triggerAtMs < s0 + minOf(5_000L, 300L + o.text.length * 55L) }
            if (t - sp.triggerAtMs > (if (busy) 3_000 else 1_000)) p += "«${sp.text}» started ${t - sp.triggerAtMs} ms after its trigger"
            if (!seen.add(m to sp.text)) p += "«${sp.text}» spoken twice for manoeuvre $m"
            if (sp.cls == PromptClass.ARRIVAL || m == 0) continue
            val along = interpolate(timeline, t)
            val d = oracle.maneuverAlong[m] - along
            if (d < -5) p += "«${sp.text}» for manoeuvre $m spoken ${(-d).toInt()} m after passing it"
            if (d > 150.0 + v + 5) p += "«${sp.text}» for manoeuvre $m started ${d.toInt()} m ahead (bike main ≤ 150 m, no early prompt)"
            Ac27.statedMetres(sp.text)?.let { s -> if (Math.abs(s - d) > maxOf(30.0, 0.2 * d)) p += "«${sp.text}» states ${s.toInt()} m, true ${d.toInt()} m" }
            if (d in 15.0..150.0) covered += m
            if (sp.text.contains(", дараа нь ") && m + 1 < steps.size) {
                val d2 = oracle.maneuverAlong[m + 1] - along
                if (d2 in 15.0..150.0 + 60.0) covered += m + 1
            }
        }
        for (m in 1 until steps.size) {
            val exempt = m == steps.lastIndex && steps[m].maneuver.type == "arrive" && oracle.maneuverAlong[m] - oracle.maneuverAlong[m - 1] < 30.0
            if (!exempt && m !in covered) p += "manoeuvre $m (${steps[m].maneuver.type}) had no prompt while 15–150 m ahead"
        }
        if (r.requests.isNotEmpty()) p += "${r.requests.size} route requests on the route"
        if (r.spoken.count { it.second.cls == PromptClass.ARRIVAL } != 1) p += "arrival prompts ${r.spoken.count { it.second.cls == PromptClass.ARRIVAL }}"
        println("G11s spoken: " + r.spoken.map { (t, sp) -> "${t / 1000}s ${"%.0f".format(sp.maneuver?.let { oracle.maneuverAlong[it.second] - interpolate(timeline, t) } ?: 0.0)}m «${sp.text}»" })
        assertTrue(p.joinToString("\n"), p.isEmpty())
    }

    /**
     * TC-B02, AC 25 (+ AC 14 / 19 reroute rule): on G11sb the rider leaves the route; the engine's reroute request (body as
     * sent) carries `costing: "bicycle"`, `alternates: 0` and no `costing_options`, and at most 1 is in flight.
     */
    @Test
    fun tcB02_bikeRerouteBodyOnG11sb() {
        HostFerrostar.require()
        val fixes = Nav011Gpx.fixes("G11sb")
        val r = QaRun(Nav011Gpx.route("G11sb"), TravelMode.BICYCLE)
        r.run(fixes, tailMs = 5_000)
        assertTrue("a reroute request after leaving the route; log: ${r.log.takeLast(10)}", r.bodies.isNotEmpty())
        for (b in r.bodies) {
            val j = Json.parseToJsonElement(b).jsonObject
            assertEquals(b, "bicycle", j["costing"]!!.jsonPrimitive.content)
            assertEquals(b, 0, j["alternates"]!!.jsonPrimitive.content.toInt())
            assertFalse(b, j.containsKey("costing_options"))
        }
        assertTrue("in flight max ${r.inFlightMax}", r.inFlightMax <= 1)
    }
}
