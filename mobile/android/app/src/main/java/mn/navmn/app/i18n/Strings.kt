package mn.navmn.app.i18n

/**
 * Text lookup for code that has no Android Context (instruction, voice and format generators, the guidance core).
 * On the device it is backed by string resources of the requested language (ResourceStrings); in JVM tests by the
 * parsed res/values and res/values-en strings.xml files, so tests check the shipped resources, not a copy.
 */
interface Strings {
    operator fun get(key: StringKey): String

    /**
     * A `<plurals>` resource (NAV-005-D4). [one] selects the `one` item, otherwise `other`. The caller decides it from
     * the FORMATTED number ([Plurals.isOne]), never from a numeric cast (navigation-ux §4.1 "English singular / plural").
     */
    fun plural(key: PluralKey, one: Boolean): String
}

/** navigation-ux §4.1 / NAV-005 screen spec "Plural forms": the singular only when the formatted {n} is exactly "1". */
object Plurals {
    fun isOne(formatted: String): Boolean = formatted == "1"

    /** The [key] template for the formatted number [n], with `{n}` replaced. */
    fun fill(strings: Strings, key: PluralKey, n: String): String = Templates.fill(strings.plural(key, isOne(n)), "n" to n)
}

/**
 * Glossary templates keep their placeholders literally ({n}, {ordinal}, {first}, {second}, {days}, {distance}) so the
 * resource files can be compared with the glossary byte for byte (ADR-0009 §8). This replaces them; String.format is
 * never used on resource text.
 */
object Templates {
    fun fill(template: String, vararg values: Pair<String, String>): String {
        var out = template
        for ((name, value) in values) out = out.replace("{$name}", value)
        return out
    }
}
