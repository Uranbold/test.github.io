package mn.navmn.app.support

import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.PluralKey
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import java.io.File

/** The shipped string resources (res/values = mn, res/values-en = en), parsed for JVM tests. */
object TestStrings {
    val repoRoot: File = File(System.getProperty("nav.repoRoot") ?: "../../..").canonicalFile
    val resDir: File = File(repoRoot, "mobile/android/app/src/main/res")

    private val STRING = Regex("<string\\s+name=\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</string>")
    private val PLURALS = Regex("<plurals\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</plurals>")
    private val ITEM = Regex("<item\\s+quantity=\"([a-z]+)\"\\s*>([\\s\\S]*?)</item>")

    fun unescape(raw: String): String = raw
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\\\\(['\"@?\\\\])"), "$1")

    data class Entry(val value: String, val translatable: Boolean)

    fun parse(file: File): Map<String, Entry> =
        STRING.findAll(file.readText()).associate { m ->
            m.groupValues[1] to Entry(unescape(m.groupValues[3]), !m.groupValues[2].contains("translatable=\"false\""))
        }

    /** `<plurals>` resources: name → quantity → text (NAV-005-D4). */
    fun parsePlurals(file: File): Map<String, Map<String, String>> =
        PLURALS.findAll(file.readText()).associate { m ->
            m.groupValues[1] to ITEM.findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }

    val mn: Map<String, Entry> by lazy { parse(File(resDir, "values/strings.xml")) }
    val en: Map<String, Entry> by lazy { parse(File(resDir, "values-en/strings.xml")) }
    val mnPlurals: Map<String, Map<String, String>> by lazy { parsePlurals(File(resDir, "values/strings.xml")) }
    val enPlurals: Map<String, Map<String, String>> by lazy { parsePlurals(File(resDir, "values-en/strings.xml")) }

    fun plurals(lang: Lang): Map<String, Map<String, String>> = if (lang == Lang.MN) mnPlurals else mnPlurals + enPlurals

    fun map(lang: Lang): Map<String, String> {
        val base = mn.mapValues { it.value.value }
        return if (lang == Lang.MN) base else base + en.mapValues { it.value.value }
    }

    private val cache = HashMap<Lang, Strings>()

    fun of(lang: Lang): Strings = cache.getOrPut(lang) {
        val m = map(lang)
        val pl = plurals(lang)
        object : Strings {
            override fun get(key: StringKey): String = m[key.resName] ?: error("missing string ${key.resName} for $lang")
            override fun plural(key: PluralKey, one: Boolean): String =
                pl[key.resName]?.get(if (one) "one" else "other") ?: error("missing plurals ${key.resName} for $lang")
        }
    }
}
