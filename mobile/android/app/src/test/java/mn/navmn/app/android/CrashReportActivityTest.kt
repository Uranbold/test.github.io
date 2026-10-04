package mn.navmn.app.android

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import mn.navmn.app.BuildConfig
import mn.navmn.app.log.CrashLog
import mn.navmn.app.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Bug B-NAV019-01 diagnostic through the real Activity (debug build; the demo build sets the same flag): a stored crash
 * report is shown on the next launch instead of the map, with copy, share and «Хаах» (which deletes it). Runs at the
 * PO phone's API levels 28–30 and today's default.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "mn-w360dp-h640dp", sdk = [28, 30, Config.NEWEST_SDK])
class CrashReportActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val report = "app 0.1.0 (1)\nthread: main\n\njava.lang.ExceptionInInitializerError\n\tat mn.navmn.app.demo.DemoVariant.onApplicationCreate(DemoVariant.kt:79)"

    @Before
    fun setUp() {
        FakeLocation.reset()
        RecordingMapSurface.reset()
        hilt.inject()
    }

    @After
    fun tearDown() {
        scenario?.close()
        CrashLog.of(app).clear()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun storeReport() {
        // The location CrashLog.of uses (files/crash/); a fixed text keeps the assertions independent of JVM frames.
        File(app.filesDir, "crash").apply { mkdirs() }.resolve(CrashLog.FILE_NAME).writeText(report)
        assertEquals(report, CrashLog.of(app).read())
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theFlagIsOnInDebug() {
        assertTrue(BuildConfig.CRASH_DIAGNOSTICS)
    }

    @Test
    fun noReportMeansTheMapAsBefore() {
        launch()
        assertTrue(compose.onAllNodes(hasTestTag("crash-report")).fetchSemanticsNodes().isEmpty())
        assertNotNull("map composed", RecordingMapSurface.content)
    }

    @Test
    fun aStoredReportIsShownBeforeTheMapAndCloseDeletesIt() {
        storeReport()
        launch()
        assertTrue(exists("Алдаа гарлаа"))
        assertTrue(exists("DemoVariant.kt:79"))
        assertNull("the map is not created while the report is shown", RecordingMapSurface.content)

        compose.onNode(hasTestTag("crash-report-close") and hasText("Хаах", substring = true)).performClick()
        compose.waitForIdle()
        assertNull(CrashLog.of(app).read())
        assertTrue(compose.onAllNodes(hasTestTag("crash-report")).fetchSemanticsNodes().isEmpty())
        assertNotNull("map composed after closing", RecordingMapSurface.content)
    }

    @Test
    fun copyPutsTheReportOnTheClipboardAndShareOpensTheChooser() {
        storeReport()
        launch()
        compose.onNode(hasTestTag("crash-report-copy")).performClick()
        compose.waitForIdle()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(report, clip.getItemAt(0).text.toString())

        compose.onNode(hasTestTag("crash-report-share")).performClick()
        compose.waitForIdle()
        var started: Intent? = null
        scenario!!.onActivity { started = shadowOf(it).nextStartedActivity }
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        @Suppress("DEPRECATION")
        val send = started!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(report, send.getStringExtra(Intent.EXTRA_TEXT))
        // Sharing keeps the report until «Хаах».
        assertEquals(report, CrashLog.of(app).read())
    }
}
