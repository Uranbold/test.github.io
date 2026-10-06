package mn.navmn.app.privacy

import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Progress
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.route.TravelMode
import mn.navmn.app.service.GuidanceNotificationText
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** S8 notification text (AC 15, 17, 67) and the AC 65–68 repository checks. */
class NotificationAndPrivacyTest {
    private val coordinate = Regex("-?\\d{1,3}\\.\\d{4,}")

    private fun state(banner: Banner, gpsLost: Boolean = false) = GuidanceState(
        GuidancePhase.NAVIGATING, 0, banner, Progress(4200.0, 280.0, 0), null, emptyList(),
        Trip(LatLon(47.8858, 106.9173), "Зайсан", TravelMode.CAR, false), gpsLost, false, false, false, false, 10.0,
    )

    @Test
    fun notificationTextFromOurResourcesWithoutCoordinates() {
        val mn = TestStrings.of(Lang.MN)
        val m = GuidanceNotificationText.of(state(Banner.Maneuver(KeyResult(ManeuverKey.TURN_LEFT), 304.0, "Дүнжингаравын гудамж", null, false)), Lang.MN, mn)
        assertEquals("Зүүн тийш эргэнэ үү", m.title)
        assertEquals("300\u00A0м · Дүнжингаравын гудамж", m.text)
        val r = GuidanceNotificationText.of(state(Banner.Rerouting(RerouteSecondary.UNAVAILABLE)), Lang.MN, mn)
        assertEquals("Маршрутыг дахин тооцоолж байна", r.title)
        assertNull(r.text)
        val g = GuidanceNotificationText.of(state(Banner.Maneuver(KeyResult(ManeuverKey.TURN_LEFT), 304.0, "", null, true), gpsLost = true), Lang.MN, mn)
        assertEquals("GPS дохио тасарлаа", g.title)
        val en = GuidanceNotificationText.of(state(Banner.Maneuver(KeyResult(ManeuverKey.TURN_RIGHT), 1450.0, "", null, false)), Lang.EN, TestStrings.of(Lang.EN))
        assertEquals("Turn right", en.title)
        assertEquals("1.5\u00A0km", en.text)
        for (c in listOf(m, r, g, en)) {
            assertFalse(coordinate.containsMatchIn(c.title + (c.text ?: "")))
        }
    }

    private val root get() = TestStrings.repoRoot

    @Test
    fun repositoryHasNoServerHostnamesKeystoresOrLocalFiles() {
        val android = File(root, "mobile/android")
        val tracked = android.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".kotlin") }
            .filter { it.isFile && it.name != "local.properties" && it.name != "gateway.local.properties" }
            .toList()
        val forbiddenFiles = tracked.filter { it.extension in setOf("jks", "keystore", "p12") }
        assertEquals(forbiddenFiles.toString(), 0, forbiddenFiles.size)
        val url = Regex("https?://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)")
        val allowedHosts = setOf(
            "127.0.0.1", "localhost", "10.0.2.2", "schemas.android.com", "maven-central.storage-download.googleapis.com",
            "index.crates.io", "static.crates.io", "rustup.rs", "www.apache.org", "opensource.org", "github.com",
            "developer.android.com", "docs.gradle.org", "www.openstreetmap.org", "openstreetmap.org", "maplibre.org",
            "stadiamaps.com", "services.gradle.org", "dl.google.com", "fonts.google.com", "scripts.sil.org", "openfontlicense.org", "www.gnu.org",
        )
        val bad = ArrayList<String>()
        for (f in tracked.filter { it.extension in setOf("kt", "kts", "xml", "properties", "toml", "md", "sh", "mjs", "json") }) {
            if (f.path.contains("/src/test/resources/routes/")) continue
            if (f.name == "THIRD_PARTY_NOTICES.md") continue // library project URLs (licences), not hosts the app contacts
            // NAV-005 section P: verbatim upstream licence and NOTICE texts (bundled for the offline licences screen); their
            // URLs are part of the legal text, never contacted (the screen has no links, AC 89).
            if (f.path.contains("/mobile/android/licenses/")) continue
            for (m in url.findAll(f.readText())) if (m.groupValues[1] !in allowedHosts) bad += "${f.relativeTo(android)}: ${m.value}"
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun mainSourcesNeverLogCoordinatesOrBodies() {
        val src = File(root, "mobile/android/app/src/main/java")
        val bad = ArrayList<String>()
        src.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val l = line.trim()
                val isLog = l.contains("log.d(") || l.contains("Log.d(") || l.contains("Log.i(") || l.contains("Log.w(") || l.contains("Log.e(") || l.contains("println(")
                if (isLog && Regex("lat|lon|latLon|origin|destination|body|query|fix\\b", RegexOption.IGNORE_CASE).containsMatchIn(l.substringAfter("(")))
                    bad += "${f.name}:${i + 1}: $l"
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun noAnalyticsCrashOrPlayServicesDependencies() {
        val catalog = File(root, "mobile/android/gradle/libs.versions.toml").readText() + File(root, "mobile/android/app/build.gradle.kts").readText()
        for (forbidden in listOf("firebase", "crashlytics", "play-services", "gms", "sentry", "analytics", "appcenter", "bugsnag")) {
            assertFalse("dependency on $forbidden", catalog.contains(forbidden, ignoreCase = true))
        }
        assertFalse("no dynamic versions", Regex("=\\s*\"[^\"]*\\+\"").containsMatchIn(File(root, "mobile/android/gradle/libs.versions.toml").readText()))
    }
}
