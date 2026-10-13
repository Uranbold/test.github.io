package mn.navmn.app.voice.pack

import android.content.Context
import java.security.MessageDigest

/** Take of a segment (pack-format §2.1): `nonFinal` only for a `{first}` instruction before «дараа нь» (ADR-0010 §2). */
enum class Take(val id: String) {
    FINAL("final"),
    NON_FINAL("nonFinal"),
    ;

    companion object {
        fun of(id: String): Take? = entries.firstOrNull { it.id == id }
    }
}

/** One clip of a prompt, identified by its inventory key and take. */
data class Segment(val key: String, val take: Take) {
    /** pack-format §7 prompt list token, e.g. `instr.turn.right:nonFinal`. */
    val token: String get() = "$key:${take.id}"

    companion object {
        /** pack-format §7: space-separated tokens, "-" for an empty list. */
        fun tokens(segments: List<Segment>): String = if (segments.isEmpty()) "-" else segments.joinToString(" ") { it.token }
    }
}

/**
 * NAV-016 M1: the canonical voice inventory (pack-format §2.3) the app was built with. The build generates the asset
 * `voice/inventory.tsv` (exactly the canonical bytes, from glossary-c3-spoken.tsv and the architect's inventory.tsv)
 * and `BuildConfig.VOICE_INVENTORY_SHA256`; spoken Mongolian reaches the app only through this asset, never as a Kotlin
 * literal (ResourcesTest). [sha256] is the hash of the bytes this instance was parsed from.
 */
class VoiceInventory(bytes: ByteArray) {
    val sha256: String = sha256Hex(bytes)

    /** Every entry in canonical order: key, take, spoken text. */
    val entries: List<Triple<String, Take, String>>

    private val byKey: Map<Pair<String, Take>, String>

    init {
        val list = ArrayList<Triple<String, Take, String>>()
        for (line in String(bytes, Charsets.UTF_8).split('\n')) {
            if (line.isEmpty()) continue
            val cells = line.split('\t')
            require(cells.size == 3) { "voice inventory: bad line" }
            val take = requireNotNull(Take.of(cells[1])) { "voice inventory: bad take for ${cells[0]}" }
            list += Triple(cells[0], take, cells[2])
        }
        entries = list
        byKey = list.associate { (k, t, s) -> (k to t) to s }
    }

    /** The spoken text of [key] in [take], or null when the inventory has no such entry. */
    fun spoken(key: String, take: Take): String? = byKey[key to take]

    operator fun contains(segment: Segment): Boolean = (segment.key to segment.take) in byKey

    val size: Int get() = entries.size

    companion object {
        const val ASSET = "voice/inventory.tsv"

        /** An empty inventory: every segment is missing (the voice output then plays the chime, never a partial prompt). */
        val EMPTY = VoiceInventory(ByteArray(0))

        @Volatile private var app: VoiceInventory? = null

        /** The inventory compiled into this APK (read once per process from the generated asset). */
        fun fromAssets(context: Context): VoiceInventory =
            app ?: synchronized(this) {
                app ?: runCatching { VoiceInventory(context.assets.open(ASSET).use { it.readBytes() }) }
                    .getOrDefault(EMPTY)
                    .also { app = it }
            }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
