package mn.navmn.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.SearchView
import mn.navmn.app.search.reverse.ReverseView
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.TestStrings
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.screens.ReverseStateKey
import mn.navmn.app.ui.screens.SheetStateKey
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
 * NAV-011 S1/S2/S3 in the NavRoot structure (Robolectric, 360×640 dp, mn): the draggable preview sheet with
 * alternatives and «Дугуй» (AC 15, 17, 18, 22–24, 44), the typing-lock card (AC 31, 33, 34) and the nearest place on the
 * coordinate card (AC 8–11, P6). Expected texts are the glossary strings.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class Nav011OverlayTest {
    @get:Rule val rule = createComposeRule()

    private val three = (PreviewRoutes.process(RouteProcessor(FakeRouteParser()), ThreeRoutes.bytes(), 0) as RouteOutcome.Ok).routes
    private val dest = Destination(LatLon(47.8858, 106.9173), "Зайсан толгой")
    private val model = mutableStateOf(m())
    private val scale = mutableStateOf(1f)
    private val log = ArrayList<String>()
    private var tapAllowed = true

    private fun m(
        preview: PreviewState? = null,
        card: LatLon? = null,
        reverse: ReverseView? = null,
        lock: TypingLockState = TypingLockState(),
        expanded: Boolean = false,
        search: SearchView = SearchView.Closed,
        query: String = "",
    ) = BrowseModel(Lang.MN, query, search, card, preview, null, false, false, 0.0, false, reverse, lock, expanded, 0)

    private val actions = BrowseActions(
        onQuery = { log += "query:$it" }, onClearSearch = { log += "clear" }, onResult = { _, _ -> log += "result" }, onRetrySearch = {},
        onSettings = {}, onZoomIn = {}, onZoomOut = {}, onNorthUp = {}, onMyLocation = {}, onCardClose = { log += "cardClose" },
        onCardDirections = { log += "directions" }, onPreviewClose = {}, onMode = { log += "mode:$it" }, onAvoid = {}, onPreviewRetry = {},
        onStart = { log += "start" }, onOpenAppSettings = {}, onOpenLocationSettings = {}, onDismissMapProblem = {}, onRetryTiles = {}, onSheetHeight = {},
        onSelectRoute = { log += "select:$it" }, onSheetExpanded = { log += "expanded:$it" }, onReverseRetry = { log += "reverseRetry" },
        onSearchFieldTap = { log += "fieldTap"; tapAllowed }, onLockedWhileTyping = { log += "lockedWhileTyping" },
        onDismissLock = { log += "dismissLock" }, onPassenger = { log += "passenger" },
        onCoordinateOption = { log += "coordinate:${it.lat},${it.lon}" },
        onCardBounds = { r, wide -> cardBounds = r to wide },
    )
    private var cardBounds: Pair<Rect?, Boolean>? = null

    private fun show(lang: Lang = Lang.MN) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
                NavTheme(false) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { BrowseOverlay(model.value, TestStrings.of(lang), actions) }
                        AttributionStrip(showEsa = false)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun root(): Rect = rule.onNodeWithTag("attribution").fetchSemanticsNode().boundsInRoot

    private fun preview(r: PreviewResult, mode: TravelMode = TravelMode.CAR) = PreviewState(dest, mode, false, null, r)

    @Test
    fun collapsedSheetShowsTheSelectedRouteSummaryAndStartAt360x640() {
        model.value = m(preview(PreviewResult.Route(three, 1_790_000_000_000L, selected = 0)))
        show()
        val sheet = rule.onNodeWithTag("route-preview").fetchSemanticsNode()
        assertEquals("collapsed", sheet.config.getOrNull(SheetStateKey))
        // NAV-018 (AC 35, screen spec Q1/Q4): the visible header row is replaced by the points block; «Маршрут харах» is
        // the sheet's pane title only.
        assertEquals("Маршрут харах", sheet.config.getOrNull(SemanticsProperties.PaneTitle))
        for (t in listOf("Машин", "Явган", "Дугуй", "Маршрут 1", "Эхлэх")) assertTrue("missing «$t»", exists(t))
        assertFalse("«Маршрут сонгох» is in the expanded part only", exists("Маршрут сонгох"))
        // AC 18: at 360×640 / 100 % the summary and «Эхлэх» are fully visible without dragging, above the attribution.
        val att = root()
        for (tag in listOf("preview-summary", "nav-start")) {
            val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("$tag clipped", n.size.height.toFloat(), n.boundsInRoot.height, 1f)
            assertTrue("$tag overlaps the attribution", n.boundsInRoot.bottom <= att.top + 0.5f)
        }
        // P3: collapsed ≤ 60 % of the area above the attribution.
        assertTrue(sheet.boundsInRoot.height <= att.top * 0.6f + 1f)
        rule.onNodeWithTag("preview-sheet-handle").performClick()
        assertEquals("expanded:true", log.last())
    }

    @Test
    fun expandedSheetRouteOptionsGroupSelectionAndTalkBackDescriptions() {
        model.value = m(preview(PreviewResult.Route(three, 1_790_000_000_000L, selected = 1)), expanded = true)
        show()
        assertEquals("expanded", rule.onNodeWithTag("route-preview").fetchSemanticsNode().config.getOrNull(SheetStateKey))
        assertTrue(exists("Маршрут сонгох"))
        val options = rule.onAllNodesWithTag("route-option").fetchSemanticsNodes()
        assertEquals(3, options.size)
        val density = rule.density.density
        for (o in options) assertTrue("option ≥ 48 dp", o.size.height / density >= 48f)
        val descriptions = options.map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "" }
        assertTrue(descriptions[0], descriptions[0].startsWith("Маршрут 1") && descriptions[0].endsWith("Өөр маршрут"))
        assertFalse(descriptions[1], descriptions[1].contains("Өөр маршрут"))
        assertTrue(descriptions[2].endsWith("Өөр маршрут"))
        rule.onAllNodesWithTag("route-option")[1].assertIsSelected()
        // The summary names the selection.
        assertTrue(exists("Маршрут 2"))
        rule.onAllNodesWithTag("route-option")[2].performClick()
        assertEquals("select:2", log.last())
        // Points block and avoid switch are in the expanded part (car).
        assertTrue(exists("Шороон замаас зайлсхийх"))
        assertTrue(exists("Миний байршил"))
    }

    @Test
    fun oneRouteHasNoOptionsGroupBikeHidesTheAvoidSwitchTooFarIsN11() {
        model.value = m(preview(PreviewResult.Route(three[0], 1_790_000_000_000L)), expanded = true)
        show()
        assertFalse(exists("Маршрут сонгох"))
        assertFalse("k = 1: no «Маршрут {n}» line", exists("Маршрут 1"))
        rule.onNodeWithTag("mode-bike").performClick()
        assertEquals("mode:BICYCLE", log.last())
        model.value = m(preview(PreviewResult.Route(three[0], 1_790_000_000_000L), TravelMode.BICYCLE), expanded = true)
        rule.waitForIdle()
        assertFalse("AC 23: no avoid switch on «Дугуй»", exists("Шороон замаас зайлсхийх"))
        rule.onNodeWithTag("mode-bike").assertIsSelected()
        model.value = m(preview(PreviewResult.TooFar, TravelMode.BICYCLE))
        rule.waitForIdle()
        assertTrue(exists("Энэ зай явганаар эсвэл дугуйгаар хэт хол байна"))
    }

    @Test
    fun noRouteWithTheAvoidHintExpandsTheSheetOnce() {
        model.value = m(PreviewState(dest, TravelMode.CAR, true, null, PreviewResult.NoRoute(true)))
        show()
        assertEquals("expanded:true", log.last())
    }

    @Test
    fun stackedTabsAtFontScale2KeepEveryLabelWhole() {
        scale.value = 2f
        model.value = m(preview(PreviewResult.Route(three, 1_790_000_000_000L)))
        show()
        val density = rule.density.density
        for (tag in listOf("mode-car", "mode-walk", "mode-bike")) {
            val n = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue("$tag ≥ 64 dp stacked", n.size.height / density >= 63.5f)
        }
        // «Эхлэх» never scrolls away (P2).
        val start = rule.onNodeWithTag("nav-start", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(start.size.height.toFloat(), start.boundsInRoot.height, 1f)
    }

    @Test
    fun lockCardOnTapWithCloseAndPassenger() {
        model.value = m(lock = TypingLockState(engaged = true, overridden = false, cardVisible = false, engagement = 1))
        show()
        assertFalse("AC 30: nothing changes on screen before a tap", exists("Хөдөлж байх үед бичих боломжгүй"))
        tapAllowed = false
        // While locked the field is read-only (no SetText action, so no keyboard); it is found by its description.
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Газар, хаяг хайх")).performClick()
        assertTrue("the tap is reported to the lock", log.contains("fieldTap"))
        model.value = m(lock = TypingLockState(engaged = true, overridden = false, cardVisible = true, engagement = 1))
        rule.waitForIdle()
        for (t in listOf("Хөдөлж байх үед бичих боломжгүй", "Жолооч бол зогсоод хайна уу", "Хаах", "Би зорчигч")) assertTrue("missing «$t»", exists(t))
        val density = rule.density.density
        for (tag in listOf("typing-lock-close", "typing-lock-passenger")) {
            val n = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue("$tag ≥ 48 dp", n.size.height / density >= 47.5f && n.size.width / density >= 47.5f)
        }
        rule.onNodeWithTag("typing-lock-passenger").performClick()
        assertEquals("passenger", log.last())
        rule.onNodeWithTag("typing-lock-close").performClick()
        assertEquals("dismissLock", log.last())
        // AC 33: the clear button still works while locked.
        model.value = m(lock = TypingLockState(engaged = true, overridden = false, cardVisible = true, engagement = 1), query = "Sukh")
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Хайлтыг арилгах")).performClick()
        assertEquals("clear", log.last())
    }

    @Test
    fun nearestPlaceStatesKeepDirectionsInPlace() {
        val p = LatLon(47.9189, 106.9176)
        val feature = PhotonFeature(LatLon(47.919, 106.918), mapOf("osm_key" to "place", "osm_value" to "square", "name" to "Сүхбаатарын талбай", "district" to "Сүхбаатар дүүрэг"))
        val states = listOf(
            ReverseView.Pending, ReverseView.Loading, ReverseView.Place(feature), ReverseView.Empty, ReverseView.Offline,
            ReverseView.Unavailable, ReverseView.RateLimited(false), ReverseView.Error,
        )
        model.value = m(card = p, reverse = states[0])
        show()
        var buttonTop: Float? = null
        for (s in states) {
            model.value = m(card = p, reverse = s)
            rule.waitForIdle()
            val area = rule.onNodeWithTag("coord-nearest").fetchSemanticsNode()
            assertEquals(s.id, area.config.getOrNull(ReverseStateKey))
            assertTrue("«Сонгосон цэг» heading stays", exists("Сонгосон цэг"))
            val btn = rule.onNodeWithText("Маршрут гаргах").fetchSemanticsNode().boundsInRoot.top
            // P6: the card is bottom-anchored, so «Маршрут гаргах» stays at the same position in every state.
            buttonTop?.let { assertEquals("«Маршрут гаргах» moved in state ${s.id}", it, btn, 1f) }
            buttonTop = buttonTop ?: btn
        }
        model.value = m(card = p, reverse = ReverseView.Place(feature))
        rule.waitForIdle()
        assertTrue(exists("Ойролцоох газар"))
        assertTrue(exists("Сүхбаатарын талбай"))
        model.value = m(card = p, reverse = ReverseView.Empty)
        rule.waitForIdle()
        assertTrue(exists("Илэрц олдсонгүй"))
        model.value = m(card = p, reverse = ReverseView.Unavailable)
        rule.waitForIdle()
        assertTrue(exists("Хайлт түр ажиллахгүй байна"))
        rule.onNodeWithTag("coord-nearest-retry").performClick()
        assertEquals("reverseRetry", log.last())
        model.value = m(card = p, reverse = ReverseView.Offline)
        rule.waitForIdle()
        assertTrue(exists("Интернэт холболт алга"))
        // «Маршрут гаргах» is usable in every state (AC 8).
        rule.onNodeWithText("Маршрут гаргах").performClick()
        assertEquals("directions", log.last())
    }

    /** NAV-011 AC 7, 33 (D140, D146): the typed-coordinate option row (one merged button, ≥ 48 dp, no loading row). */
    @Test
    fun typedCoordinateOptionRowIsOneButtonSelectableAlsoWhileLocked() {
        val p = LatLon(47.9189, 106.9176)
        model.value = m(search = SearchView.Coordinate(p), query = "47.9189, 106.9176")
        show()
        assertEquals(1, rule.onAllNodesWithTag("search-coordinate-option").fetchSemanticsNodes().size)
        val row = rule.onNodeWithTag("search-coordinate-option").fetchSemanticsNode()
        assertEquals(listOf("Сонгосон цэг, 47.91890, 106.91760"), row.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Button, row.config.getOrNull(SemanticsProperties.Role))
        assertTrue("≥ 48 dp (56 dp row)", row.size.height / rule.density.density >= 55.5f)
        assertTrue(exists("Сонгосон цэг"))
        assertTrue(exists("47.91890, 106.91760"))
        assertFalse("no loading row", exists("Ачаалж байна…"))
        rule.onNodeWithTag("search-coordinate-option").performClick()
        assertEquals("coordinate:47.9189,106.9176", log.last())
        // AC 33: with the lock engaged and its card shown, the option is still there and selectable.
        model.value = m(search = SearchView.Coordinate(p), query = "47.9189, 106.9176", lock = TypingLockState(engaged = true, overridden = false, cardVisible = true, engagement = 1))
        rule.waitForIdle()
        assertTrue(exists("Хөдөлж байх үед бичих боломжгүй"))
        rule.onNodeWithTag("search-coordinate-option").performClick()
        assertEquals(2, log.count { it == "coordinate:47.9189,106.9176" })
    }

    /** AC 7, 13: the same option in English (line 1 from resources, coordinates unchanged). */
    @Test
    @Config(qualifiers = "en-w360dp-h640dp")
    fun typedCoordinateOptionInEnglish() {
        model.value = m(search = SearchView.Coordinate(LatLon(47.9189, 106.9176))).copy(lang = Lang.EN)
        show(Lang.EN)
        val row = rule.onNodeWithTag("search-coordinate-option").fetchSemanticsNode()
        assertEquals(listOf("Selected point, 47.91890, 106.91760"), row.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(exists("Selected point"))
    }

    /** P8 (D140): on wide windows the coordinate card is a start-edge column of the side-sheet width (both entry points). */
    @Test
    @Config(qualifiers = "mn-w640dp-h360dp-land")
    fun coordinateCardIsAStartColumnOnWideWindows() {
        model.value = m(card = LatLon(47.9189, 106.9176), reverse = ReverseView.Loading)
        show()
        val d = rule.density.density
        val card = rule.onNodeWithTag("coordinate-card").fetchSemanticsNode().boundsInRoot
        // clamp(320 dp, 40 % of 640 dp, 400 dp) = 320 dp column, 8 dp start margin.
        assertEquals(8f, card.left / d, 1f)
        assertEquals(320f, card.right / d, 1f)
        assertEquals(true, cardBounds?.second)
        assertTrue(exists("Маршрут гаргах"))
    }
}
