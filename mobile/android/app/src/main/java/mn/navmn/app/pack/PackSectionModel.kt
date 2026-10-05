package mn.navmn.app.pack

/**
 * NAV-022 screen spec O2 (States table): what the «Офлайн газрын зураг» section shows for a [PackState]. Pure, so the
 * state table is tested on the JVM; the composable only draws it. One primary action per state (F2).
 */
data class PackSectionModel(
    /** OF3 (no pack only). */
    val benefit: Boolean,
    /** OF6 value (bytes) or null when unknown / not shown. */
    val downloadSize: Long?,
    /** OF7 value (bytes) or null. */
    val spaceNeeded: Long?,
    val status: JobUi?,
    val primary: Primary,
    /** OF20 lines: (kind, data_timestamp) in the order map, routes, search; only installed kinds (AC 39). */
    val dates: List<Pair<PackKind, String>>,
    /** OF28 (bytes) when a pack is installed. */
    val spaceUsed: Long?,
    val delete: Boolean,
    /** OF29 target (manifest `licence.url`, else the one recorded at install); attribution and the line show with a pack. */
    val licenceUrl: String?,
) {
    enum class Primary { DOWNLOAD, UPDATE, CANCEL, RETRY, NONE }

    companion object {
        private val DATE_ORDER = listOf(PackKind.TILES, PackKind.ROUTING, PackKind.SEARCH)

        fun of(s: PackState): PackSectionModel {
            val installed = !s.installed.isEmpty
            val facts = s.facts?.takeIf { it.schemaKnown }
            val job = s.job
            val primary = when (job) {
                JobUi.Waiting, is JobUi.Downloading, JobUi.Verifying -> Primary.CANCEL
                JobUi.Failed, is JobUi.NoSpace -> Primary.RETRY
                JobUi.Idle -> when {
                    !installed -> Primary.DOWNLOAD
                    facts?.hasFilesToFetch == true -> Primary.UPDATE // AC 4, 26
                    else -> Primary.NONE // up to date, or the pack is too new for the app (AC 24)
                }
            }
            val sizes = facts?.takeIf { it.hasFilesToFetch }
            return PackSectionModel(
                benefit = !installed && job == JobUi.Idle,
                downloadSize = sizes?.downloadBytes,
                // OF7 belongs to the first download and its failures (set-none, set-failed, set-storage).
                spaceNeeded = if (!installed && (job == JobUi.Idle || job == JobUi.Failed || job is JobUi.NoSpace)) sizes?.requiredSpace else null,
                status = job.takeIf { it != JobUi.Idle },
                primary = primary,
                dates = DATE_ORDER.mapNotNull { k -> s.installed[k]?.let { k to it.dataTimestamp } },
                spaceUsed = if (installed) s.installed.totalBytes else null,
                delete = installed,
                licenceUrl = if (installed) (s.facts?.licenceUrl ?: s.installed.licenceUrl) else null,
            )
        }
    }
}
