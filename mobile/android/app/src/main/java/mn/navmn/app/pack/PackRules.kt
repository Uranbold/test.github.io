package mn.navmn.app.pack

import mn.navmn.app.routing.GraphBuilderAllowList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One installed file, as recorded in `packs/active.json` (NAV-022 task file §1). */
data class InstalledFile(
    val kind: PackKind,
    val version: String,
    /** Relative to `packs/`, for example `20261004T193412Z/routing.tar`. */
    val path: String,
    val bytes: Long,
    val sha256: String,
    val dataTimestamp: String,
    val graphBuilder: String? = null,
    val searchSchema: Int? = null,
    val pmtiles: Int? = null,
    val minzoom: Int? = null,
    val maxzoom: Int? = null,
)

/** The installed state of every kind (a kind may be missing). */
data class InstalledPack(
    val files: Map<PackKind, InstalledFile> = emptyMap(),
    val manifestEtag: String? = null,
    val licenceUrl: String? = null,
) {
    val isEmpty: Boolean get() = files.isEmpty()
    val totalBytes: Long get() = files.values.sumOf { it.bytes }
    operator fun get(kind: PackKind): InstalledFile? = files[kind]

    companion object {
        val NONE = InstalledPack()
    }
}

/** Who started a download: decides the network rule, the kinds and whether the user sees messages (NAV-022 Terms). */
enum class JobMode(val wire: String) {
    /** «Татах», O1, «Шинэчлэх», «Дахин оролдох» (all compatible kinds). */
    USER("user"),
    /** The 14-day offer: routing + search only, mobile data allowed by the offer itself (AC 28). */
    STALE("stale"),
    /** The periodic check: installed kinds only, Wi-Fi only, silent (AC 21–23, 38). */
    AUTO("auto"),
    ;

    val userStarted: Boolean get() = this != AUTO

    companion object {
        fun ofWire(s: String?): JobMode = entries.firstOrNull { it.wire == s } ?: AUTO
    }
}

/** The decisions of NAV-022 P1 (pure; JVM tests per rule). */
object PackRules {
    const val REGION = "mn"
    const val KNOWN_PACK_SCHEMA = 1
    val KNOWN_SEARCH_SCHEMAS: Set<Int> = setOf(1)
    const val PMTILES_VERSION = 3
    const val MARGIN_BYTES = 50_000_000L
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** AC 27: routing + search older than 14 × 24 h. */
    const val STALE_AGE_MS = 14 * DAY_MS
    /** AC 29 (D200): a declined 14-day offer comes back after 7 × 24 h. */
    const val DECLINE_PAUSE_MS = 7 * DAY_MS
    /** AC 1, 3: the first-launch manifest must arrive within 10 s. */
    const val FIRST_LAUNCH_MANIFEST_MS = 10_000L

    val SLOT_ID = Regex("^[0-9]{8}T[0-9]{6}Z$")
    val FILE_PATH = Regex("^[0-9]{8}T[0-9]{6}Z/(basemap\\.pmtiles|routing\\.tar|search\\.sqlite)\\.gz$")
    val SHA256 = Regex("^[0-9a-f]{64}$")

    /** AC 24: a manifest whose `pack_schema` the app does not know offers nothing. */
    fun schemaKnown(m: PackManifest): Boolean = m.packSchema == KNOWN_PACK_SCHEMA

    /**
     * Per-file compatibility (AC 24, P1): routing needs an allow-listed `graph_builder` (R11), search a known
     * `search_schema`, tiles PMTiles v3 (when stated). The path must name the file of its kind; encoding gzip.
     */
    fun compatible(f: PackFile, allowList: (String?) -> Boolean = GraphBuilderAllowList::allows): Boolean {
        val kind = f.packKind ?: return false
        if (f.encoding != "gzip") return false
        if (f.installedName != kind.installedName) return false
        return when (kind) {
            PackKind.ROUTING -> allowList(f.graphBuilder)
            PackKind.SEARCH -> f.searchSchema in KNOWN_SEARCH_SCHEMAS
            PackKind.TILES -> f.pmtilesVersion == null || f.pmtilesVersion == PMTILES_VERSION
        }
    }

    /** The kinds a job may fetch: all for a user download, routing + search for the 14-day offer, installed for auto. */
    fun kindsFor(mode: JobMode, installed: InstalledPack): Set<PackKind> = when (mode) {
        JobMode.USER -> PackKind.entries.toSet()
        JobMode.STALE -> setOf(PackKind.ROUTING, PackKind.SEARCH)
        JobMode.AUTO -> installed.files.keys
    }

