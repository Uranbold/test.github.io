package mn.navmn.app.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from a to b, 0–360°. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val φ1 = Math.toRadians(a.lat)
        val φ2 = Math.toRadians(b.lat)
        val dλ = Math.toRadians(b.lon - a.lon)
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point at [distanceM] along a polyline (clamped to its ends). */
    fun along(line: List<LatLon>, distanceM: Double): LatLon {
        if (line.isEmpty()) throw IllegalArgumentException("empty line")
        if (distanceM <= 0) return line.first()
        var rest = distanceM
        for (i in 0 until line.size - 1) {
            val seg = distance(line[i], line[i + 1])
            if (rest <= seg && seg > 0) {
                val f = rest / seg
                return LatLon(line[i].lat + (line[i + 1].lat - line[i].lat) * f, line[i].lon + (line[i + 1].lon - line[i].lon) * f)
            }
            rest -= seg
        }
        return line.last()
    }

    fun length(line: List<LatLon>): Double = (0 until line.size - 1).sumOf { distance(line[it], line[it + 1]) }

    /** Point [distanceM] from [from] in direction [bearingDeg] (spherical). */
    fun offset(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val δ = distanceM / EARTH_RADIUS_M
        val θ = Math.toRadians(bearingDeg)
        val φ1 = Math.toRadians(from.lat)
        val λ1 = Math.toRadians(from.lon)
        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return LatLon(Math.toDegrees(φ2), Math.toDegrees(λ2))
    }

    /** Google encoded polyline decoder; Valhalla `format=osrm` geometries are polyline6. [] for malformed input. */
    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0L
        var lng = 0L
        fun next(): Long? {
            var result = 0L
            var shift = 0
            var byte: Int
            do {
                if (index >= encoded.length) return null
                byte = encoded[index++].code - 63
                if (byte < 0 || byte > 63) return null
                result = result or ((byte and 0x1f).toLong() shl shift)
                shift += 5
            } while (byte >= 0x20 && shift < 35)
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            val dLat = next() ?: return emptyList()
            val dLng = next() ?: return emptyList()
            lat += dLat
            lng += dLng
            out.add(LatLon(lat / factor, lng / factor))
        }
        return out
    }
}
