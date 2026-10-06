package mn.navmn.app.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.i18n.Lang
import mn.navmn.app.pack.PackKind
import mn.navmn.app.settings.ThemeChoice
import mn.navmn.app.ui.screens.LicencesOverlay
import mn.navmn.app.ui.screens.SettingsSheet
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-005 section P (AC 88, 89, 91–95; screen spec android-licences.md) at composable level on 360×640 dp, from the real
 * generated debug assets: the S7 row; S9 block 1 visible without scrolling; the pack block only with installed files;
 * TalkBack row names; S10 facts, copyright lines and the full text paragraph by paragraph; Back from S10 to S9 and from
 * S9 to the caller; the error row for a damaged index. No network type is involved (assets only, AC 89).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class LicencesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var closed = 0

    private fun show(packDates: List<Pair<PackKind, String>> = emptyList(), assets: mn.navmn.app.licences.LicenceAssets? = null) {
        rule.setContent {
            NavTheme(false) {
                if (assets == null) LicencesOverlay(packDates, onClose = { closed++ }) else LicencesOverlay(packDates, onClose = { closed++ }, assets = assets)
            }
        }
        rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("licence-entry-ferrostar")).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(hasTestTag("licences-error")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun s7HasTheLicencesRowLastAndItOpensThePage() {
        var opened = 0
        rule.setContent {
            NavTheme(false) {
                SettingsSheet(ThemeChoice.DAY, Lang.MN, true, {}, {}, {}, {}, onOpenLicences = { opened++ })
            }
        }
        // The partially expanded sheet clips the end of S7 here; the Activity test opens it by touch after scrolling.
        val row = rule.onNodeWithTag("licences-row")
        row.assertExists()
        assertTrue(row.fetchSemanticsNode().size.height >= with(rule.density) { 48.dp.roundToPx() })
        rule.onNodeWithText("Лиценз").assertExists()
        row.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun listShowsTheOsmBlockFirstWithoutScrollingAndTheEntries() {
        show()
        rule.onNodeWithTag("licences-list").assertIsDisplayed()
        rule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        rule.onNodeWithText("Газрын зургийн мэдээлэл OpenStreetMap төслөөс авсан бөгөөд Open Database License (ODbL) 1.0 лицензтэй.").assertIsDisplayed()
        rule.onNodeWithTag("licences-odbl").assertIsDisplayed()
        rule.onNodeWithTag("licences-ccby").assertIsDisplayed()
        // AC 92: the whole block 1 is on the first screen at 360×640 / 100 %.
        val block = rule.onNodeWithTag("licences-notice").fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag("licences-list").fetchSemanticsNode().boundsInRoot
        assertTrue("block 1 bottom ${block.bottom} within the screen ${root.bottom}", block.bottom <= root.bottom)
        assertTrue(!exists("licences-pack"))
        // AC 95: each row is one node «{name}, {version}, {licence name}», role button, ≥ 48 dp.
        val row = rule.onNodeWithTag("licence-entry-ferrostar").fetchSemanticsNode()
        // The licence names of every part, the component's own first (the Rust crates in libferrostar.so follow, ADR-0017 A5 §1).
        assertEquals(listOf("Ferrostar, 0.57.0, BSD-3-Clause, MIT, Apache-2.0, ISC, Zlib, MPL-2.0, MIT AND Apache-2.0 WITH LLVM-exception"),
            row.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(row.config.contains(SemanticsActions.OnClick))
        assertTrue(row.size.height >= with(rule.density) { 48.dp.roundToPx() })
        // Families with many versions omit the version (UX P3).
        rule.onNodeWithTag("licences-list").performScrollToNode(hasTestTag("licence-entry-androidx"))
        assertEquals(listOf("AndroidX and Jetpack Compose, Apache-2.0, BSD-3-Clause"), rule.onNodeWithTag("licence-entry-androidx").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        rule.onNodeWithTag("licences-list").performScrollToNode(hasText("Фонт, дүрс"))
        rule.onNodeWithTag("licences-list").performScrollToNode(hasTestTag("licence-entry-map-icons"))
        // Headings are headings (AC 95).
        val headings = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().size
        assertTrue(headings >= 2)
    }

    @Test
    fun packBlockShowsOnlyWithInstalledFiles() {
        show(packDates = listOf(PackKind.TILES to "2026-09-07T00:00:00Z", PackKind.SEARCH to "2026-09-08T00:00:00Z"))
        rule.onNodeWithTag("licences-pack").assertIsDisplayed()
        rule.onNodeWithText("Офлайн газрын зураг, маршрут, хайлтын мэдээлэл нь OpenStreetMap төслийн мэдээллээс бэлтгэсэн бөгөөд ижил лицензтэй.").assertExists()
        rule.onNodeWithText("Газрын зураг: 2026-09-07").assertExists()
        rule.onNodeWithText("Хайлт: 2026-09-08").assertExists()
        assertTrue(rule.onAllNodes(hasText("Маршрут: ", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun detailShowsFactsCopyrightAndTheFullTextAndBackReturns() {
        show()
        rule.onNodeWithTag("licence-entry-valhalla-mobile").performClick()
        rule.waitUntil(3_000) { exists("licence-paragraph") }
        rule.onNodeWithTag("licences-detail").assertIsDisplayed()
        rule.onNodeWithTag("licence-name").assertIsDisplayed()
        rule.onNodeWithText("valhalla-mobile").assertExists()
        rule.onNodeWithText("Хувилбар 0.6.3 · MIT · BSD-3-Clause · Apache-2.0 · BSL-1.0 · BSD-2-Clause").assertIsDisplayed()
        // Entry copyright block first, then again above the valhalla-mobile text part.
        rule.onAllNodesWithText("© 2024 Adventure Consortium Inc (dba Rallista)").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("© 2018 Valhalla contributors").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Лицензийн бичвэр").assertExists()
        // The MIT text, paragraph by paragraph; the artifact list at the end.
        rule.onNodeWithTag("licences-detail").performScrollToNode(hasText("Permission is hereby granted", substring = true))
        rule.onNodeWithTag("licences-detail").performScrollToNode(hasText("io.github.rallista:valhalla-mobile · 0.6.3 · MIT"))
        // Back (the screen's control, named «Хаах») → S9; again → the caller.
        val back = rule.onNodeWithTag("licences-back", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Хаах")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // The enterAlways bar has scrolled away while reading down (UX P1), so the control is activated by its action.
        rule.onNodeWithTag("licences-back").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("licences-list").assertIsDisplayed()
        assertEquals(0, closed)
        rule.onNodeWithTag("licences-back").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, closed)
        assertTrue(back.size.width >= with(rule.density) { 48.dp.roundToPx() })
    }

    @Test
    fun odbLRowOpensTheBundledOdbLText() {
        show()
        rule.onNodeWithTag("licences-odbl").performClick()
        rule.waitUntil(3_000) { exists("licence-paragraph") }
        rule.onNodeWithText("OpenStreetMap").assertExists()
        rule.onNodeWithText("© OpenStreetMap contributors").assertExists()
        rule.onNodeWithTag("licences-detail").performScrollToNode(hasText("Open Database License", substring = true))
    }

    @Test
    fun damagedIndexShowsTheErrorRowAndKeepsTheOsmBlock() {
        val broken = mn.navmn.app.licences.LicenceAssets { throw java.io.IOException("damaged") }
        show(assets = broken)
        rule.onNodeWithTag("licences-error").assertIsDisplayed()
        rule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        rule.onNodeWithText("Дахин оролдох").assertIsDisplayed()
    }
}
