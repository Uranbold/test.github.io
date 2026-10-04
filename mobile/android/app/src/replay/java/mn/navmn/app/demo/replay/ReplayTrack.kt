package mn.navmn.app.demo.replay

import mn.navmn.app.location.Fix
import java.time.Instant

/**
 * One timed `<trkpt>` of a recorded track (tests/gpx/nav005, make_gpx.py): position, the `nav:acc` / `nav:speed` /
 * `nav:course` extension and the offset from the first `<time>` ([offsetMs], ADR-0016 §4.2).
 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val courseDeg: Double?,
    val speedMps: Double?,
    val offsetMs: Long,
    val gpxTimeMs: Long,
)

/**
 * ADR-0016 §4.2: a recorded GPX track turned into exactly the [Fix] values the QA replay harness (`QaGpx.fixes`) builds
 * from the same bytes, so the demo feeds the engine the inputs that produced `voice-golden.tsv` (NAV-019 AC 14 by
 * construction): accuracy `nav:acc` or 5 m, bearing `nav:course` with 10° bearing accuracy, speed `nav:speed`.
 */
class ReplayTrack(val points: List<TrackPoint>) {
    init {
        require(points.size >= 2) { "a track needs at least 2 timed points" }
        require(points.zipWithNext().all { (a, b) -> b.offsetMs >= a.offsetMs }) { "track times go backwards" }
    }

    val size: Int get() = points.size
    val durationMs: Long get() = points.last().offsetMs

    /** Offsets in ms, for [ReplaySchedule]. */
    val offsets: LongArray by lazy { LongArray(points.size) { points[it].offsetMs } }

    /** Fix [i] on the replay timeline: `elapsedMs` = [anchorElapsedMs] + its offset, `wallTimeMs` = [wallMs]. */
    fun fix(i: Int, anchorElapsedMs: Long, wallMs: Long): Fix {
        val p = points[i]
        return Fix(
            lat = p.lat,
            lon = p.lon,
            accuracyM = p.accuracyM,
            bearingDeg = p.courseDeg,
            bearingAccuracyDeg = if (p.courseDeg != null) BEARING_ACCURACY_DEG else null,
            speedMps = p.speedMps,
            elapsedMs = anchorElapsedMs + p.offsetMs,
            wallTimeMs = wallMs,
        )
    }

    /** The fixes as the QA harness builds them: elapsed = offset, wall = the GPX time (golden parity tests). */
    fun gpxTimelineFixes(): List<Fix> = points.indices.map { fix(it, 0, points[it].gpxTimeMs) }

    companion object {
        const val DEFAULT_ACCURACY_M = 5.0
        const val BEARING_ACCURACY_DEG = 10.0

        private val TRKPT = Regex("<trkpt\\b([^>]*)>([\\s\\S]*?)</trkpt>")
        private fun attr(attrs: String, name: String): String? = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
        private fun tag(body: String, name: String): String? = Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)

        /** Parses a GPX 1.1 track; throws [IllegalArgumentException] for anything that is not a usable timed track. */
        fun parse(gpx: ByteArray): ReplayTrack {
            val text = gpx.decodeToString()
            var t0: Long? = null
            val points = TRKPT.findAll(text).map { m ->
                val attrs = m.groupValues[1]
                val body = m.groupValues[2]
                val lat = attr(attrs, "lat")?.toDoubleOrNull() ?: throw IllegalArgumentException("trkpt without lat")
                val lon = attr(attrs, "lon")?.toDoubleOrNull() ?: throw IllegalArgumentException("trkpt without lon")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "trkpt outside the coordinate range" }
                val time = tag(body, "time") ?: throw IllegalArgumentException("trkpt without time")
                val wall = runCatching { Instant.parse(time).toEpochMilli() }.getOrElse { throw IllegalArgumentException("bad trkpt time") }
                val base = t0 ?: wall.also { t0 = it }
                TrackPoint(
                    lat = lat,
                    lon = lon,
                    accuracyM = tag(body, "nav:acc")?.toDoubleOrNull() ?: DEFAULT_ACCURACY_M,
                    courseDeg = tag(body, "nav:course")?.toDoubleOrNull(),
                    speedMps = tag(body, "nav:speed")?.toDoubleOrNull(),
                    offsetMs = wall - base,
                    gpxTimeMs = wall,
                )
            }.toList()
            return ReplayTrack(points)
        }
    }
}
