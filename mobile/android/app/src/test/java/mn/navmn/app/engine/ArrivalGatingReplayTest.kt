package mn.navmn.app.engine

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.Gpx
import mn.navmn.app.support.HostFerrostar
import mn.navmn.app.support.Replay
import mn.navmn.app.support.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-0009 Amendment 1 / Amendment 3 §5 arrival gating (NAV-005 review probe, AC 55–57) on the REAL Ferrostar session:
 * rule (c) (within 30 m of the route end) fires only while the upcoming manoeuvre is `arrive`, so one outlier at the
 * end coordinate, or a route that passes close to its own end, does not end guidance early.
 */
class ArrivalGatingReplayTest {
    @Before
    fun need() = HostFerrostar.require()

    private fun arrivals(r: Replay) = r.events.filter { it.second == GuidanceEvent.Arrived }

    /**
     * The review probe on QA's G1 GPX (P1 → P3, 4 steps): after ~300 m of driving one good fix lands 20 m from the
     * route end. Before the fix guidance ended there and «Таны очих газар баруун талд байна» was spoken.
     */
    @Test
    fun g1OneGoodFixTwentyMetresFromTheEndEarlyInTheTripDoesNotArrive() {
        val track = Gpx.parse(Fixtures.repoFile("tests/gpx/nav005/G1.gpx"))
        val r = Replay(Fixtures.route("p1-p3-car-mn.json"), TravelMode.CAR, Lang.MN, destination = LatLon(47.8858, 106.9173))
        val end = r.initial.plan.end
        // the first fix ≥ 300 m along the track
        var along = 0.0
        var k = 1
        while (k < track.size && along < 300.0) {
            along += Geo.distance(track[k - 1].latLon, track[k].latLon)
            k++
        }
        val outlierAt = track[k].elapsedMs
        val outlier = Tracks.fix(Geo.offset(end, 0.0, 20.0), outlierAt, track[k].bearingDeg, track[k].speedMps)
        // the outlier replaces one true fix (a GPS jump), the track then continues unchanged
        val fixes: List<Fix> = track.take(k) + outlier + track.drop(k + 1)
        r.run(fixes, tailMs = 10_000)

        val arrived = arrivals(r)
        assertEquals("exactly one arrival: ${arrived.map { it.first }}", 1, arrived.size)
        assertTrue("arrived at ${arrived[0].first} ms, outlier at $outlierAt ms", arrived[0].first > outlierAt + 60_000)
        // the arrival happens near the true end, after the last turn
        val atArrival = fixes.last { it.elapsedMs <= arrived[0].first }
        assertTrue("arrived ${Geo.distance(atArrival.latLon, end).toInt()} m from the end", Geo.distance(atArrival.latLon, end) <= 60.0)
        val spokenBefore = r.spoken.filter { it.first in outlierAt..(outlierAt + 5_000) }.map { it.second.text }
        assertTrue("arrival spoken at the outlier: $spokenBefore", spokenBefore.none { it.startsWith("Таны очих газар") || it.startsWith("Та очих газартаа") })
        assertEquals("guidance continued after the outlier", GuidancePhase.ARRIVED, r.last?.phase)
        assertTrue("banners after the outlier ${r.banners}", r.banners.any { it.first > outlierAt && it.second == "Баруун тийш эргэнэ үү" })
        assertEquals(0, r.requests.size)
    }

