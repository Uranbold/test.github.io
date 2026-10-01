package mn.navmn.app.resources

import mn.navmn.app.i18n.StringKey
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** NAV-005 AC 61: identical key sets, placeholders kept, glossary match, no hard-coded user-facing literals. */
class ResourcesTest {
    private val placeholder = Regex("\\{[a-z]+\\}")

    @Test
    fun mnAndEnKeySetsAreIdentical() {
        val mn = TestStrings.mn.filterValues { it.translatable }.keys
        val en = TestStrings.en.keys
        assertEquals("only in mn: ${mn - en}, only in en: ${en - mn}", mn, en)
    }

    @Test
    fun placeholdersAreTheSameInBothLanguages() {
        for ((k, v) in TestStrings.en) {
            val mn = TestStrings.mn.getValue(k).value
            assertEquals(k, placeholder.findAll(mn).map { it.value }.toSet(), placeholder.findAll(v.value).map { it.value }.toSet())
            assertTrue("$k uses String.format markers", !mn.contains("%") && !v.value.contains("%"))
        }
    }

    @Test
    fun stringKeyEnumMatchesTheResourceFile() {
        assertEquals(TestStrings.mn.keys, StringKey.entries.map { it.resName }.toSet())
    }

    @Test
    fun everyMongolianValueMatchesTheGlossary() {
        val script = File(TestStrings.repoRoot, "mobile/android/tools/check-glossary.mjs")
        val node = listOf("node", "/opt/node22/bin/node").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        Assume.assumeTrue("node not available", node != null)
        val p = ProcessBuilder(node, script.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals(out.lines().filter { it.contains("MISSING") }.joinToString("\n"), 0, p.exitValue())
    }

    /** Debug-only tooling lives in src/test (AC 72) and is excluded; main sources must have no UI literals. */
    @Test
    fun noHardCodedUserFacingStringsInKotlinSources() {
        val src = File(TestStrings.repoRoot, "mobile/android/app/src/main/java")
        val cyrillic = Regex("\"[^\"\\n]*[\u0400-\u04FF][^\"\\n]*\"")
        val uiLiteral = Regex("(Text|contentDescription\\s*=|label\\s*=|title\\s*=|placeholder\\s*=)\\s*\\(?\\s*\"[^\"\\n]*[A-Za-z][^\"\\n]*\"")
        val bad = ArrayList<String>()
        // Template interpolation of resource text ("$a · $b") is not a literal; lines marked "scan:data" hold OSM data
        // patterns (search name suffixes, ported from web/src/search/lexicon.json), never shown.
        val interpolation = Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_][A-Za-z0-9_]*")
        src.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("scan:data")) return@forEachIndexed
                val code = line.substringBefore("//").replace(interpolation, "")
                if (cyrillic.containsMatchIn(code)) bad += "${f.name}:${i + 1}: $line"
                if (uiLiteral.containsMatchIn(code)) bad += "${f.name}:${i + 1}: $line"
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }
}
