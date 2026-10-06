package mn.navmn.app.search.offline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-023 AC 2, AC 3, R5 (task SM2): the app's fold / clean / skeleton / words against the shared normalisation vectors
 * that the search DB builder's tests read too: `backend/pack/vectors/search-normalisation.v1.json` (the file the backend
 * published for AC 3; the story names a QA path in `tests/`, see the handoff). The story's AC 3 groups are checked from
 * the story text as well, so they hold even if the shared file moves. Every row must pass.
 */
class SearchNormalisationVectorsTest {
    private val file = Fixtures.repoFile(SHARED)
    private val root: JsonObject by lazy { Json.parseToJsonElement(file.readText()).jsonObject }

    private fun rows(name: String) = root.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun storyAc3GroupsGiveOneSkeletonEach() {
        val bad = AC3.flatMap { (forms, expected) -> forms.filter { SearchText.skeleton(it) != expected }.map { "«$it» → «${SearchText.skeleton(it)}», expected «$expected»" } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun sharedFileIsVersion1ForSchema1() {
        assertTrue("shared vector file missing: $SHARED", file.isFile)
        assertEquals(1, root.getValue("vectors_version").jsonPrimitive.int)
        assertEquals(1, root.getValue("search_schema").jsonPrimitive.int)
        for (s in listOf("fold", "clean", "skeleton_groups", "skeleton_distinct", "skeleton", "words")) assertTrue("$s empty", rows(s).isNotEmpty())
    }

    @Test
    fun sharedFoldCleanSkeletonAndWords() {
        val bad = ArrayList<String>()
        for (r in rows("fold")) if (SearchText.fold(r.s("in")) != r.s("out")) bad += "fold ${r.s("id")}: «${SearchText.fold(r.s("in"))}»"
        for (r in rows("clean")) if (SearchText.clean(r.s("in")) != r.s("out")) bad += "clean ${r.s("id")}: «${SearchText.clean(r.s("in"))}»"
        for (r in rows("skeleton")) if (SearchText.skeleton(r.s("in")) != r.s("out")) bad += "skeleton ${r.s("id")}: «${SearchText.skeleton(r.s("in"))}»"
        for (r in rows("words")) {
            val want = r.getValue("out").jsonArray.map { it.jsonPrimitive.content }
            if (SearchText.words(r.s("in")) != want) bad += "words ${r.s("id")}: ${SearchText.words(r.s("in"))}"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun sharedSkeletonGroups() {
        val bad = ArrayList<String>()
        for (g in rows("skeleton_groups")) {
            val want = g.s("skeleton")
            for (f in g.getValue("forms").jsonArray.map { it.jsonPrimitive.content }) {
                if (SearchText.skeleton(f) != want) bad += "${g.s("id")} «$f» → «${SearchText.skeleton(f)}», expected «$want»"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * D01–D03: the skeleton keeps Latin "u" for «ө» apart (as the builder); the engine's Latin vowel variants (task SM3
     * step 3) must reach the Cyrillic skeleton. D04 is the edit-distance case (1 edit), checked by the engine test.
     */
    @Test
    fun sharedDistinctPairsAndTheLatinVowelVariants() {
        val bad = ArrayList<String>()
        for (d in rows("skeleton_distinct")) {
            val a = SearchText.skeleton(d.s("a"))
            val b = SearchText.skeleton(d.s("b"))
            if (a != d.s("a_skeleton") || b != d.s("b_skeleton")) bad += "${d.s("id")}: «$a» / «$b»"
        }
        for (id in listOf("D01", "D02", "D03")) {
            val d = rows("skeleton_distinct").first { it.s("id") == id }
            val variants = d.s("b_skeleton").split(' ').map { SearchText.latinVowelVariants(it) }
            if (variants.size != 1 || d.s("a_skeleton") !in variants[0]) bad += "$id: ${d.s("a_skeleton")} not in ${variants.flatten()}"
        }
        assertEquals(1, Levenshtein.distance("enhtaivni", "enhtaivani", 2))
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    companion object {
        const val SHARED = "backend/pack/vectors/search-normalisation.v1.json"

        /** NAV-023 AC 3, from the story text. */
        val AC3: List<Pair<List<String>, String>> = listOf(
            listOf("Сүхбаатар", "Сухбаатар", "Sukhbaatar", "Suhbaatar", "Sükhbaatar", "SUKHBAATAR") to "suhbatar", // scan:data
            listOf("Зайсан", "Zaisan", "Zaysan") to "zaisan", // scan:data
            listOf("Баянзүрх", "Bayanzurkh", "Bayanzurh") to "baianzurh", // scan:data
            listOf("Чингэлтэй", "Chingeltei") to "chingeltei", // scan:data
            listOf("Их дэлгүүр", "Ikh delguur") to "ih delgur", // scan:data
            listOf("Эрдэнэт", "Erdenet") to "erdenet", // scan:data
            listOf("Гандан", "Gandan") to "gandan", // scan:data
        )
    }
}
