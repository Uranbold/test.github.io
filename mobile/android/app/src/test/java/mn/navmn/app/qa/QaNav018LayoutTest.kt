package mn.navmn.app.qa

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.background.battery.BatteryHintSlot
import mn.navmn.app.background.battery.LocalBatteryHint
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.SearchView
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.TestStrings
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.screens.preview.PointActions
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QA, NAV-018 layout checks the mobile suite does not make (test plan §4):
 * - TC-L01 AC 27 + 30: chosen start (O1) **and** the NAV-012 B2 entry row at 360×640, collapsed: the fields, summary,
 *   «Эхлэх» and O1 unclipped above the attribution, map band ≥ 160 dp, in both themes;
 * - TC-L02 AC 30: expanded: O1 and the full B2 hint card are both visible and neither overlaps «Эхлэх» or the
 *   attribution;
 * - TC-L03 AC 27 (P5): a wide window shows the fields, swap and «Маршрутын заавар» in the side sheet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class QaNav018LayoutTest {
    @get:Rule val rule = createComposeRule()

    private val route = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
    private val dest = Destination(LatLon(47.8858, 106.9173), "Зайсан толгой")
    private val chosen = RoutePoint.Place(LatLon(47.9213, 106.8948), "Гандантэгчинлэн хийд")
    @Suppress("unused") private val device = RoutePoint.MyLocation(Fix(47.9189, 106.9176, 5.0, null, null, null, 0, 0))
    private val model = mutableStateOf<BrowseModel?>(null)
    private val night = mutableStateOf(false)

    private val O1 = "Замчлал зөвхөн таны байршлаас эхэлнэ"

    private fun m(expanded: Boolean) = BrowseModel(
        lang = Lang.MN, query = "", searchView = SearchView.Closed, card = null,
        preview = PreviewState(dest, TravelMode.CAR, false, chosen, PreviewResult.Route(route, 1_790_000_000_000L)),
        mapProblem = null, tilesFailed = false, offline = false, bearing = 0.0, followingMe = false,
        sheetExpanded = expanded,
    )

    private val actions = BrowseActions(
        onQuery = {}, onClearSearch = {}, onResult = { _, _ -> }, onRetrySearch = {},
        onSettings = {}, onZoomIn = {}, onZoomOut = {}, onNorthUp = {}, onMyLocation = {}, onCardClose = {},
        onCardDirections = {}, onPreviewClose = {}, onMode = {}, onAvoid = {}, onPreviewRetry = {},
        onStart = {}, onOpenAppSettings = {}, onOpenLocationSettings = {}, onDismissMapProblem = {}, onRetryTiles = {}, onSheetHeight = {},
        onSheetExpanded = {}, onSearchFieldTap = { true }, onDismissLock = {}, onPassenger = {},
        points = PointActions(
            onField = {}, onSwap = {}, onEditorQuery = {}, onEditorResult = { _, _ -> }, onEditorMyLocation = {}, onEditorClose = {},
            onEditorFieldTap = { true }, onCardSetOrigin = {}, onCardSetDestination = {}, onTurnRow = {},
        ),
    )

    private fun show() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, 1f),
                LocalBatteryHint provides BatteryHintSlot(visible = true, onExpand = {}, onDismiss = {}, onOpenSettings = {}),
            ) {
                NavTheme(night.value) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { model.value?.let { BrowseOverlay(it, TestStrings.of(Lang.MN), actions) } }
                        AttributionStrip(showEsa = false)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val density get() = rule.density.density
    private fun dp(px: Float) = px / density
    private fun node(tag: String): SemanticsNode = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
    private fun textNode(text: String): SemanticsNode = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()
    private fun att(): Rect = node("attribution").boundsInRoot
    private fun whole(n: SemanticsNode) = kotlin.math.abs(n.size.height.toFloat() - n.boundsInRoot.height) <= 1f && kotlin.math.abs(n.size.width.toFloat() - n.boundsInRoot.width) <= 1f
    private fun overlaps(a: Rect, b: Rect) = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f

    /** TC-L01 (AC 27, AC 30): collapsed sheet with O1 and the B2 entry row, day and night. */
    @Test
    fun tcL01_collapsedWithO1AndTheBatteryRowKeepsTheAc27Guarantees() {
        model.value = m(expanded = false)
        show()
        for (theme in listOf(false, true)) {
            night.value = theme
            rule.waitForIdle()
            val label = if (theme) "night" else "day"
            val sheet = node("route-preview").boundsInRoot
            assertTrue("$label: map band ${dp(sheet.top)} dp ≥ 160", dp(sheet.top) >= 160f - 0.5f)
            for (tag in listOf("route-origin", "route-destination", "preview-summary", "nav-start", "route-origin-hint", "battery-entry")) {
                val n = node(tag)
                assertTrue("$label: $tag clipped", whole(n))
                assertTrue("$label: $tag under the attribution", n.boundsInRoot.bottom <= att().top + 0.5f)
            }
            assertTrue("$label: O1 does not cover «Эхлэх»", !overlaps(node("route-origin-hint").boundsInRoot, node("nav-start").boundsInRoot))
            assertTrue("$label: B2 row does not cover «Эхлэх»", !overlaps(node("battery-entry").boundsInRoot, node("nav-start").boundsInRoot))
        }
    }

    /** TC-L02 (AC 30): expanded: O1 and the full B2 card both visible; neither overlaps «Эхлэх» or the attribution. */
    @Test
    fun tcL02_expandedShowsO1AndTheB2CardWithoutCoveringStart() {
        model.value = m(expanded = true)
        show()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("battery-hint"))
        rule.waitForIdle()
        val start = node("nav-start").boundsInRoot
        val hint = node("route-origin-hint")
        val b2 = node("battery-hint")
        assertTrue("O1 shown whole", whole(hint))
        assertTrue("B2 card visible", b2.boundsInRoot.height > 0f && b2.boundsInRoot.bottom <= att().top + 0.5f)
        assertTrue("B2 does not cover «Эхлэх»", !overlaps(b2.boundsInRoot, start))
        assertTrue("O1 does not cover «Эхлэх»", !overlaps(hint.boundsInRoot, start))
        assertTrue("«Эхлэх» above the attribution", start.bottom <= att().top + 0.5f)
        assertEquals(O1, textNode(O1).config.getOrNull(SemanticsProperties.Text)?.joinToString())
    }

    /** TC-L03 (AC 27, NAV-011 P5): wide windows put the fields, swap and the list in the side sheet. */
    @Test
    @Config(qualifiers = "mn-w900dp-h600dp")
    fun tcL03_wideWindowSideSheetHoldsFieldsSwapAndList() {
        model.value = m(expanded = false)
        show()
        for (tag in listOf("route-origin", "route-destination", "route-swap", "nav-start")) assertTrue("$tag in the side sheet", whole(node(tag)))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("route-steps"))
        assertTrue("«Маршрутын заавар» in the side sheet", rule.onAllNodes(hasText("Маршрутын заавар"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val sheet = node("route-preview").boundsInRoot
        assertTrue("side sheet, not a bottom sheet (width ${dp(sheet.width)} dp)", dp(sheet.width) < 900f * 0.75f)
    }
}
