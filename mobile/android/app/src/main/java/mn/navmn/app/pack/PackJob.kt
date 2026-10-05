package mn.navmn.app.pack

import mn.navmn.app.log.DebugLog
import java.io.File

/** The network facts a download needs (NAV-022 Terms: Wi-Fi = validated + not metered). */
interface NetworkGate {
    fun validated(): Boolean
    fun unmetered(): Boolean
}

/** `StorageManager.getAllocatableBytes` on the pack directory's volume (AC 6); a fake in tests. */
fun interface SpaceProbe {
    fun allocatable(dir: File): Long
}

sealed interface JobProgress {
    data class Downloading(val received: Long, val total: Long) : JobProgress {
        val percent: Int get() = if (total <= 0) 0 else ((received * 100) / total).toInt().coerceIn(0, 100)
    }

    data object Verifying : JobProgress
}

enum class FailReason { MANIFEST, NETWORK, INTEGRITY, SELF_TEST, INCOMPATIBLE }

sealed interface JobResult {
    data class Installed(val kinds: Set<PackKind>, val installed: InstalledPack) : JobResult
    data object NothingToDo : JobResult
    /** No validated network, or Wi-Fi required and the network is metered: wait (0 file requests, AC 10, 11, 23). */
    data object WaitNetwork : JobResult
    /** 429 with a `Retry-After` longer than the job may wait: run again after [afterMs] (AC 15). */
    data class RetryLater(val afterMs: Long) : JobResult
    data class NoSpace(val toFree: Long) : JobResult
    data class Failed(val reason: FailReason) : JobResult
}

/**
 * NAV-022 P3 + P4: one run of a pack download (user-started, 14-day offer or automatic), independent of WorkManager so
 * the JVM tests drive it against a mock `/packs/` server.
 *
 * Manifest (any validated network, AC 11; `If-None-Match` for automatic checks, AC 21) → files to fetch (AC 24, 25)
 * → space check before any file request (AC 6) → per file: network rule (AC 8–11, 23), resumable download (AC 14),
 * 404 → re-read the manifest, 429 → wait `Retry-After`, 5 consecutive failures → [FailReason.NETWORK] with the partial
 * files kept (AC 15), no space → staging deleted (AC 7) → checksum + gunzip (AC 16) → self-tests → atomic install
 * (AC 18). Any check failure deletes the job's staged files and keeps the installed pack (AC 17).
 *
 * Cancellation is coroutine cancellation (the caller deletes the staging area, AC 13).
 */
