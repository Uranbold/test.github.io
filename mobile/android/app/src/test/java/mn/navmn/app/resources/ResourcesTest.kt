package mn.navmn.app.resources

import mn.navmn.app.i18n.PluralKey
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
    /** String.format / getString(id, args) markers (%s, %1${'$'}s, %d, %.1f, …). A literal percent sign after a placeholder
     *  (NAV-022 OF8 «Татаж байна… {percent}%», glossary 2.7) is text, not a marker. */
    private val formatMarker = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")

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
            assertTrue("$k uses String.format markers", !formatMarker.containsMatchIn(mn) && !formatMarker.containsMatchIn(v.value))
        }
    }

    @Test
    fun stringKeyEnumMatchesTheResourceFile() {
        assertEquals(TestStrings.mn.keys, StringKey.entries.map { it.resName }.toSet())
    }

    /** NAV-005-D4: both languages define the same `<plurals>`, each with `one` and `other`, same placeholders. */
    @Test
    fun pluralsAreCompleteInBothLanguages() {
        assertEquals(PluralKey.entries.map { it.resName }.toSet(), TestStrings.mnPlurals.keys)
        assertEquals(TestStrings.mnPlurals.keys, TestStrings.enPlurals.keys)
        for ((k, mnItems) in TestStrings.mnPlurals) {
            val enItems = TestStrings.enPlurals.getValue(k)
            assertEquals("$k mn quantities", setOf("one", "other"), mnItems.keys)
            assertEquals("$k en quantities", setOf("one", "other"), enItems.keys)
            // Mongolian has no plural change (navigation-ux §4.1): both items carry the same glossary text.
            assertEquals("$k mn one/other differ", mnItems["one"], mnItems["other"])
            for (v in mnItems.values + enItems.values) {
                // NAV-023: the results count keeps the glossary's own placeholder «{count} илэрц олдлоо».
                val expected = if (k == PluralKey.SEARCH_RESULTS_COUNT.resName) "{count}" else "{n}"
                assertEquals(k, setOf(expected), placeholder.findAll(v).map { it.value }.toSet())
                assertTrue("$k uses String.format markers", !v.contains("%"))
            }
        }
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
