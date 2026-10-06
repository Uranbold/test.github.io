package mn.navmn.app.routing

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Progress
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.route.TravelMode
import mn.navmn.app.support.TestStrings
import mn.navmn.app.ui.Orientation
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.components.OfflineIndicator
import mn.navmn.app.ui.screens.GuidanceOverlay
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-021 AC 27, 28, 38 (screen spec android-offline-pack F7–F9): the "offline" indicator OF24 / OF25 in both
 * languages; the guidance progress panel shows the icon-only form while the route came from the device and nothing for
 * a gateway route; the indicator never replaces «Дуусгах» or the attribution.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class OfflineIndicatorTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun glossaryStringsInBothLanguages() {
        assertEquals("Офлайн", TestStrings.of(Lang.MN)[StringKey.OFFLINE_INDICATOR])
        assertEquals("Офлайн газрын зургаас", TestStrings.of(Lang.MN)[StringKey.OFFLINE_INDICATOR_A11Y])
        assertEquals("Offline", TestStrings.of(Lang.EN)[StringKey.OFFLINE_INDICATOR])
        assertEquals("From the offline map", TestStrings.of(Lang.EN)[StringKey.OFFLINE_INDICATOR_A11Y])
        // AC 38: the mn values are the glossary rows OF24 / OF25 verbatim.
        val glossary = mn.navmn.app.support.Fixtures.shared("glossary.md")
        assertTrue(glossary.contains("| Offline indicator | «Офлайн» |"))
        assertTrue(glossary.contains("| Offline indicator (accessible name) | «Офлайн газрын зургаас» |"))
    }

    @Test
    fun chipShowsTheWordAndIsNamedOf25() {
        rule.setContent { NavTheme(false) { OfflineIndicator(TestStrings.of(Lang.MN)) } }
        val node = rule.onNodeWithTag("offline-indicator", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf("Офлайн газрын зургаас"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue("never a touch target", node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null)
    }

    private fun state(onDevice: Boolean) = GuidanceState(
        GuidancePhase.NAVIGATING, 1,
        Banner.Maneuver(KeyResult(ManeuverKey.TURN_LEFT), 300.0, "Энхтайваны өргөн чөлөө", null, false),
        Progress(12_400.0, 25 * 60.0, 0), null, emptyList(),
        Trip(LatLon(47.8858, 106.9173), "Зайсан толгой", TravelMode.CAR, false),
        gpsLost = false, gpsRestoredVisible = false, offline = true, voiceNoticeVisible = false, muted = false, speedMps = 12.0,
        onDeviceRoute = onDevice,
    )

    private fun show(s: GuidanceState) {
        val holder = mutableStateOf(s)
        rule.setContent {
            NavTheme(false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        GuidanceOverlay(holder.value, Lang.MN, TestStrings.of(Lang.MN), Orientation.HEADING_UP, true, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                    AttributionStrip(showEsa = false)
                }
            }
        }
    }

    @Test
    fun guidanceOnAnOnDeviceRouteShowsTheIconAndAppendsOf25ToTheProgressNode() {
        show(state(onDevice = true))
        rule.onNodeWithTag("offline-indicator-icon", useUnmergedTree = true).assertIsDisplayed()
        val descriptions = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue(descriptions.toString(), descriptions.any { it.endsWith(", Офлайн газрын зургаас") })
        rule.onNodeWithTag("nav-end").assertIsDisplayed()
        rule.onNodeWithTag("attribution").assertIsDisplayed()
    }

    @Test
    fun guidanceOnAGatewayRouteShowsNoIndicator() {
        show(state(onDevice = false))
        assertTrue(rule.onAllNodesWithTag("offline-indicator-icon", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("offline-indicator", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** NAV-012 AC 54: a restored device route shows no indicator in the restore skeleton (before the first good fix). */
    @Test
    fun restoreSkeletonOfADeviceRouteShowsNoIndicator() {
        show(state(onDevice = true).copy(restoring = true, banner = Banner.Restoring))
        rule.onNodeWithTag("nav-progress-skeleton", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("offline-indicator-icon", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val descriptions = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue(descriptions.toString(), descriptions.none { it.contains("Офлайн газрын зургаас") })
    }
}
