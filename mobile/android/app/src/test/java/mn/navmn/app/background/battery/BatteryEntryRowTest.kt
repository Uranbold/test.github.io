package mn.navmn.app.background.battery

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-012 AC 26 (2026-10-04 wording), QA defect D4, screen spec H1 "Battery entry row": the collapsed one-line row
 * shows the B2 text on one line cut with «…» (not B1), and TalkBack reads the full B2 in the collapsed row and in the
 * expanded hint. Robolectric, 360×640 dp, native graphics so the text is really measured. Expected texts are the
 * glossary strings (B2).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class BatteryEntryRowTest {
    @get:Rule val rule = createComposeRule()

    private val b2Mn = "Батарей хэмнэх тохиргоо замчлалыг зогсоож болзошгүй. Утасны тохиргоонд батарейн хязгаарлалтыг унтраана уу."
    private val b2En = "Battery saving can stop navigation. Turn off battery restrictions in your phone settings."
    private val b1Mn = "Батарейн хязгаарлалт"

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun assertCollapsedRow(b2: String) {
        var expanded = 0
        rule.setContent {
            NavTheme(false) {
                // The sheet supplies 16 dp side margins (screen spec: full sheet width − 32 dp).
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { BatteryEntryRow(onExpand = { expanded++ }) }
            }
        }
        rule.waitForIdle()

        // Accessible name: the merged node of the row is one button whose text is the full B2, nothing else (AC 26).
        val row = rule.onNodeWithTag("battery-entry").fetchSemanticsNode()
        assertEquals(listOf(b2), row.texts())
        assertNull("icons are decorative", row.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Button, row.config.getOrNull(SemanticsProperties.Role))

        // Visible text: B2 on one line, cut with «…», never wrapping.
        val text = rule.onNode(hasText(b2), useUnmergedTree = true).fetchSemanticsNode()
        val layout = layoutOf(text)
        assertEquals("one line", 1, layout.lineCount)
        assertTrue("B2 does not fit 328 dp and must be cut with «…»", layout.isLineEllipsized(0))

        rule.onNodeWithTag("battery-entry").performClick()
        rule.waitForIdle()
        assertEquals("a tap anywhere on the row expands the sheet", 1, expanded)
    }

    @Test
    fun collapsedRowShowsB2OnOneLineAndReadsTheFullB2() {
        assertCollapsedRow(b2Mn)
        assertTrue("B1 is the S7 headline only", rule.onAllNodes(hasText(b1Mn), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "en-w360dp-h640dp")
    fun collapsedRowInEnglish() = assertCollapsedRow(b2En)

    @Test
    fun expandedHintReadsTheFullB2ThenCloseAndOpenSettings() {
        rule.setContent { NavTheme(false) { BatteryHintCard(onDismiss = {}, onOpenSettings = {}) } }
        rule.waitForIdle()
        val text = rule.onNode(hasText(b2Mn), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(b2Mn), text.texts())
        assertTrue("full hint wraps, never cut", !layoutOf(text).isLineEllipsized(layoutOf(text).lineCount - 1))
        assertEquals(listOf("Хаах"), rule.onNodeWithTag("battery-hint-close").fetchSemanticsNode().texts())
        assertEquals(listOf("Тохиргоо нээх"), rule.onNodeWithTag("battery-hint-settings").fetchSemanticsNode().texts())
    }
}