    /**
     * Files to fetch (NAV-022 Terms): compatible files of [kinds] whose `sha256` differs from the installed one. Never
     * compares `pack_version` or `version` order (openapi 0.6.1): an older file with another checksum is fetched (AC 25).
     * The tiles file changes monthly and routing + search weekly (D166), so a weekly manifest yields routing + search only.
     */
    fun filesToFetch(
        m: PackManifest,
        installed: InstalledPack,
        kinds: Set<PackKind>,
        allowList: (String?) -> Boolean = GraphBuilderAllowList::allows,
    ): List<PackFile> {
        if (!schemaKnown(m)) return emptyList()
        return PackKind.entries.mapNotNull { kind ->
            if (kind !in kinds) return@mapNotNull null
            val f = m.file(kind) ?: return@mapNotNull null
            if (!compatible(f, allowList)) return@mapNotNull null
            if (installed[kind]?.sha256 == f.sha256) null else f
        }
    }

    fun downloadBytes(files: List<PackFile>): Long = files.sumOf { it.downloadBytes }

    /** NAV-022 Terms "Required space": Σ bytes + the largest download_bytes + 50 MB. */
    fun requiredSpace(files: List<PackFile>): Long =
        if (files.isEmpty()) 0 else files.sumOf { it.bytes } + files.maxOf { it.downloadBytes } + MARGIN_BYTES

    /** AC 6: the space to free (0 = enough). */
    fun spaceToFree(files: List<PackFile>, allocatable: Long): Long = maxOf(0L, requiredSpace(files) - allocatable)

    fun epochMs(timestamp: String): Long? = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val UB: ZoneId = ZoneId.of("Asia/Ulaanbaatar")

    /** AC 39: `data_timestamp` as the Asia/Ulaanbaatar calendar date `YYYY-MM-DD` (digits only). */
    fun dataDate(timestamp: String): String? = runCatching { Instant.parse(timestamp).atZone(UB).format(DATE) }.getOrNull()
}

/**
 * NAV-022 AC 27–30 (D165, D200, D205): when the 14-day routing + search offer may show. Pure: the caller passes the
 * device clock, so the tests use a fake clock.
 */
object StaleOfferRule {
    data class Input(
        val now: Long,
        val foreground: Boolean,
        val validated: Boolean,
        val unmetered: Boolean,
        val guiding: Boolean,
        val installed: InstalledPack,
        /** Σ download_bytes of the routing / search files to fetch of the current manifest (0 = none or unknown). */
        val staleDownloadBytes: Long,
        val lastDeclinedAt: Long?,
        val shownThisSession: Boolean,
        val firstOfferThisSession: Boolean,
    )

    /** The older `data_timestamp` of the installed routing and search files (null when neither is installed). */
    fun oldestWeekly(installed: InstalledPack): Long? =
        listOfNotNull(installed[PackKind.ROUTING], installed[PackKind.SEARCH]).mapNotNull { PackRules.epochMs(it.dataTimestamp) }.minOrNull()

    fun isStale(installed: InstalledPack, now: Long): Boolean {
        val oldest = oldestWeekly(installed) ?: return false
        return now - oldest > PackRules.STALE_AGE_MS
    }

    fun declinePaused(lastDeclinedAt: Long?, now: Long): Boolean =
        lastDeclinedAt != null && now - lastDeclinedAt < PackRules.DECLINE_PAUSE_MS

    fun shouldOffer(i: Input): Boolean =
        i.foreground &&
            i.validated && !i.unmetered && // mobile data (a metered hotspot counts)
            !i.guiding &&
            isStale(i.installed, i.now) &&
            i.staleDownloadBytes > 0 &&
            !declinePaused(i.lastDeclinedAt, i.now) &&
            !i.shownThisSession &&
            !i.firstOfferThisSession // UX B1: never in the same session as O1
}

/** NAV-022 AC 1–3 (D169, D207): the first-launch offer decision at S1 render. */
object FirstLaunchRule {
    enum class Decision {
        /** Flag set or a pack installed: nothing to do. */
        NONE,
        /** No Wi-Fi at the first start: set the flag, no offer (AC 3). */
        SET_FLAG_NO_OFFER,
        /** Wi-Fi: fetch the manifest within 10 s; offer if it arrives (flag set when the offer shows). */
        FETCH_AND_OFFER,
    }

    fun decide(flagSet: Boolean, installed: InstalledPack, validated: Boolean, unmetered: Boolean): Decision = when {
        flagSet || !installed.isEmpty -> Decision.NONE
        !(validated && unmetered) -> Decision.SET_FLAG_NO_OFFER
        else -> Decision.FETCH_AND_OFFER
    }
}
