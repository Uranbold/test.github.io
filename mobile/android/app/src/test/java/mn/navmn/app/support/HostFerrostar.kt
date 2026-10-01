package mn.navmn.app.support

import org.junit.Assume

/**
 * ADR-0009 §10 / M-1: the real Ferrostar core on the host JVM (tools/build-host-ferrostar.sh). Tests that need it call
 * [require]: with -Pnav.hostFerrostar=required a missing library fails the test; otherwise the test is skipped and
 * reported as skipped (never silently replaced by a fake).
 */
object HostFerrostar {
    val available: Boolean by lazy {
        runCatching {
            uniffi.ferrostar.createOsrmResponseParser(6u)
            true
        }.getOrElse { false }
    }

    fun require() {
        if (System.getProperty("nav.hostFerrostar") == "required" && !available) {
            throw AssertionError("host libferrostar could not be loaded (jna.library.path=${System.getProperty("jna.library.path")})")
        }
        Assume.assumeTrue("host libferrostar not available", available)
    }
}
