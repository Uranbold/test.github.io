package mn.navmn.app.i18n

/**
 * Text lookup for code that has no Android Context (instruction, voice and format generators, the guidance core).
 * On the device it is backed by string resources of the requested language (ResourceStrings); in JVM tests by the
 * parsed res/values and res/values-en strings.xml files, so tests check the shipped resources, not a copy.
 */
fun interface Strings {
    operator fun get(key: StringKey): String
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
