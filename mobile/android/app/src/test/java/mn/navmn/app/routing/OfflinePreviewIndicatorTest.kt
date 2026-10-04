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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.route.alternatives.ThreeRoutes
import mn.navmn.app.search.SearchView
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.TestStrings
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-021 AC 27 (screen spec android-offline-pack F8): a preview computed on the device shows OF24 after the
 * duration · distance text (TalkBack: OF25 on that line); a gateway preview shows none. The chip never covers the
 * summary, «Эхлэх» or the OSM attribution (360×640 dp, mn).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class OfflinePreviewIndicatorTest {
    @get:Rule val rule = createComposeRule()

    private val gateway = (PreviewRoutes.process(RouteProcessor(FakeRouteParser()), ThreeRoutes.bytes(), 0) as RouteOutcome.Ok).routes
    private val onDevice = gateway.map { it.asOnDevice() }
    private val dest = Destination(LatLon(47.8858, 106.9173), "Зайсан толгой")

    private fun model(routes: List<mn.navmn.app.route.ParsedRoute>, expanded: Boolean = false) = BrowseModel(
        Lang.MN, "", SearchView.Closed, null,
        PreviewState(dest, TravelMode.CAR, false, null, PreviewResult.Route(routes, 1_790_000_000_000L, selected = 0)),
        null, false, false, 0.0, false, null, TypingLockState(), expanded, 0,
    )

    private val actions = BrowseActions(
        onQuery = {}, onClearSearch = {}, onResult = { _, _ -> }, onRetrySearch = {},
        onSettings = {}, onZoomIn = {}, onZoomOut = {}, onNorthUp = {}, onMyLocation = {}, onCardClose = {},
        onCardDirections = {}, onPreviewClose = {}, onMode = {}, onAvoid = {}, onPreviewRetry = {},
        onStart = {}, onOpenAppSettings = {}, onOpenLocationSettings = {}, onDismissMapProblem = {}, onRetryTiles = {}, onSheetHeight = {},
        onSelectRoute = {}, onSheetExpanded = {}, onReverseRetry = {},
        onSearchFieldTap = { true }, onLockedWhileTyping = {},
        onDismissLock = {}, onPassenger = {},
        onCoordinateOption = {},
        onCardBounds = { _, _ -> },
    )

    private fun show(m: BrowseModel) {
        val holder = mutableStateOf(m)
        rule.setContent {
            NavTheme(false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { BrowseOverlay(holder.value, TestStrings.of(Lang.MN), actions) }
                    AttributionStrip(showEsa = false)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    @Test
    fun onDevicePreviewShowsTheChipOnTheSummaryLine() {
        show(model(onDevice))
        val chips = bounds("offline-indicator")
        assertEquals("one chip per answer (screen spec F8)", 1, chips.size)
        val chip = chips.single()
        for (tag in listOf("nav-start", "attribution")) for (b in bounds(tag)) assertFalse("chip $chip overlaps $tag $b", chip.overlaps(b))
        val summary = rule.onNodeWithTag("preview-summary", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("inside the summary region", summary.contains(chip.center))
        // TalkBack: the merged duration line ends with OF25.
        val merged = rule.onAllNodes(hasTestTag("offline-indicator"), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertEquals(listOf("Офлайн газрын зургаас"), merged.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun gatewayPreviewShowsNoChip() {
        show(model(gateway))
        assertTrue(bounds("offline-indicator").isEmpty())
    }
}
