package mn.navmn.app.routing.debug

import android.os.Build
import android.os.Process
import android.os.SystemClock
import mn.navmn.app.BuildConfig
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.OsrmPlanParser
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.TravelMode
import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.InstalledRouting
import mn.navmn.app.routing.RoutingEngineHandle
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * NAV-021 R5 / AC 1, 4, 25, 34 (debug only): the on-device benchmark for the PO's phone. For each fixed route (the
 * golden-set city points, no other coordinates): **cold** = the `:routing` process is ended first, so the request
 * includes the bind, the engine build and opening the tar; then [WARM_RUNS] warm requests. Reports wall times, warm
 * p50 / p95, the `:routing` peak PSS (its own `Debug.getPss`), the response size and SHA-256 (AC 25: delivered intact
 * through the pipe), and the AC 4 hook (main-process initialisations seen in `:routing`, must be 0). Thresholds are the
 * story's AC 34 table for the chosen phone class.
 */
class RoutingBenchmark(
    private val engine: RoutingEngineHandle,
    private val routing: InstalledRouting,
    private val phoneClass: PhoneClass,
) {
    enum class PhoneClass(val coldUbMs: Long, val coldLongMs: Long, val warmP95Ms: Long, val pssMiB: Long) {
        MID(1_500, 2_500, 500, 160),
        LOW(3_000, 5_000, 1_500, 160),
    }

    data class Case(val id: String, val request: RouteRequest, val long: Boolean)

    fun run(): String {
        val sb = StringBuilder()
        sb.appendLine("NAV-021 on-device routing benchmark (debug build ${BuildConfig.VERSION_NAME})")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
        sb.appendLine("routing file: ${routing.version} (${routing.graphBuilder}), ${routing.tar.length()} bytes")
        sb.appendLine("phone class thresholds: ${phoneClass.name} (AC 34)")
        var allPass = true
        var peakPss = 0L
        for (c in CASES) {
            restartRoutingProcess()
            val body = RouteBody.json(c.request)
            val (coldMs, coldAnswer) = timed { engine.route(routing, body, TIMEOUT_MS) }
            peakPss = maxOf(peakPss, pssKb())
            val warm = ArrayList<Long>()
            var last: EngineAnswer = coldAnswer
            repeat(WARM_RUNS) {
                val (ms, a) = timed { engine.route(routing, body, TIMEOUT_MS) }
                warm += ms
                last = a
                peakPss = maxOf(peakPss, pssKb())
            }
            warm.sort()
            val p50 = percentile(warm, 50)
            val p95 = percentile(warm, 95)
            val coldLimit = if (c.long) phoneClass.coldLongMs else phoneClass.coldUbMs
            val ok = coldAnswer is EngineAnswer.Osrm && OsrmPlanParser.errorCode(coldAnswer.bytes).first == "Ok"
            val pass = ok && coldMs <= coldLimit && p95 <= phoneClass.warmP95Ms
            allPass = allPass && pass
            sb.appendLine("${c.id}: cold ${coldMs} ms (limit $coldLimit), warm p50 ${p50} ms, p95 ${p95} ms (limit ${phoneClass.warmP95Ms}), ${describe(coldAnswer)}, warm ${describe(last)} -> ${if (pass) "PASS" else "FAIL"}")
        }
        val pssMiB = ceil(peakPss / 1024.0).toLong()
        val pssPass = pssMiB <= phoneClass.pssMiB
        val info = (engine as? RoutingEngineHandle.Bound)?.client?.processInfo()
        sb.appendLine(":routing peak PSS ${pssMiB} MiB (limit ${phoneClass.pssMiB}) -> ${if (pssPass) "PASS" else "FAIL"}")
        sb.appendLine("AC 4: :routing pid ${info?.first} (main pid ${Process.myPid()}), main-process initialisations in :routing = ${info?.second}")
        sb.appendLine("overall: ${if (allPass && pssPass && info?.second == 0) "PASS" else "FAIL or incomplete"}")
        return sb.toString()
    }

    private fun describe(a: EngineAnswer): String = when (a) {
        is EngineAnswer.Osrm -> "code ${OsrmPlanParser.errorCode(a.bytes).first}, ${a.bytes.size} bytes, sha256 ${sha256(a.bytes).take(16)}"
        is EngineAnswer.Failed -> "error ${a.error}"
    }

    private fun restartRoutingProcess() {
        val client = (engine as? RoutingEngineHandle.Bound)?.client ?: return
        val pid = client.processInfo()?.first
        client.unbind()
        if (pid != null && pid != Process.myPid()) {
            Process.killProcess(pid)
            SystemClock.sleep(500) // let the system notice the death before the cold bind
        }
    }

    private fun pssKb(): Long = (engine as? RoutingEngineHandle.Bound)?.client?.processInfo()?.third ?: 0L

    private inline fun <T> timed(block: () -> T): Pair<Long, T> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = block()
        return (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000 to r
    }

    companion object {
        const val WARM_RUNS = 15
        const val TIMEOUT_MS = 30_000L

        private val UB = LatLon(47.9189, 106.9176) // P1, Sükhbaatar Square (golden set)
        private val P3 = LatLon(47.8858, 106.9173)
        private val DARKHAN = LatLon(49.4867, 105.9228)
        private val CHOIBALSAN = LatLon(48.071, 114.534)
        private val OLGII = LatLon(48.968, 89.962)

        private fun car(a: LatLon, b: LatLon) = RouteRequest(a, b, TravelMode.CAR, avoidUnpaved = false, lang = Lang.MN)

        val CASES = listOf(
            Case("p1-p3-car", car(UB, P3), long = false),
            Case("ub-darkhan-car", car(UB, DARKHAN), long = false),
            Case("choibalsan-olgii-car", car(CHOIBALSAN, OLGII), long = true),
        )

        fun percentile(sorted: List<Long>, p: Int): Long {
            if (sorted.isEmpty()) return 0
            val i = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[i]
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
