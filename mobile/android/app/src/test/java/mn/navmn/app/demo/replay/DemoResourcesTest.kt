package mn.navmn.app.demo.replay

import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NAV-019 AC 5, 13, 38, 41 (ADR-0016 §2, §3, §12): demo strings only from the glossary with identical mn/en key sets, no
 * hard-coded UI literals in the demo or replay sources, no demo code referenced from src/main, no platform location API
 * in the replay, and the manifest overlay as designed.
 */
class DemoResourcesTest {
    private val app = File(TestStrings.repoRoot, "mobile/android/app/src")
    private val demoRes = File(app, "demo/res")

    @Test
    fun demoKeySetsAreIdenticalAndTheValuesAreW1AndW2() {
        val mn = TestStrings.parse(File(demoRes, "values/strings.xml"))
        val en = TestStrings.parse(File(demoRes, "values-en/strings.xml"))
        assertEquals(mn.keys, en.keys)
        assertEquals("Туршилтын горим", mn.getValue("demo_mode").value) // scan:data
        assertEquals("Түр зогсоох", mn.getValue("demo_pause").value) // scan:data
        assertEquals("Demo mode", en.getValue("demo_mode").value)
        assertEquals("Pause", en.getValue("demo_pause").value)
        assertTrue("demo keys never shadow main keys", (mn.keys intersect TestStrings.mn.keys).isEmpty())
    }

    @Test
    fun everyDemoMongolianValueMatchesTheGlossary() {
        val script = File(TestStrings.repoRoot, "mobile/android/tools/check-glossary.mjs")
        val node = listOf("node", "/opt/node22/bin/node").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        Assume.assumeTrue("node not available", node != null)
        val p = ProcessBuilder(node, script.path, "--strings", File(demoRes, "values/strings.xml").path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals(out, 0, p.exitValue())
    }

    @Test
    fun noHardCodedUserFacingStringsInDemoOrReplaySources() {
        val cyrillic = Regex("\"[^\"\\n]*[\u0400-\u04FF][^\"\\n]*\"")
        val uiLiteral = Regex("(Text|contentDescription\\s*=|label\\s*=|title\\s*=|placeholder\\s*=)\\s*\\(?\\s*\"[^\"\\n]*[A-Za-z][^\"\\n]*\"")
        val interpolation = Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_][A-Za-z0-9_]*")
        val bad = ArrayList<String>()
        for (dir in listOf(File(app, "demo/java"), File(app, "replay/java"))) {
            dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//").replace(interpolation, "")
                    if (cyrillic.containsMatchIn(code) || uiLiteral.containsMatchIn(code)) bad += "${f.name}:${i + 1}: $line"
                }
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    /** ADR-0016 §2–3, AC 5: src/main knows only the generic seam; no demo package, no BuildConfig demo branch. */
    @Test
    fun mainSourcesNeverReferenceDemoCode() {
        val bad = ArrayList<String>()
        File(app, "main/java").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("mn.navmn.app.demo") || line.contains("BuildConfig.DEMO")) bad += "${f.name}:${i + 1}: $line"
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
        // src/replay is pure Kotlin: no Android types and no Hilt modules (it is on the testDebug classpath).
        File(app, "replay/java").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            assertTrue("${f.name}: Android type in src/replay", !Regex("import android\\.").containsMatchIn(text))
            assertTrue("${f.name}: Hilt module in src/replay", !text.contains("@Module") && !text.contains("@InstallIn"))
        }
    }

    /** AC 13: the demo never registers a platform location request or reads a last known position. */
    @Test
    fun replayAndDemoSourcesUseNoPlatformLocationApi() {
        val api = Regex("requestLocationUpdates|getLastKnownLocation|getCurrentLocation|FusedLocation|requestSingleUpdate|addTestProvider")
        for (dir in listOf(File(app, "demo/java"), File(app, "replay/java"))) {
            dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                assertTrue("${f.name} uses a platform location API", !api.containsMatchIn(f.readText()))
            }
        }
    }

    /** ADR-0016 §2, §4.5: launcher label «Туршилтын горим» and WAKE_LOCK only in the demo overlay. */
    @Test
    fun demoManifestOverlay() {
        val overlay = File(app, "demo/AndroidManifest.xml").readText()
        assertTrue(overlay.contains("android:label=\"@string/demo_mode\"") && overlay.contains("tools:replace=\"android:label\""))
        assertTrue(Regex("WAKE_LOCK\"\\s+tools:node=\"replace\"").containsMatchIn(overlay))
        val main = File(app, "main/AndroidManifest.xml").readText()
        assertTrue("main keeps the WAKE_LOCK removal", Regex("WAKE_LOCK\"\\s+tools:node=\"remove\"").containsMatchIn(main))
    }
}
