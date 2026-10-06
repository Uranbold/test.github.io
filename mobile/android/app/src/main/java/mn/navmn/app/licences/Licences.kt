package mn.navmn.app.licences

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * NAV-005 section P (AC 88–98, D195): the licences screen's data. Generated at build time by
 * `tools/gen-third-party-notices.py` from the variant's resolved runtime classpath and the repository's licence files
 * into the APK assets `licences/index.json` and `licences/texts/<sha256>.txt` (Gradle `generate<Variant>LicenceAssets`).
 * Nothing here is written by hand and nothing is translated (glossary LC9): names, versions, licence names, copyright
 * lines and texts are shown as generated. Read from the APK only: 0 network requests (AC 89).
 */
@Serializable
data class LicenceIndex(
    val schema: Int,
    val variant: String,
    val data: LicenceData,
    val software: List<LicenceEntry>,
    val fonts: List<LicenceEntry>,
    /** Asset path → SHA-256 of every distinct licence text (each stored once, AC 91, 97). */
    val texts: Map<String, String>,
) {
    fun entry(id: String): LicenceEntry? = (software + fonts).firstOrNull { it.id == id }
}

/** The map-data block (AC 92): the ODbL 1.0 text for OpenStreetMap and the CC BY 4.0 text for ESA WorldCover. */
@Serializable
data class LicenceData(val odbl: DataLicence, val ccby: DataLicence)

@Serializable
data class DataLicence(val name: String, val licence: String, val text: String)

/** One row of S9 and its detail page S10 (UX spec L5, P3, P4). */
@Serializable
data class LicenceEntry(
    val id: String,
    val name: String,
    /** null for families with many versions (AndroidX, Kotlin): the row then shows the licence names only (UX P3). */
    val version: String? = null,
    val licences: List<String>,
    val copyright: List<String>,
    val parts: List<LicencePart>,
    /** Every shipped artifact of this entry with its version and licence name (AC 90: 100 % on screen). */
    val artifacts: List<LicenceArtifact> = emptyList(),
)

@Serializable
data class LicencePart(
    val title: String,
    val licence: String,
    val copyright: List<String>,
    /** Asset path of the full licence text. */
    val text: String,
    /** Asset path of an upstream NOTICE file shown after the text (Apache-2.0, AC 91), or null. */
    val notice: String? = null,
)

@Serializable
data class LicenceArtifact(val coordinate: String, val version: String, val licence: String)

/** Reads the generated assets. [open] is `AssetManager::open` on the device and a file reader in JVM tests. */
class LicenceAssets(private val open: (String) -> InputStream) {
    /** The index, or null when it is missing or damaged (a damaged APK: the screen shows its error row). Never throws. */
    fun index(): LicenceIndex? = runCatching { open(INDEX).use { json.decodeFromString(LicenceIndex.serializer(), it.readBytes().decodeToString()) } }.getOrNull()

    /** One licence text as stored (byte-identical to the repository file, AC 91), or null. Never throws. */
    fun text(path: String): String? = runCatching { open(path).use { it.readBytes().decodeToString() } }.getOrNull()

    companion object {
        const val INDEX = "licences/index.json"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Display paragraphs of a licence text (UX P4: one lazy item per paragraph, so a 25 KB text is never one layout
         * pass). Blank lines separate paragraphs; inside a paragraph, a hard-wrapped line is joined to the previous one,
         * while a line that starts a clause (indented, a list marker or a number) keeps its own line. Display only:
         * the stored text is untouched.
         */
        fun paragraphs(text: String): List<String> {
            val clause = Regex("^(\\s+|[-*•]\\s|\\(?[0-9a-zA-Z]{1,3}[.)]\\s|#)")
            return text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n+")).mapNotNull { block ->
                val lines = block.split('\n').filter { it.isNotBlank() }
                if (lines.isEmpty()) return@mapNotNull null
                val sb = StringBuilder(lines.first().trimEnd())
                for (line in lines.drop(1)) {
                    if (clause.containsMatchIn(line)) sb.append('\n').append(line.trimEnd()) else sb.append(' ').append(line.trim())
                }
                sb.toString()
            }
        }
    }
}