class PackJob(
    private val http: PackHttp,
    private val store: PackStore,
    private val selfTests: SelfTests,
    private val space: SpaceProbe,
    private val network: NetworkGate,
    private val sleep: suspend (Long) -> Unit,
    private val log: DebugLog = DebugLog.NONE,
    private val allowList: (String?) -> Boolean = mn.navmn.app.routing.GraphBuilderAllowList::allows,
    /** Waits until the self-tests may run (false: try again later; the staged files are kept). */
    private val beforeSelfTests: suspend () -> Boolean = { true },
    /** Every manifest this run reads (the UI facts follow the newest one). */
    private val onManifest: (PackManifest, String?) -> Unit = { _, _ -> },
) {
    suspend fun run(mode: JobMode, allowMetered: Boolean, progress: (JobProgress) -> Unit = {}): JobResult {
        if (!network.validated()) return JobResult.WaitNetwork
        var failures = 0
        var rereads = 0
        manifest@ while (true) {
            val installed = store.installed()
            val fetched = when (val mr = http.fetchManifest(if (mode == JobMode.AUTO) installed.manifestEtag else null)) {
                is ManifestResult.Ok -> mr
                ManifestResult.NotModified -> return JobResult.NothingToDo // AC 21: 304 → 0 file requests
                ManifestResult.NotFound -> return if (mode.userStarted) JobResult.Failed(FailReason.MANIFEST) else JobResult.NothingToDo
                is ManifestResult.RateLimited -> {
                    if (mr.retryAfterMs > MAX_INLINE_WAIT_MS) return JobResult.RetryLater(mr.retryAfterMs)
                    sleep(mr.retryAfterMs)
                    continue@manifest
                }
                ManifestResult.Failed -> {
                    if (++failures >= MAX_FAILURES) return JobResult.Failed(FailReason.MANIFEST)
                    sleep(backoff(failures))
                    if (!network.validated()) return JobResult.WaitNetwork
                    continue@manifest
                }
            }
            val manifest = fetched.manifest
            onManifest(manifest, fetched.etag)
            if (!PackRules.schemaKnown(manifest)) {
                log.d("pack manifest: unknown pack_schema, nothing offered")
                return if (mode.userStarted && installed.isEmpty) JobResult.Failed(FailReason.INCOMPATIBLE) else JobResult.NothingToDo
            }
            val plan = PackRules.filesToFetch(manifest, installed, PackRules.kindsFor(mode, installed), allowList)
            if (plan.isEmpty()) {
                store.rememberEtag(fetched.etag, manifest.licence.url)
                return if (mode.userStarted && installed.isEmpty) JobResult.Failed(FailReason.INCOMPATIBLE) else JobResult.NothingToDo
            }
            val toFree = PackRules.spaceToFree(plan, space.allocatable(store.packsDir))
            if (toFree > 0) return JobResult.NoSpace(toFree) // AC 6: 0 file requests
            store.pruneStaging(plan.map { "${it.version}/${it.installedName}" }.toSet())

            val total = PackRules.downloadBytes(plan)
            var done = 0L
            progress(JobProgress.Downloading(0, total))
            val staged = ArrayList<StagedFile>()
            for (f in plan) {
                val dir = store.stagingDir(f.version)
                val raw = File(dir, f.installedName)
                val gz = File(dir, f.installedName + ".gz")
                val validator = File(dir, f.installedName + ".gz" + PackStore.VALIDATOR)
                if (raw.isFile && PackVerifier.rawStillValid(f, raw)) { // verified in an earlier run (resume)
                    done += f.downloadBytes
                    progress(JobProgress.Downloading(done, total))
                    staged += StagedFile(f, raw)
                    continue
                }
                transfer@ while (true) {
                    if (!network.validated()) return JobResult.WaitNetwork
                    if (!allowMetered && !network.unmetered()) return JobResult.WaitNetwork // AC 11, 23: Wi-Fi only
                    val base = done
                    when (val r = http.download(f, gz, validator) { onDisk -> progress(JobProgress.Downloading(base + onDisk, total)) }) {
                        DownloadResult.Done -> break@transfer
                        DownloadResult.Retired -> {
                            if (++rereads > MAX_REREADS) return JobResult.Failed(FailReason.NETWORK)
                            log.d("pack file retired (404): manifest re-read")
                            continue@manifest
                        }
                        is DownloadResult.RateLimited -> {
                            if (r.retryAfterMs > MAX_INLINE_WAIT_MS) return JobResult.RetryLater(r.retryAfterMs)
                            sleep(r.retryAfterMs)
                        }
                        is DownloadResult.Failed -> {
                            if (r.progressed) failures = 0
                            if (++failures >= MAX_FAILURES) return JobResult.Failed(FailReason.NETWORK) // partial kept
                            sleep(backoff(failures))
                        }
                        DownloadResult.NoSpace -> return noSpace(plan)
                    }
                }
                done += f.downloadBytes
                progress(JobProgress.Downloading(done, total))
                when (PackVerifier.verifyAndInflate(f, gz, raw)) {
                    is PackVerifier.Result.Ok -> {
                        gz.delete()
                        validator.delete()
                        staged += StagedFile(f, raw)
                    }
                    PackVerifier.Result.NoSpace -> return noSpace(plan)
                    else -> {
                        log.d("pack ${f.kind}: checksum or gzip check failed")
                        store.clearStaging()
                        return JobResult.Failed(FailReason.INTEGRITY)
                    }
                }
            }
            progress(JobProgress.Verifying)
            if (!beforeSelfTests()) return JobResult.RetryLater(RETRY_AFTER_GUIDANCE_MS)
            for (s in staged) {
                val ok = when (s.manifestFile.packKind) {
                    PackKind.TILES -> selfTests.tiles(s.manifestFile, s.raw)
                    PackKind.ROUTING -> selfTests.routing(s.manifestFile, s.raw, manifest)
                    PackKind.SEARCH -> selfTests.search(s.manifestFile, s.raw, manifest)
                    null -> false
                }
                if (!ok) {
                    store.clearStaging()
                    return JobResult.Failed(FailReason.SELF_TEST)
                }
            }
            val after = store.install(staged, fetched.etag, manifest.licence.url)
            store.pruneStaging(emptySet())
            return JobResult.Installed(staged.mapNotNull { it.manifestFile.packKind }.toSet(), after)
        }
    }

    private fun noSpace(plan: List<PackFile>): JobResult {
        store.clearStaging() // AC 7: partial files deleted, the installed pack unchanged
        return JobResult.NoSpace(maxOf(1L, PackRules.spaceToFree(plan, space.allocatable(store.packsDir))))
    }

    companion object {
        /** AC 15: 5 consecutive attempts with network errors or 5xx. */
        const val MAX_FAILURES = 5
        const val MAX_REREADS = 3
        const val MAX_INLINE_WAIT_MS = 5 * 60_000L
        const val RETRY_AFTER_GUIDANCE_MS = 60_000L

        /** 2, 4, 8, 16 s between consecutive failed attempts. */
        fun backoff(failures: Int): Long = 1000L shl failures.coerceIn(1, 4)
    }
}