    /**
     * A loop round a block whose end lies 20 m beside its own first street: east 400 m, left (north) 300 m, left
     * (west) 400 m, left (south) 280 m → end. The first ~100 m pass within 30 m of the end; arrival must wait for the
     * last leg.
     */
    @Test
    fun loopRoutePassingCloseToItsOwnEndArrivesOnlyAtTheEnd() {
        val a = LatLon(47.9150, 106.9000)
        val b = Geo.offset(a, 90.0, 400.0)
        val c = Geo.offset(b, 0.0, 300.0)
        val d = Geo.offset(c, 270.0, 400.0)
        val e = Geo.offset(d, 180.0, 280.0) // 20 m north of a
        val json = SyntheticOsrm.route(
            listOf(
                SyntheticOsrm.Leg(listOf(a, b), "depart", null, "Эхний гудамж"),
                SyntheticOsrm.Leg(listOf(b, c), "turn", "left", "Хоёр дахь гудамж"),
                SyntheticOsrm.Leg(listOf(c, d), "turn", "left", "Гурав дахь гудамж"),
                SyntheticOsrm.Leg(listOf(d, e), "turn", "left", "Дөрөв дэх гудамж"),
            ),
        )
        val r = Replay(json, TravelMode.CAR, Lang.MN)
        assertTrue("end ${Geo.distanceToLine(r.initial.plan.end, listOf(a, b))} m from the first street", Geo.distanceToLine(r.initial.plan.end, listOf(a, b)) < 30.0)
        val fixes = Tracks.along(r.initial.plan.geometry, 10.0)
        r.run(fixes, tailMs = 10_000)

        val arrived = arrivals(r)
        assertEquals("exactly one arrival: ${arrived.map { it.first }}", 1, arrived.size)
        val at = fixes.last { it.elapsedMs <= arrived[0].first }
        // on the last street (d → e), within 30 m of the end
        assertTrue("arrived ${Geo.distanceToLine(at.latLon, listOf(d, e)).toInt()} m from the last street", Geo.distanceToLine(at.latLon, listOf(d, e)) < 10.0)
        assertTrue("arrived ${Geo.distance(at.latLon, e).toInt()} m from the end", Geo.distance(at.latLon, e) <= 31.0)
        // all three left turns were announced before the arrival
        val announced = r.spoken.filter { it.first < arrived[0].first }.mapNotNull { it.second.maneuver?.second }.toSet()
        assertTrue("announced manoeuvres $announced: ${r.spokenTexts()}", announced.containsAll(listOf(1, 2, 3)))
        assertEquals(0, r.requests.size)
    }
}

/** Builds a minimal Valhalla-style OSRM response (polyline6) for synthetic routes in replay tests. */
object SyntheticOsrm {
    data class Leg(val line: List<LatLon>, val type: String, val modifier: String?, val name: String)

    fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = Math.round(p.lat * 1e6)
            val lon = Math.round(p.lon * 1e6)
            put(lat - lastLat)
            put(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    private fun num(d: Double) = String.format(java.util.Locale.ROOT, "%.6f", d)
    private fun loc(p: LatLon) = "[${num(p.lon)},${num(p.lat)}]"

    fun route(legs: List<Leg>, speedMps: Double = 10.0): ByteArray {
        val all = ArrayList<LatLon>()
        for (l in legs) for (p in l.line) if (all.lastOrNull() != p) all += p
        val end = legs.last().line.last()
        val steps = ArrayList<String>()
        var prevBearing = 0.0
        for (l in legs) {
            val dist = Geo.length(l.line)
            val bearing = Geo.bearing(l.line[0], l.line[1])
            steps += step(l.line, l.type, l.modifier, l.name, dist, dist / speedMps, bearing, prevBearing)
            prevBearing = Geo.bearing(l.line[l.line.size - 2], l.line.last())
        }
        steps += step(listOf(end, end), "arrive", null, legs.last().name, 0.0, 0.0, 0.0, prevBearing)
        val total = legs.sumOf { Geo.length(it.line) }
        val json = """{"code":"Ok","waypoints":[{"distance":1.0,"name":"","location":${loc(all.first())}},{"distance":1.0,"name":"","location":${loc(end)}}],""" +
            """"routes":[{"weight_name":"auto","weight":${num(total / speedMps)},"duration":${num(total / speedMps)},"distance":${num(total)},"geometry":"${encode(all)}",""" +
            """"legs":[{"via_waypoints":[],"admins":[{}],"weight":${num(total / speedMps)},"duration":${num(total / speedMps)},"distance":${num(total)},"summary":"","steps":[${steps.joinToString(",")}]}]}]}"""
        return json.toByteArray()
    }

    private fun step(line: List<LatLon>, type: String, modifier: String?, name: String, dist: Double, dur: Double, bearingAfter: Double, bearingBefore: Double): String {
        val m = if (modifier != null) ""","modifier":"$modifier"""" else ""
        val start = line.first()
        return """{"geometry":"${encode(line)}","maneuver":{"type":"$type"$m,"bearing_after":${Math.round(bearingAfter)},"bearing_before":${Math.round(bearingBefore)},"location":${loc(start)}},""" +
            """"name":"$name","duration":${num(dur)},"distance":${num(dist)},"driving_side":"right","weight":${num(dur)},"mode":"driving",""" +
            """"intersections":[{"entry":[true],"bearings":[${Math.round(bearingAfter)}],"out":0,"location":${loc(start)},"geometry_index":0}],""" +
            """"bannerInstructions":[],"voiceInstructions":[]}"""
    }
}
