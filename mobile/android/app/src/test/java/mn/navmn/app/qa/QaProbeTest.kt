package mn.navmn.app.qa

import mn.navmn.app.engine.Banner
import mn.navmn.app.i18n.Lang
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.HostFerrostar
import org.junit.Test

/** Temporary diagnostic (deleted before handoff). */
class QaProbeTest {
    @Test
    fun probeG3b() {
        HostFerrostar.require()
        val r = QaRun(QaGpx.routeBytes("G3b"), TravelMode.CAR, Lang.MN, P3)
        val fixes = QaGpx.fixes("G3b")
        r.run(fixes, tailMs = 3_000)
        val o = RouteOracle(r.initial.plan)
        println("maneuverAlong=" + o.maneuverAlong)
        for ((t, s) in r.states.filter { it.first in 247_000L..284_000L }) {
            val b = s.banner
            println("t=$t phase=${s.phase} lost=${s.gpsLost} banner=${r.bannerText(b)} d=${(b as? Banner.Maneuver)?.distanceM} rem=${s.progress.distanceRemaining}")
        }
        println(r.spoken.map { "${it.first} ${it.second.maneuver} ${it.second.text} trig=${it.second.triggerAtMs}" })
    }
}
