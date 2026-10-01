package mn.navmn.app.support

import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import java.io.File

/** The shipped string resources (res/values = mn, res/values-en = en), parsed for JVM tests. */
object TestStrings {
    val repoRoot: File = File(System.getProperty("nav.repoRoot") ?: "../../..").canonicalFile
    val resDir: File = File(repoRoot, "mobile/android/app/src/main/res")

    private val STRING = Regex("<string\\s+name=\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</string>")

    fun unescape(raw: String): String = raw
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\\\\(['\"@?\\\\])"), "$1")

    data class Entry(val value: String, val translatable: Boolean)

    fun parse(file: File): Map<String, Entry> =
        STRING.findAll(file.readText()).associate { m ->
            m.groupValues[1] to Entry(unescape(m.groupValues[3]), !m.groupValues[2].contains("translatable=\"false\""))
        }

    val mn: Map<String, Entry> by lazy { parse(File(resDir, "values/strings.xml")) }
    val en: Map<String, Entry> by lazy { parse(File(resDir, "values-en/strings.xml")) }

    fun map(lang: Lang): Map<String, String> {
        val base = mn.mapValues { it.value.value }
        return if (lang == Lang.MN) base else base + en.mapValues { it.value.value }
    }

    private val cache = HashMap<Lang, Strings>()

    fun of(lang: Lang): Strings = cache.getOrPut(lang) {
        val m = map(lang)
        Strings { key: StringKey -> m[key.resName] ?: error("missing string ${key.resName} for $lang") }
    }
}
