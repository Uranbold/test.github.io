package mn.navmn.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.i18n.Lang
import mn.navmn.app.pack.InstalledFile
import mn.navmn.app.pack.InstalledPack
import mn.navmn.app.pack.JobUi
import mn.navmn.app.pack.ManifestFacts
import mn.navmn.app.pack.PackKind
import mn.navmn.app.pack.PackOffer
import mn.navmn.app.pack.PackState
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.OfflineOfferSheet
import mn.navmn.app.ui.screens.OfflineSection
import mn.navmn.app.ui.screens.PackActions
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** NAV-022 O1 and O2 in Compose (AC 1, 2, 4, 36, 37, 39; screen spec F1–F3, F5, Copy) with the Mongolian resources. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class OfflinePackUiTest {
    @get:Rule val rule = createComposeRule()

    private val nb = ' '
    private val v = "20261004T193412Z"
    private val facts = ManifestFacts(true, 119_500_000, 337_100_000, 33_000_000, "https://127.0.0.1/odbl-1.0")
    private val installed = InstalledPack(
        mapOf(
            PackKind.TILES to InstalledFile(PackKind.TILES, v, "$v/basemap.pmtiles", 117_536_866, "t", "2026-09-26T20:21:03Z"),
            PackKind.ROUTING to InstalledFile(PackKind.ROUTING, v, "$v/routing.tar", 63_700_000, "r", "2026-10-04T10:00:00Z"),
            PackKind.SEARCH to InstalledFile(PackKind.SEARCH, v, "$v/search.sqlite", 20_400_000, "s", "2026-10-03T22:59:05Z"),
        ),
    )

    private val calls = ArrayList<String>()
    private val actions = PackActions(
        onDownload = { calls += "download" },
        onCancel = { calls += "cancel" },
        onDelete = { calls += "delete" },
        onOpenLicence = { calls += "licence:$it" },
    )

    private fun section(state: PackState) {
        val holder = mutableStateOf(state)
        rule.setContent {
            NavTheme(false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { OfflineSection(holder.value, Lang.MN, actions) }
            }
        }
    }

    @Test fun noPackShowsBenefitSizesAndTheDownloadButton() {
        section(PackState(true, InstalledPack.NONE, facts, JobUi.Idle))
        rule.onNodeWithText("Офлайн газрын зураг").assertIsDisplayed()
        rule.onNodeWithText("Интернэт холболтгүй үед ч газрын зураг, маршрут, хайлт ажиллана").assertIsDisplayed()
        rule.onNodeWithText("Татах хэмжээ: 120${nb}МБ").assertIsDisplayed()
        rule.onNodeWithText("Утсанд шаардлагатай зай: 338${nb}МБ").assertIsDisplayed()
        rule.onNodeWithTag("offline-download").performScrollTo().performClick()
        assertEquals(listOf("download"), calls)
        assertTrue(rule.onAllNodesWithTag("offline-delete").fetchSemanticsNodes().isEmpty())
    }

    @Test fun downloadingShowsTheStatusRowWithCancel() {
        section(PackState(true, InstalledPack.NONE, facts, JobUi.Downloading(42)))
        val desc = rule.onNodeWithTag("offline-status").fetchSemanticsNode().children.first().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("Татаж байна… 42%"), desc)
        rule.onNodeWithTag("offline-cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
        assertTrue(rule.onAllNodesWithTag("offline-download").fetchSemanticsNodes().isEmpty())
    }

    @Test fun installedShowsDatesSpaceDeleteAttributionAndLicence() {
        section(PackState(true, installed, facts.copy(downloadBytes = 0, requiredSpace = 0), JobUi.Idle))
        rule.onNodeWithText("Мэдээллийн огноо").assertIsDisplayed()
        rule.onNodeWithText("Газрын зураг: 2026-09-27").assertIsDisplayed() // Asia/Ulaanbaatar date of 20:21Z
        rule.onNodeWithText("Маршрут: 2026-10-04").assertIsDisplayed()
        rule.onNodeWithText("Хайлт: 2026-10-04").assertIsDisplayed()
        rule.onNodeWithText("Эзэлж буй зай: 202${nb}МБ").assertIsDisplayed()
        assertTrue("up to date: no «Шинэчлэх»", rule.onAllNodesWithTag("offline-update").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("© OpenStreetMap contributors").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("offline-licence").performScrollTo().performClick()
        assertEquals(listOf("licence:https://127.0.0.1/odbl-1.0"), calls)
    }

    @Test fun deleteAsksFirstAndCancelChangesNothing() {
        section(PackState(true, installed, facts.copy(downloadBytes = 0), JobUi.Idle))
        rule.onNodeWithTag("offline-delete").performScrollTo().performClick()
        rule.onNodeWithText("Офлайн газрын зургийг устгах уу?").assertIsDisplayed()
        rule.onNodeWithText("Цуцлах").performClick()
        assertTrue(calls.isEmpty())
        rule.onNodeWithTag("offline-delete").performScrollTo().performClick()
        rule.onNodeWithText("Устгах").performClick()
        assertEquals(listOf("delete"), calls)
    }

    @Test fun updateAvailableShowsItsSize() {
        section(PackState(true, installed, facts.copy(downloadBytes = 33_000_000), JobUi.Idle))
        rule.onNodeWithText("Татах хэмжээ: 33${nb}МБ").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("offline-update").performScrollTo().performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test fun firstLaunchOfferSitsAboveTheAttributionAndReportsItsChoice() {
        var shown = 0
        val result = ArrayList<Boolean>()
        rule.setContent {
            NavTheme(false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.fillMaxSize().testTag("map"))
                        OfflineOfferSheet(PackOffer.First(119_500_000, 337_100_000), Lang.MN, { shown++ }, { result += true }, { result += false })
                    }
                    AttributionStrip(showEsa = false)
                }
            }
        }
        rule.onNodeWithText("Монголын газрын зураг татах").assertIsDisplayed()
        rule.onNodeWithText("Татах хэмжээ: 120${nb}МБ").assertIsDisplayed()
        rule.onNodeWithTag("offline-offer-later").assertIsDisplayed()
        rule.onNodeWithTag("offline-offer-download").assertIsDisplayed()
        assertEquals("AC 2: the flag is stored when it shows", 1, shown)
        val sheet = rule.onNodeWithTag("offline-offer").fetchSemanticsNode().boundsInRoot
        val attribution = rule.onNodeWithTag("attribution").fetchSemanticsNode().boundsInRoot
        assertFalse("F1: R5 stays visible", sheet.overlaps(attribution))
        rule.onNodeWithTag("offline-offer-download").performClick()
        assertEquals(listOf(true), result)
    }
}
