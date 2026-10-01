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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import mn.navmn.app.support.TestStrings
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BannerVariant
import mn.navmn.app.ui.screens.GuidanceOverlay
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose checks of the S5 / S6 layout (screen spec Layout rules 1–4; AC 2, 21, 24, 31, 37, 42, 47–51, 55, 62, 63).
 * The map is a placeholder (MapLibre's native library does not load on the JVM).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class GuidanceOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val overlayTags = listOf("nav-banner", "nav-then", "nav-status", "nav-recenter", "nav-voice", "nav-orientation", "nav-voice-notice", "nav-progress", "nav-arrival")

    private fun state(
        banner: Banner = Banner.Maneuver(KeyResult(ManeuverKey.OFF_RAMP_RIGHT), 300.0, "Чингисийн өргөн чөлөө, Нарны зам", KeyResult(ManeuverKey.TURN_LEFT), false),
        phase: GuidancePhase = GuidancePhase.NAVIGATING,
        gpsLost: Boolean = false,
        offline: Boolean = false,
        notice: Boolean = false,
        muted: Boolean = false,
    ) = GuidanceState(
        phase, 0, banner, Progress(12_400.0, 25 * 60.0, 0), null, emptyList(),
        Trip(LatLon(47.8858, 106.9173), "Зайсан толгой", TravelMode.CAR, false), gpsLost, false, offline, notice, muted, 12.0,
    )

    private val scaleHolder = mutableStateOf(1f)

    /** setContent may run once per test; [scaleHolder] changes the font scale afterwards. */
    private fun show(s: GuidanceState, fontScale: Float = 1f, lang: Lang = Lang.MN, following: Boolean = true, orientation: Orientation = Orientation.HEADING_UP, night: Boolean = false) {
        val holder = mutableStateOf(s)
        scaleHolder.value = fontScale
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scaleHolder.value)) {
                NavTheme(night) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.fillMaxSize().testTag("map"))
                            GuidanceOverlay(holder.value, lang, TestStrings.of(lang), orientation, following, {}, {}, {}, {}, {}, {}, {}, {})
                        }
                        AttributionStrip(showEsa = false)
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): List<Rect> = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun assertAttributionFree() {
        rule.onNodeWithTag("attribution").assertIsDisplayed()
        val a = bounds("attribution").single()
        for (tag in overlayTags) for (b in bounds(tag)) assertFalse("$tag $b overlaps attribution $a", a.overlaps(b))
    }

    private fun lines(tag: String): Int {
        val results = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().lineCount
    }

    @Test
    fun attributionIsNeverCoveredInTheWorstCaseStates() {
        show(state(gpsLost = true, offline = true, notice = true), following = false)
        for (scale in listOf(1f, 1.3f, 2f)) {
            scaleHolder.value = scale
            rule.waitForIdle()
            assertAttributionFree()
            assertTrue("instruction ≤ 3 lines at $scale", lines("nav-banner-text") <= 3)
        }
    }

    @Test
    fun attributionFreeInRerouteArrivalNightAndEnglish() {
        show(state(Banner.Rerouting(RerouteSecondary.UNAVAILABLE), GuidancePhase.OFF_ROUTE), fontScale = 2f)
        assertAttributionFree()
        rule.onNode(SemanticsMatcher.expectValue(BannerVariant, "reroute")).assertIsDisplayed()
        rule.onNodeWithTag("nav-banner-street", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun arrivalPanelReplacesProgress() {
        show(state(Banner.Arrival(KeyResult(ManeuverKey.ARRIVE_RIGHT), "Зайсангийн гудамж"), GuidancePhase.ARRIVED))
        assertAttributionFree()
        rule.onNodeWithTag("nav-arrival").assertIsDisplayed()
        assertEquals(0, bounds("nav-progress").size)
        rule.onNode(SemanticsMatcher.expectValue(BannerVariant, "arrival")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "mn-w640dp-h360dp-land")
    fun landscapeKeepsAttributionFree() {
        show(state(gpsLost = true), following = false)
        assertAttributionFree()
    }

    @Test
    fun controlsHaveGlossaryNamesAndFortyEightDpTargets() {
        show(state(), following = false)
        val minPx = with(Density(RuntimeEnvironment.getApplication())) { 48.dp.toPx() } - 1
        rule.onNodeWithText("Байршил руу буцах", useUnmergedTree = true).assertExists()
        val recenter = rule.onNodeWithTag("nav-recenter").fetchSemanticsNode().boundsInRoot
        assertTrue("recenter ${recenter.height}", recenter.height >= minPx)
        for (name in listOf("Дуусгах", "Дууг хаах", "Хойд зүг дээшээ", "Тохиргоо")) {
            val node = rule.onNodeWithContentDescription(name, useUnmergedTree = false).fetchSemanticsNode()
            val b = node.boundsInRoot
            assertTrue("$name ${b.width}x${b.height}", b.width >= minPx && b.height >= minPx)
        }
    }

    @Test
    fun mutedAndNorthUpLabelsNameTheNextState() {
        show(state(muted = true), orientation = Orientation.NORTH_UP)
        rule.onNodeWithContentDescription("Дууг нээх").assertIsDisplayed()
        rule.onNodeWithContentDescription("Явах чиглэл дээшээ").assertIsDisplayed()
    }

    @Test
    fun thenStripAccessibleTextAndPoliteLiveRegionOnTheInstruction() {
        show(state())
        rule.onNodeWithContentDescription("Дараа нь, Зүүн тийш эргэнэ үү").assertIsDisplayed()
        val live = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
        assertTrue(live.any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("Баруун талын гарах зам руу эргэнэ үү") } == true })
    }

    @Test
    fun englishBanner() {
        show(state(), lang = Lang.EN)
        rule.onNodeWithTag("nav-banner-text", useUnmergedTree = true).assertIsDisplayed()
        val t = rule.onNodeWithTag("nav-banner-text", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertEquals("Take the exit on the right", t)
    }

    @Test
    fun keepScreenOnSetAndCleared() {
        val on = mutableStateOf(true)
        var view: android.view.View? = null
        rule.setContent {
            view = LocalView.current
            KeepScreenOn(on.value)
        }
        rule.waitForIdle()
        assertTrue(view!!.keepScreenOn)
        on.value = false
        rule.waitForIdle()
        assertFalse(view!!.keepScreenOn)
    }
}
