package mn.navmn.app.search.offline

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchView
import mn.navmn.app.search.reverse.ReverseView
import mn.navmn.app.support.TestStrings
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-023 AC 21, 23, 24, 29 (screen spec android-offline-pack F8, O7 `ind-results`, `ind-noresult`, `ind-card`): the
 * OF24 chip once per on-device results list (header row above the first result), after «Илэрц олдсонгүй» from the
 * device, and after «Ойролцоох газар» for a reverse answer from the device; nothing for gateway answers. TalkBack hears
 * «{count} илэрц олдлоо» then OF25 «Офлайн газрын зургаас». The chip never covers a result name, the search field or
 * the attribution.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class OfflineSearchIndicatorTest {
    @get:Rule val rule = createComposeRule()

    private val features = listOf(
        PhotonFeature(LatLon(47.9215, 106.8950), mapOf("name" to "Гандан хийд", "osm_key" to "amenity", "osm_value" to "place_of_worship", "district" to "Чингэлтэй дүүрэг")),
        PhotonFeature(LatLon(47.9205, 106.8975), mapOf("name" to "Гандангийн гудамж", "osm_key" to "highway", "osm_value" to "residential")),
    )
    private val items = features.map { PlaceDisplay.info(it) }
    private val model = mutableStateOf(m())

    private fun m(search: SearchView = SearchView.Closed, card: LatLon? = null, reverse: ReverseView? = null, query: String = "Гандан") =
        BrowseModel(Lang.MN, query, search, card, null, null, false, false, 0.0, false, reverse, TypingLockState(), false, 0)

    private val actions = BrowseActions(
        onQuery = {}, onClearSearch = {}, onResult = { _, _ -> }, onRetrySearch = {},
        onSettings = {}, onZoomIn = {}, onZoomOut = {}, onNorthUp = {}, onMyLocation = {}, onCardClose = {},
        onCardDirections = {}, onPreviewClose = {}, onMode = {}, onAvoid = {}, onPreviewRetry = {},
        onStart = {}, onOpenAppSettings = {}, onOpenLocationSettings = {}, onDismissMapProblem = {}, onRetryTiles = {}, onSheetHeight = {},
        onSelectRoute = {}, onSheetExpanded = {}, onReverseRetry = {},
        onSearchFieldTap = { true }, onLockedWhileTyping = {}, onDismissLock = {}, onPassenger = {},
        onCoordinateOption = {}, onCardBounds = { _: Rect?, _: Boolean -> },
    )

    private fun show(lang: Lang = Lang.MN) {
        rule.setContent {
            NavTheme(false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { BrowseOverlay(model.value.copy(lang = lang), TestStrings.of(lang), actions) }
                    AttributionStrip(showEsa = false)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun indicators() = rule.onAllNodesWithTag("offline-indicator", useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun onDeviceResultsShowTheChipOnceAndAnnounceTheCountThenOf25() {
        model.value = m(SearchView.Results(items, onDevice = true))
        show()
        assertEquals(1, indicators().size)
        val header = rule.onNodeWithTag("search-offline-header", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf("2 илэрц олдлоо, Офлайн газрын зургаас"), header.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(LiveRegionMode.Polite, header.config.getOrNull(SemanticsProperties.LiveRegion))
        // AC 24: above the first result, never over a result name, the field or the attribution.
        val chip = indicators().single().boundsInRoot
        val firstName = rule.onAllNodes(hasText("Гандан хийд"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("chip overlaps the first result", chip.bottom <= firstName.top + 0.5f)
        val field = rule.onNodeWithTag("search-bar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("chip overlaps the search field", !chip.overlaps(field))
        val att = rule.onNodeWithTag("attribution").fetchSemanticsNode().boundsInRoot
        assertTrue("chip overlaps the attribution", chip.bottom <= att.top)
    }

    @Test
    fun englishAnnouncementUsesTheSingular() {
        model.value = m(SearchView.Results(items.take(1), onDevice = true))
        show(Lang.EN)
        val header = rule.onNodeWithTag("search-offline-header", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf("1 result, From the offline map"), header.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun gatewayResultsShowNoChip() {
        model.value = m(SearchView.Results(items))
        show()
        assertTrue(indicators().isEmpty())
        assertTrue(rule.onAllNodesWithTag("search-offline-header", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        model.value = m(SearchView.NoResults)
        rule.waitForIdle()
        assertTrue(indicators().isEmpty())
    }

    @Test
    fun noResultsFromTheDeviceShowsTheChipAfterTheText() {
        model.value = m(SearchView.NoResultsOnDevice)
        show()
        assertEquals(1, indicators().size)
        val text = rule.onAllNodes(hasText(TestStrings.of(Lang.MN)[StringKey.SEARCH_NO_RESULTS]), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val chip = indicators().single().boundsInRoot
        assertTrue("chip after the text", chip.left >= text.right - 0.5f || chip.top >= text.bottom - 0.5f)
    }

    @Test
    fun coordinateCardMarksAReverseAnswerFromTheDevice() {
        val p = LatLon(47.9189, 106.9176)
        val f = PhotonFeature(p, mapOf("name" to "Сүхбаатарын талбай", "osm_key" to "place", "osm_value" to "square"))
        model.value = m(card = p, reverse = ReverseView.Place(f, onDevice = true), query = "")
        show()
        assertEquals(1, indicators().size)
        // One merged node: «Ойролцоох газар», then OF25, then the place.
        val area = rule.onNodeWithTag("coord-nearest").fetchSemanticsNode()
        val said = (area.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            area.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ")
        assertTrue(said, said.contains("Офлайн газрын зургаас"))
        model.value = m(card = p, reverse = ReverseView.Place(f), query = "")
        rule.waitForIdle()
        assertTrue("gateway answer: no chip", indicators().isEmpty())
        model.value = m(card = p, reverse = ReverseView.EmptyOnDevice, query = "")
        rule.waitForIdle()
        assertEquals(1, indicators().size)
    }
}
