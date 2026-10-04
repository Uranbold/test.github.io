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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.instructions.ManeuverInput
import mn.navmn.app.location.Fix
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.preview.points.MyLocationOption
import mn.navmn.app.preview.points.PointEditorState
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.preview.points.PointsUi
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.preview.points.StepFocus
import mn.navmn.app.preview.turnlist.TurnListModel
import mn.navmn.app.preview.turnlist.TurnRowText
import mn.navmn.app.route.GuidancePlan
import mn.navmn.app.route.ParsedRoute
import mn.navmn.app.route.PlanStep
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.route.alternatives.PreviewRoutes
import mn.navmn.app.route.alternatives.ThreeRoutes
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchView
import mn.navmn.app.search.reverse.ReverseView
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.Fixtures
import mn.navmn.app.support.TestStrings
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.screens.preview.EditingKey
import mn.navmn.app.ui.screens.preview.PointActions
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-018 UI in the NavRoot structure (Robolectric, 360×640 dp, native graphics): the points block, swap, O1 and the
 * disabled «Эхлэх», the Q3 cap / 160 dp map band, «Маршрутын заавар» (lazy rows, semantics, row tap), the coordinate
 * card buttons in preview context, the point editor with the «Миний байршил» option and the lock card, and the marker
 * descriptions. Expected texts are the glossary strings.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class Nav018OverlayTest {
    @get:Rule val rule = createComposeRule()

    private val three = (PreviewRoutes.process(RouteProcessor(FakeRouteParser()), ThreeRoutes.bytes(), 0) as RouteOutcome.Ok).routes
    private val p1p3 = (RouteProcessor(FakeRouteParser()).process(Fixtures.route("p1-p3-car-mn.json"), 0) as RouteOutcome.Ok).route
    private val dest = Destination(LatLon(47.8858, 106.9173), "Зайсан толгой")
    private val device = RoutePoint.MyLocation(Fix(47.9189, 106.9176, 5.0, null, null, null, 0, 0))
    private val gandan = RoutePoint.Place(LatLon(47.9213, 106.8948), "Гандан хийд")
    private val model = mutableStateOf<BrowseModel?>(null)
    private val scale = mutableStateOf(1f)
    private val log = ArrayList<String>()
    private var tapAllowed = true

    private fun m(
        preview: PreviewState?,
        expanded: Boolean = false,
        card: LatLon? = null,
        reverse: ReverseView? = null,
        points: PointsUi = PointsUi(),
        fieldView: SearchView = SearchView.Closed,
        lock: TypingLockState = TypingLockState(),
        lang: Lang = Lang.MN,
    ) = BrowseModel(lang, "", SearchView.Closed, card, preview, null, false, false, 0.0, false, reverse, lock, expanded, 0, points, fieldView)

    private val actions = BrowseActions(
        onQuery = {}, onClearSearch = {}, onResult = { _, _ -> }, onRetrySearch = {},
        onSettings = {}, onZoomIn = {}, onZoomOut = {}, onNorthUp = {}, onMyLocation = {}, onCardClose = { log += "cardClose" },
        onCardDirections = { log += "directions" }, onPreviewClose = { log += "close" }, onMode = {}, onAvoid = {}, onPreviewRetry = {},
        onStart = { log += "start" }, onOpenAppSettings = {}, onOpenLocationSettings = {}, onDismissMapProblem = {}, onRetryTiles = {}, onSheetHeight = {},
        onSheetExpanded = { log += "expanded:$it" }, onSearchFieldTap = { tapAllowed }, onDismissLock = { log += "dismissLock" }, onPassenger = { log += "passenger" },
        points = PointActions(
            onField = { log += "field:$it" },
            onSwap = { log += "swap" },
            onEditorQuery = { log += "editorQuery:$it" },
            onEditorResult = { _, name -> log += "editorResult:$name" },
            onEditorMyLocation = { log += "myLocation" },
            onEditorClose = { log += "editorClose" },
            onEditorFieldTap = { log += "editorTap"; tapAllowed },
            onCardSetOrigin = { log += "setOrigin" },
            onCardSetDestination = { log += "setDestination" },
            onTurnRow = { log += "row:$it" },
        ),
    )

    private fun show(lang: Lang = Lang.MN) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
                NavTheme(false) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { model.value?.let { BrowseOverlay(it, TestStrings.of(lang), actions) } }
                        AttributionStrip(showEsa = false)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val density get() = rule.density.density
    private fun node(tag: String): SemanticsNode = rule.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode()
    private fun exists(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
    private fun att(): Rect = node("attribution").boundsInRoot
    private fun dp(px: Float) = px / density
    private fun desc(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: ""
    private fun state(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.StateDescription) ?: ""
    private fun disabled(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Disabled) != null

    /** AC 27 / Q3: map band ≥ 160 dp above the collapsed sheet; the sheet within min(75 %, area − 168 dp). */
    private fun assertBandAndCap(label: String) {
        val sheet = node("route-preview").boundsInRoot
        val area = att().top
        assertTrue("$label: map band ${dp(sheet.top)} dp", dp(sheet.top) >= 160f - 0.5f)
        assertTrue("$label: sheet ${dp(sheet.height)} dp over the Q3 cap", sheet.height <= minOf(area * 0.75f, area - 168f * density) + 1f)
        for (tag in listOf("route-origin", "route-destination", "preview-summary", "nav-start")) {
            val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("$label: $tag clipped", n.size.height.toFloat(), n.boundsInRoot.height, 1f)
            assertTrue("$label: $tag under the attribution", n.boundsInRoot.bottom <= area + 0.5f)
        }
    }

    @Test
    fun deviceStartCollapsedShowsTheFieldsAndAnEnabledStart() {
        model.value = m(PreviewState(dest, TravelMode.CAR, false, device, PreviewResult.Route(three, 1_790_000_000_000L)))
        show()
        val sheet = node("route-preview")
        assertEquals("«Маршрут харах» stays the pane title", "Маршрут харах", sheet.config.getOrNull(SemanticsProperties.PaneTitle))
        assertFalse("AC 35: the visible header row is replaced by the points block", exists("Маршрут харах"))
        val origin = node("route-origin")
        assertEquals("AC 1: accessible name", "Эхлэх цэг", desc(origin))
        assertEquals("Миний байршил", state(origin))
        val destination = node("route-destination")
        assertEquals("Очих газар", desc(destination))
        assertEquals("Зайсан толгой", state(destination))
        for (tag in listOf("route-origin", "route-destination", "route-swap", "route-close")) {
            val n = node(tag)
            assertTrue("$tag ≥ 48 dp", dp(n.size.height.toFloat()) >= 47.5f && dp(n.size.width.toFloat()) >= 47.5f)
        }
        assertEquals("Эхлэх цэг, очих газрыг солих", desc(node("route-swap")))
        assertFalse(disabled(node("route-swap")))
        assertFalse("AC 15: enabled with a device start", disabled(node("nav-start")))
        assertTrue("no O1 with a device start", tagged("route-origin-hint").isEmpty())
        assertFalse("the list is not in the collapsed sheet (AC 18)", exists("Маршрутын заавар"))
        assertBandAndCap("device start")
        // AC 8: marker descriptions on app-owned nodes.
        assertEquals("Эхлэх цэг: Миний байршил", desc(node("marker-origin")))
        assertEquals("Очих газар: Зайсан толгой", desc(node("marker-destination")))
        node("route-origin").let { rule.onNodeWithTag("route-origin").performClick() }
        rule.onNodeWithTag("route-destination").performClick()
        rule.onNodeWithTag("route-swap").performClick()
        rule.onNodeWithTag("route-close").performClick()
        assertEquals(listOf("field:ORIGIN", "field:DESTINATION", "swap", "close"), log)
    }

    @Test
    fun chosenStartShowsO1AndADisabledStartThatDoesNothing() {
        model.value = m(PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(three, 1_790_000_000_000L)))
        show()
        assertEquals("Гандан хийд", state(node("route-origin")))
        assertTrue("AC 15: O1", exists("Замчлал зөвхөн таны байршлаас эхэлнэ"))
        val start = node("nav-start")
        assertTrue("AC 15: disabled", disabled(start))
        rule.onNodeWithTag("nav-start").performClick()
        assertFalse("AC 15: a disabled «Эхлэх» does nothing", log.contains("start"))
        // TalkBack reads «Эхлэх» first, then O1 (Q7).
        val hint = node("route-origin-hint")
        assertEquals(0f, start.config.getOrNull(SemanticsProperties.TraversalIndex))
        assertEquals(1f, hint.config.getOrNull(SemanticsProperties.TraversalIndex))
        assertTrue("O1 drawn above «Эхлэх»", hint.boundsInRoot.bottom <= start.boundsInRoot.top + 0.5f)
        assertEquals(hint.size.height.toFloat(), hint.boundsInRoot.height, 1f)
        assertBandAndCap("chosen start")
        assertEquals("Эхлэх цэг: Гандан хийд", desc(node("marker-origin")))
    }

    @Test
    fun noUsableLocationLeavesTheStartEmptyAndSwapDisabled() {
        model.value = m(PreviewState(dest, TravelMode.CAR, false, null, PreviewResult.Location(LocationProblem.DENIED)))
        show()
        assertTrue("AC 2: placeholder", exists("Эхлэх цэг сонгох"))
        assertEquals("Эхлэх цэг", desc(node("route-origin")))
        assertEquals("Эхлэх цэг сонгох", state(node("route-origin")))
        assertTrue("AC 2: the NAV-005 message stays", exists("Байршлын зөвшөөрөл олгоогүй байна"))
        val swap = node("route-swap")
        assertTrue("AC 11: disabled while a point is empty", disabled(swap))
        assertEquals("read as disabled, with its name", "Эхлэх цэг, очих газрыг солих", desc(swap))
        assertTrue("no start marker description without a start", tagged("marker-origin").isEmpty())
        assertTrue(disabled(node("nav-start")))
    }

    @Test
    fun snapNoticeShowsTheLargerOfTheTwoSnapDistances() {
        val far = ParsedRoute(p1p3.plan.copy(snapDistances = listOf(720.0, 10.0)), p1p3.native)
        model.value = m(PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(far, 1_790_000_000_000L)))
        show()
        assertTrue("AC 14: the start's 720 m", exists("Хамгийн ойрын зам сонгосон цэгээс 720\u00A0м зайтай"))
    }

    @Test
    fun expandedSheetEndsWithTheTurnListWhoseRowsReadAsOne() {
        model.value = m(PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(p1p3, 1_790_000_000_000L)), expanded = true)
        show()
        assertFalse("the NAV-011 display-only points block is gone", exists("Очих газар: Зайсан толгой") && tagged("route-point-row").isNotEmpty())
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("route-steps"))
        val heading = node("route-steps")
        assertNotNull("AC 23: heading semantics", heading.config.getOrNull(SemanticsProperties.Heading))
        assertTrue(exists("Маршрутын заавар"))
        val rows = TurnListModel.build(p1p3.plan)
        assertEquals(rows.size, heading.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount)
        val shown = tagged("route-step")
        assertTrue(shown.isNotEmpty())
        val first = shown.first()
        assertEquals("AC 23: instruction, street, distance", TurnRowText.contentDescription(rows[0], Lang.MN, TestStrings.of(Lang.MN)), desc(first))
        assertEquals(0, first.config.getOrNull(SemanticsProperties.CollectionItemInfo)?.rowIndex)
        for (n in shown) assertTrue("AC 22: row ≥ 48 dp", dp(n.size.height.toFloat()) >= 47.5f)
        rule.onAllNodesWithTag("route-step")[0].performClick()
        assertEquals("row:0", log.last())
        // The O1 footer stays pinned while the list scrolls.
        val start = node("nav-start")
        assertEquals(start.size.height.toFloat(), start.boundsInRoot.height, 1f)
        assertTrue(exists("Замчлал зөвхөн таны байршлаас эхэлнэ"))
    }

    @Test
    fun activatedRowFollowsItsRouteOnly() {
        val r = PreviewResult.Route(three, 1_790_000_000_000L, selected = 0)
        model.value = m(
            PreviewState(dest, TravelMode.CAR, false, device, r),
            expanded = true,
            points = PointsUi(step = StepFocus(three[0], 0, three[0].plan.steps[0].location, 1, collapse = false)),
        )
        show()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("route-steps"))
        // Another route selected: its rows are listed (AC 21) and no row of it is activated.
        model.value = m(
            PreviewState(dest, TravelMode.CAR, false, device, r.copy(selected = 1)),
            expanded = true,
            points = PointsUi(step = StepFocus(three[0], 0, three[0].plan.steps[0].location, 1, collapse = false)),
        )
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("route-steps"))
        val expected = TurnListModel.build(three[1].plan)
        assertEquals(expected.size, node("route-steps").config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount)
    }

    @Test
    fun aLongListIsComposedLazily() {
        val inputs = listOf(ManeuverInput("turn", "left"), ManeuverInput("turn", "right"), ManeuverInput("continue"))
        val steps = ArrayList<PlanStep>()
        steps += PlanStep.of(ManeuverInput("depart", bearingAfter = 0.0), LatLon(47.9, 106.9), "", 100.0, 10.0)
        for (i in 1 until 499) steps += PlanStep.of(inputs[i % 3], LatLon(47.9 + i * 1e-4, 106.9), "Гудамж $i", 100.0, 10.0)
        steps += PlanStep.of(ManeuverInput("arrive"), LatLon(47.95, 106.9), "", 0.0, 0.0)
        val long = ParsedRoute(GuidancePlan(0, steps, 50_000.0, 5_000.0, p1p3.plan.geometry, listOf(0.0, 0.0)), p1p3.native)
        model.value = m(PreviewState(dest, TravelMode.CAR, false, device, PreviewResult.Route(long, 1_790_000_000_000L)), expanded = true)
        show()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("route-steps"))
        val composed = rule.onAllNodesWithTag("route-step").fetchSemanticsNodes().size
        assertTrue("AC 25: $composed of 500 rows composed", composed in 1..40)
    }

    @Test
    fun coordinateCardInThePreviewOffersStartAndDestinationButtonsThatNeverMove() {
        val p = LatLon(47.9189, 106.9176)
        val feature = PhotonFeature(LatLon(47.919, 106.918), mapOf("osm_key" to "place", "osm_value" to "square", "name" to "Сүхбаатарын талбай"))
        val states = listOf(ReverseView.Pending, ReverseView.Loading, ReverseView.Place(feature), ReverseView.Empty, ReverseView.Offline, ReverseView.Unavailable, ReverseView.Error)
        val pv = PreviewState(dest, TravelMode.CAR, false, device, PreviewResult.Route(three, 1_790_000_000_000L))
        model.value = m(pv, card = p, reverse = states[0])
        show()
        assertTrue("the sheet is hidden while the card is open", tagged("route-preview").isEmpty())
        assertFalse("AC 6: no «Маршрут гаргах» in the preview", exists("Маршрут гаргах"))
        var tops: Pair<Float, Float>? = null
        for (s in states) {
            model.value = m(pv, card = p, reverse = s)
            rule.waitForIdle()
            val o = node("coord-set-origin")
            val d = node("coord-set-destination")
            for (n in listOf(o, d)) assertTrue("AC 6: ≥ 48 dp", dp(n.size.height.toFloat()) >= 47.5f)
            assertTrue("8 dp apart, start on top", dp(d.boundsInRoot.top - o.boundsInRoot.bottom) >= 7.5f)
            val now = o.boundsInRoot.top to d.boundsInRoot.top
            tops?.let { assertEquals("buttons moved in ${s.id}", it.first, now.first, 1f); assertEquals(it.second, now.second, 1f) }
            tops = tops ?: now
        }
        assertTrue(exists("Эхлэх цэг болгох"))
        assertTrue(exists("Очих газар болгох"))
        rule.onNodeWithTag("coord-set-origin").performClick()
        rule.onNodeWithTag("coord-set-destination").performClick()
        assertEquals(listOf("setOrigin", "setDestination"), log)
    }

    @Test
    fun startEditorOffersMyLocationFirstAndReusesTheResultsList() {
        val info = PlaceDisplay.info(PhotonFeature(LatLon(47.9213, 106.8948), mapOf("osm_key" to "amenity", "osm_value" to "place_of_worship", "name" to "Гандантэгчинлэн хийд")))
        val pv = PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(three, 1_790_000_000_000L))
        model.value = m(pv, points = PointsUi(editor = PointEditorState(PointSide.ORIGIN, 1)), fieldView = SearchView.Results(listOf(info)))
        show()
        assertTrue("the sheet is hidden while editing", tagged("route-preview").isEmpty())
        val editor = node("point-editor")
        assertEquals("origin", editor.config.getOrNull(EditingKey))
        assertTrue(exists("Эхлэх цэг"))
        val option = node("point-option-my-location")
        val results = node("point-results")
        assertTrue("AC 3: «Миний байршил» is the first option", option.boundsInRoot.bottom <= results.boundsInRoot.top + 0.5f)
        assertTrue(dp(option.size.height.toFloat()) >= 55.5f)
        rule.onNodeWithTag("point-option-my-location").performClick()
        assertEquals("myLocation", log.last())
        rule.onNode(hasText("Гандантэгчинлэн хийд")).performClick()
        assertEquals("editorResult:Гандантэгчинлэн хийд", log.last())
        // AC 3: a failure shows the NAV-005 message inside the card, the editor stays.
        model.value = m(pv, points = PointsUi(editor = PointEditorState(PointSide.ORIGIN, 1, MyLocationOption.Failed(LocationProblem.UNAVAILABLE))))
        rule.waitForIdle()
        assertTrue(exists("Байршил тодорхойлж чадсангүй"))
        assertTrue(tagged("point-editor").isNotEmpty())
    }

    @Test
    fun destinationEditorHasNoMyLocationOptionAndADeviceStartHidesIt() {
        val pv = PreviewState(dest, TravelMode.CAR, false, device, PreviewResult.Route(three, 1_790_000_000_000L))
        model.value = m(pv, points = PointsUi(editor = PointEditorState(PointSide.DESTINATION, 1)))
        show()
        assertEquals("destination", node("point-editor").config.getOrNull(EditingKey))
        assertTrue("AC 3: no «Миний байршил» in the destination list", tagged("point-option-my-location").isEmpty())
        model.value = m(pv, points = PointsUi(editor = PointEditorState(PointSide.ORIGIN, 2)))
        rule.waitForIdle()
        assertTrue("hidden while the start already is «Миний байршил»", tagged("point-option-my-location").isEmpty())
        assertTrue("the editor reports focus to the lock gate on open", log.contains("editorTap"))
    }

    @Test
    fun lockedEditorShowsTheLockCardAndKeepsMyLocationSelectable() {
        tapAllowed = false
        val lock = TypingLockState(engaged = true, overridden = false, cardVisible = true, engagement = 1)
        val pv = PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(three, 1_790_000_000_000L))
        model.value = m(pv, points = PointsUi(editor = PointEditorState(PointSide.ORIGIN, 1)), lock = lock)
        show()
        for (t in listOf("Хөдөлж байх үед бичих боломжгүй", "Би зорчигч", "Миний байршил")) assertTrue("AC 34: missing «$t»", exists(t))
        assertTrue("AC 34: read-only while locked", rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        val option = node("point-option-my-location")
        val card = node("typing-lock")
        assertTrue("the option card comes before the lock card (Q5)", option.boundsInRoot.bottom <= card.boundsInRoot.top + 0.5f)
        rule.onNodeWithTag("point-option-my-location").performClick()
        assertEquals("myLocation", log.last())
        rule.onNodeWithTag("typing-lock-close").performClick()
        assertEquals("dismissLock", log.last())
    }

    @Test
    fun fontScale200KeepsStartAndO1Whole() {
        scale.value = 2f
        model.value = m(PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(three, 1_790_000_000_000L)))
        show()
        val start = rule.onNodeWithTag("nav-start", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(start.size.height.toFloat(), start.boundsInRoot.height, 1f)
        val hint = node("route-origin-hint")
        assertEquals(hint.size.height.toFloat(), hint.boundsInRoot.height, 1f)
        assertTrue(start.boundsInRoot.bottom <= att().top + 0.5f)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h640dp")
    fun englishLabelsSwitchAndResultNamesStay() {
        model.value = m(
            PreviewState(Destination(LatLon(47.8858, 106.9173), null), TravelMode.CAR, false, device, PreviewResult.Route(three, 1_790_000_000_000L)),
            lang = Lang.EN,
        )
        show(Lang.EN)
        assertEquals("My location", state(node("route-origin")))
        assertEquals("Selected point", state(node("route-destination")))
        assertEquals("Start", desc(node("route-origin")))
        assertEquals("Swap start and destination", desc(node("route-swap")))
        model.value = m(PreviewState(dest, TravelMode.CAR, false, gandan, PreviewResult.Route(three, 1_790_000_000_000L)), lang = Lang.EN)
        rule.waitForIdle()
        assertEquals("D11: the result name stays", "Гандан хийд", state(node("route-origin")))
        assertTrue(exists("Navigation starts only from your location"))
        assertNull(rule.onAllNodes(hasText("Маршрут харах")).fetchSemanticsNodes().firstOrNull())
    }
}
