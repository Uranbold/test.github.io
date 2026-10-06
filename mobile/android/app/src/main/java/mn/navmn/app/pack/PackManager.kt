package mn.navmn.app.pack

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mn.navmn.app.BuildConfig
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.net.NetworkStateSource
import mn.navmn.app.routing.OnDeviceRouting
import mn.navmn.app.routing.PackFiles
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.variant.ReplayVariant
import okhttp3.OkHttpClient
import java.io.File
import java.util.Optional
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The status row of the pack section (screen spec O2 States); one component for all of them. */
sealed interface JobUi {
    data object Idle : JobUi
    /** OF9 «Wi-Fi холболт хүлээж байна» (AC 9, 10). */
    data object Waiting : JobUi
    /** OF8 with the integer percentage (AC 12). */
    data class Downloading(val percent: Int) : JobUi
    /** Checking and installing: OF8 at 100 % with an indeterminate bar until the BA term exists (UX Open question 1). */
    data object Verifying : JobUi
    /** OF12 with «Дахин оролдох» (AC 15, 17). */
    data object Failed : JobUi
    /** OF15 with the space to free (AC 6, 7). */
    data class NoSpace(val toFree: Long) : JobUi
}

/** What the last manifest means for this phone (OF6, OF7, «Шинэчлэх», the 14-day offer size, OF29). */
data class ManifestFacts(
    val schemaKnown: Boolean,
    /** Σ download_bytes of the files to fetch (all compatible kinds; AC 1, 4, 8). */
    val downloadBytes: Long,
    val requiredSpace: Long,
    /** Σ download_bytes of the routing / search files to fetch (AC 27). */
    val staleDownloadBytes: Long,
    val licenceUrl: String,
) {
    val hasFilesToFetch: Boolean get() = downloadBytes > 0
}

data class PackState(
    /** False in the demo build (UX B7) and without a usable base URL: no section, no offer, 0 requests. */
    val enabled: Boolean = false,
    val installed: InstalledPack = InstalledPack.NONE,
    val facts: ManifestFacts? = null,
    val job: JobUi = JobUi.Idle,
)

sealed interface PackOffer {
    /** O1 (AC 1). */
    data class First(val downloadBytes: Long, val requiredSpace: Long) : PackOffer
    /** O4 (AC 27): routing + search only. */
    data class Stale(val downloadBytes: Long, val routingTimestamp: String?, val searchTimestamp: String?) : PackOffer
}

/** O6 S1 messages at the end of a user-started download while the app is in the foreground (AC 41). */
sealed interface PackMessage {
    data object Ready : PackMessage
    data object Failed : PackMessage
    data class NoSpace(val toFree: Long) : PackMessage
}

/** O3: the per-download mobile-data confirmation (AC 8; never remembered, AC 9). */
data class MobileConfirm(val mode: JobMode, val downloadBytes: Long)

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PackClient

/**
 * NAV-022 PackManager (ADR-0017 §5, task file P1–P11): the app-wide pack state in the main process, the entry points
 * of the UI (first-launch offer, «Тохиргоо» section, dialogs, 14-day offer, delete) and of the WorkManager job.
 *
 * Network rules (D164): the manifest may use any validated network (AC 11); pack files run in a job whose WorkManager
 * constraint is `UNMETERED` unless the user confirmed OF13 or accepted the 14-day offer (`CONNECTED`), and [PackJob]
 * re-checks the network before every file. One job at a time ([jobMutex]): a user download waits for a running
 * automatic check and vice versa.
 *
 * Consumers (AC 19, P2): the map reports the tiles file it shows ([setMapTilesInUse]); a guidance session pins its
 * routing file in NAV-021's [OnDeviceRouting]. Files that `active.json` no longer references are deleted as soon as no
 * consumer holds them (install, delete, map switch, end of guidance) and at every start, together with `*.partial`.
 */
@Singleton
class PackManager internal constructor(
    enabled: Boolean,
    val store: PackStore,
    private val http: PackHttp?,
    private val prefs: PackPrefs,
    private val scheduler: PackScheduler,
    private val network: NetworkStateSource,
    private val space: SpaceProbe,
    private val selfTests: SelfTests,
    private val notifier: PackNotifier,
    private val guiding: StateFlow<Boolean>,
    /** The routing tar pinned by the running guidance session (null when not guiding). */
    private val routingHold: () -> File?,
    private val clock: () -> Long,
    private val elapsed: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val scope: CoroutineScope,
    private val log: DebugLog,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        network: NetworkMonitor,
        session: GuidanceSession,
        onDeviceRouting: OnDeviceRouting,
        settings: SettingsRepository,
        replay: Optional<ReplayVariant>,
        @PackClient client: OkHttpClient,
    ) : this(
        enabled = !replay.isPresent && PackConfig.baseUrl() != null,
        store = PackStore(File(context.noBackupFilesDir, PackFiles.PACKS_DIR), packLog()),
        http = PackConfig.baseUrl()?.takeIf { !replay.isPresent }?.let { PackHttp(it, client, PackConfig.userAgent()) },
        prefs = SharedPackPrefs(context),
        scheduler = WorkPackScheduler(context),
        network = network,
        space = PlatformSpaceProbe(context),
        selfTests = DefaultSelfTests(BoundRoutingSelfTest(context), BundledSearchSelfTest(), packLog()),
        notifier = PackNotifications(context) { settings.lang.value },
        guiding = session.engine.map { it != null }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false),
        routingHold = { if (session.engine.value != null) onDeviceRouting.routingForRequest()?.tar else null },
        clock = System::currentTimeMillis,
        elapsed = SystemClock::elapsedRealtime,
        sleep = { delay(it) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        log = packLog(),
    )

    val enabled: Boolean = enabled && http != null

    private val jobMutex = Mutex()
    @Volatile private var runningMode: JobMode? = null

    private val _installed = MutableStateFlow(InstalledPack.NONE)
    private val _facts = MutableStateFlow<ManifestFacts?>(null)
    @Volatile private var manifest: PackManifest? = null
    @Volatile private var manifestAt: Long = 0

    /** Progress of the running user-started job. */
    private val _active = MutableStateFlow<JobUi?>(null)
    /** The outcome of the last user-started download or its pre-checks (Failed / NoSpace), until the next start. */
    private val _final = MutableStateFlow<JobUi?>(null)
    /** B5: «Татаж байна… 0%» at once while the pre-checks run (manifest, space, network). */
    private val _preflight = MutableStateFlow<JobUi?>(null)
    private val userWork: StateFlow<UserWork?> =
        (if (this.enabled) scheduler.userWork() else flowOf(null)).stateIn(scope, SharingStarted.Eagerly, null)

    private val jobUi: StateFlow<JobUi> = combine(
        combine(userWork, _active, _final, _preflight) { w, a, f, p -> Quad(w, a, f, p) },
        network.validated,
        network.unmetered,
    ) { q, validated, unmetered ->
        val w = q.work
        when {
            q.active != null -> q.active
            w != null && w.running -> JobUi.Downloading(0)
            w != null -> if (validated && (unmetered || w.allowMetered)) JobUi.Downloading(0) else JobUi.Waiting
            q.preflight != null -> q.preflight
            q.final != null -> q.final
            else -> JobUi.Idle
        }
    }.stateIn(scope, SharingStarted.Eagerly, JobUi.Idle)

    val state: StateFlow<PackState> = combine(_installed, _facts, jobUi) { i, f, j -> PackState(this.enabled, i, f, j) }
        .stateIn(scope, SharingStarted.Eagerly, PackState(this.enabled))

    private val _offer = MutableStateFlow<PackOffer?>(null)
    val offer: StateFlow<PackOffer?> = _offer.asStateFlow()
    private val _message = MutableStateFlow<PackMessage?>(null)
    val message: StateFlow<PackMessage?> = _message.asStateFlow()
    private val _confirm = MutableStateFlow<MobileConfirm?>(null)
    val confirm: StateFlow<MobileConfirm?> = _confirm.asStateFlow()

    @Volatile private var foreground = false
    @Volatile private var firstLaunchChecked = false
    @Volatile private var firstOfferThisSession = false
    @Volatile private var staleShownThisSession = false
    @Volatile private var staleChecking = false
    @Volatile private var mapHold: String? = null

    init {
        if (this.enabled) {
            // A small file: read at once, so the first style load already uses the installed basemap (AC 31).
            _installed.value = runCatching { store.installed() }.getOrDefault(InstalledPack.NONE)
            scope.launch {
                gc(deletePartials = true) // AC 18: partial directories go within 10 s of the start
                if (!_installed.value.isEmpty) scheduler.ensurePeriodic() // AC 21: also after an app update
            }
            // AC 19 / P2: the routing file pinned by a guidance session is released at its end.
            scope.launch { guiding.drop(1).filter { !it }.collect { delay(GC_AFTER_GUIDANCE_MS); gc() } }
            // AC 27: "or the network changes while in the foreground".
            scope.launch {
                combine(network.validated, network.unmetered) { v, u -> v to u }.distinctUntilChanged().drop(1).collect { if (foreground) checkStale() }
            }
        }
    }

    // ---------------------------------------------------------------- UI entry points

    /** S1 has rendered (AC 1–3): the first-launch decision, once per process. */
    fun onBrowseShown() {
        if (!enabled || firstLaunchChecked) return
        firstLaunchChecked = true
        scope.launch {
            val installed = store.installed()
            when (FirstLaunchRule.decide(prefs.offerFlag, installed, network.validated.value, network.unmetered.value)) {
                FirstLaunchRule.Decision.NONE -> Unit
                FirstLaunchRule.Decision.SET_FLAG_NO_OFFER -> prefs.setOfferFlag()
                FirstLaunchRule.Decision.FETCH_AND_OFFER -> {
                    // AC 3: error, 404, 429 or no answer within 10 s → no offer and the flag stays unset.
                    val m = withTimeoutOrNull(PackRules.FIRST_LAUNCH_MANIFEST_MS) { fetchManifest() } ?: return@launch
                    val plan = PackRules.filesToFetch(m, installed, PackKind.entries.toSet())
                    if (plan.isEmpty()) return@launch
                    _offer.value = PackOffer.First(PackRules.downloadBytes(plan), PackRules.requiredSpace(plan))
                }
            }
        }
    }

    /** A new foreground session (Activity started): the 14-day offer may show once in it (AC 27). */
    fun onForeground() {
        foreground = true
        staleShownThisSession = false
        if (!enabled) return
        scope.launch {
            _installed.value = store.installed()
            checkStale()
        }
    }

    fun onBackground() {
        foreground = false
    }

    /** «Тохиргоо» opened: refresh the installed state and the manifest (any validated network, AC 11). */
    fun onSettingsOpened() {
        if (!enabled) return
        scope.launch {
            _installed.value = store.installed()
            if (network.validated.value && (manifest == null || clock() - manifestAt > MANIFEST_FRESH_MS)) fetchManifest()
        }
    }

    /** The offer became visible on S1 (AC 2: the flag is stored the moment it shows). */
    fun onOfferShown(offer: PackOffer) {
        when (offer) {
            is PackOffer.First -> {
                prefs.setOfferFlag()
                firstOfferThisSession = true
            }
            is PackOffer.Stale -> staleShownThisSession = true
        }
    }

    /** «Татах» / «Шинэчлэх» ([accepted]) or «Дараа», Back, swipe, scrim. */
    fun onOfferClosed(offer: PackOffer, accepted: Boolean) {
        _offer.value = null
        when (offer) {
            is PackOffer.First -> {
                prefs.setOfferFlag()
                if (accepted) download(JobMode.USER)
            }
            is PackOffer.Stale -> if (accepted) download(JobMode.STALE) else prefs.setLastStaleDecline(clock()) // AC 29
        }
    }

    /**
     * A user-started download: «Монголын газрын зураг татах», O1 «Татах», «Шинэчлэх», «Дахин оролдох» ([JobMode.USER]) or
     * the 14-day offer ([JobMode.STALE]). Order (flow F2): manifest → files to fetch → space (AC 6: 0 file requests when
     * short) → network: Wi-Fi starts at once, mobile data asks OF13 (AC 8), no network queues (AC 10).
     */
    fun download(mode: JobMode = JobMode.USER): Job = scope.launch {
        if (!enabled) return@launch
        _final.value = null
        _confirm.value = null
        _message.value = null
        _preflight.value = JobUi.Downloading(0)
        try {
            if (!network.validated.value) {
                scheduler.enqueueUser(mode, allowMetered = false) // AC 10: queued for Wi-Fi, never mobile data
                awaitQueued()
                return@launch
            }
            val m = fetchManifest()
            if (m == null) {
                _final.value = JobUi.Failed
                return@launch
            }
            val installed = store.installed()
            val plan = PackRules.filesToFetch(m, installed, PackRules.kindsFor(mode, installed))
            if (plan.isEmpty()) {
                if (installed.isEmpty) _final.value = JobUi.Failed // nothing compatible to install (AC 24)
                return@launch
            }
            val toFree = PackRules.spaceToFree(plan, space.allocatable(store.packsDir))
            if (toFree > 0) {
                _final.value = JobUi.NoSpace(toFree)
                return@launch
            }
            when {
                mode == JobMode.STALE -> scheduler.enqueueUser(mode, allowMetered = true) // AC 28: the offer confirmed it
                network.unmetered.value -> scheduler.enqueueUser(mode, allowMetered = false) // AC 8: starts at once
                else -> {
                    _confirm.value = MobileConfirm(mode, PackRules.downloadBytes(plan)) // AC 8: 0 file requests yet
                    return@launch
                }
            }
            awaitQueued()
        } finally {
            _preflight.value = null
        }
    }

    /** O3 «Татах»: this download may use any validated network until it ends (AC 9). */
    fun confirmMobileData() {
        val c = _confirm.value ?: return
        _confirm.value = null
        scheduler.enqueueUser(c.mode, allowMetered = true)
    }

    /** O3 «Wi-Fi хүлээх», Back or outside: queued with OF9 (AC 9). */
    fun waitForWifi() {
        val c = _confirm.value ?: return
        _confirm.value = null
        scheduler.enqueueUser(c.mode, allowMetered = false)
    }

    /** «Цуцлах» (AC 13): within 5 s all transfers stop, 0 partial files, the state as before. */
    fun cancel(): Job = scope.launch {
        _confirm.value = null
        _final.value = null
        _preflight.value = null
        scheduler.cancelUser()
        withTimeoutOrNull(CANCEL_WAIT_MS) { while (runningMode?.userStarted == true) delay(POLL_MS) }
        if (runningMode == null) store.clearStaging() // a running automatic check owns the staging area
        _active.value = null
        notifier.clear()
    }

    /** O5 «Устгах» (AC 36, 38; UX Open question 4 (a): a running download is cancelled first). */
    fun delete(): Job = scope.launch {
        cancel().join()
        scheduler.cancelPeriodic() // AC 38: periodic checks only update installed kinds; nothing is installed now
        withTimeoutOrNull(CANCEL_WAIT_MS) { while (runningMode != null) delay(POLL_MS) }
        store.clearStaging()
        store.deleteAll()
        _installed.value = InstalledPack.NONE
        manifest?.let { recompute(it) }
        gc() // the search file goes at once; held map and routing files when their consumer releases them
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * AC 19 / P10: the tiles file the map style currently uses (absolute path, or null for the online PMTiles). Releasing
     * a file deletes it when `active.json` no longer references it.
     */
    fun setMapTilesInUse(absolutePath: String?) {
        val rel = absolutePath?.let { relative(File(it)) }
        if (rel == mapHold) return
        mapHold = rel
        scope.launch { gc() }
    }

    /** The installed basemap file for the map style (AC 31), or null. */
    fun installedTiles(p: InstalledPack = _installed.value): File? = p[PackKind.TILES]?.let { store.fileOf(it) }

    // ---------------------------------------------------------------- the job (WorkManager)

    enum class WorkOutcome { DONE, RETRY }

    /** One WorkManager run. User-started modes report progress and outcomes; the automatic check stays silent (AC 22). */
    suspend fun runWork(mode: JobMode, allowMetered: Boolean): WorkOutcome {
        if (!enabled || http == null) return WorkOutcome.DONE
        val user = mode.userStarted
        return jobMutex.withLock {
            runningMode = mode
            try {
                if (user) {
                    _final.value = null
                    _active.value = JobUi.Downloading(0)
                }
                var lastPost = Long.MIN_VALUE
                val result = try {
                    newJob().run(mode, allowMetered) { p ->
                        if (!user) return@run
                        val ui = when (p) {
                            is JobProgress.Downloading -> JobUi.Downloading(p.percent)
                            JobProgress.Verifying -> JobUi.Verifying
                        }
                        _active.value = ui
                        val now = elapsed()
                        if (p is JobProgress.Verifying || now - lastPost >= NOTIFY_EVERY_MS) {
                            lastPost = now
                            notifier.progress((ui as? JobUi.Downloading)?.percent)
                        }
                    }
                } catch (e: CancellationException) {
                    if (user) {
                        _active.value = null
                        notifier.clear()
                    }
                    throw e
                } catch (e: Exception) {
                    // Review M1: any other failure (an IOException from PackStore.install, a self-test or file error)
                    // ends like a failed check: staged and partial files go, the installed pack stays (active.json is
                    // the commit point and was not replaced), the UI shows the failure instead of a stuck row.
                    log.d("pack job error: ${e.javaClass.simpleName}")
                    runCatching { store.clearStaging() }
                    JobResult.Failed(FailReason.INTEGRITY)
                }
                handle(mode, allowMetered, result)
            } finally {
                runningMode = null
            }
        }
    }

    private suspend fun handle(mode: JobMode, allowMetered: Boolean, r: JobResult): WorkOutcome {
        val user = mode.userStarted
        val outcome = when (r) {
            is JobResult.Installed -> {
                _installed.value = r.installed
                prefs.setOfferFlag() // AC 2, 38: a pack was chosen; the first-launch offer never shows after this
                manifest?.let { recompute(it) }
                scheduler.ensurePeriodic()
                gc()
                if (user) {
                    notifier.clear()
                    if (foreground) _message.value = PackMessage.Ready else notifier.ready() // AC 41; auto: nothing (AC 22)
                }
                WorkOutcome.DONE
            }
            JobResult.NothingToDo -> {
                _installed.value = store.installed()
                if (user) notifier.clear()
                WorkOutcome.DONE
            }
            JobResult.WaitNetwork -> {
                if (user) {
                    notifier.waiting()
                    WorkOutcome.RETRY // WorkManager runs it again when its network constraint holds
                } else {
                    WorkOutcome.DONE // the next periodic check
                }
            }
            is JobResult.RetryLater -> {
                if (user) {
                    notifier.waiting()
                    scheduler.enqueueUser(mode, allowMetered, r.afterMs) // AC 15: nothing before Retry-After
                }
                WorkOutcome.DONE
            }
            is JobResult.NoSpace -> {
                if (user) {
                    _final.value = JobUi.NoSpace(r.toFree)
                    notifier.clear()
                    if (foreground) _message.value = PackMessage.NoSpace(r.toFree) else notifier.failed(r.toFree)
                }
                WorkOutcome.DONE // automatic: skipped silently, next period (AC 6)
            }
            is JobResult.Failed -> {
                log.d("pack job failed: ${r.reason}")
                // The installed pack as on disk (unchanged by a failed job; a file that went missing is not shown).
                runCatching { manifest?.let { recompute(it) } ?: run { _installed.value = store.installed() } }
                if (user) {
                    _final.value = JobUi.Failed
                    notifier.clear()
                    if (foreground) _message.value = PackMessage.Failed else notifier.failed(null)
                }
                WorkOutcome.DONE // automatic: fails silently, next period (AC 17)
            }
        }
        if (user) _active.value = null
        return outcome
    }

    private fun newJob(): PackJob = PackJob(
        http = http!!,
        store = store,
        selfTests = selfTests,
        space = space,
        network = object : NetworkGate {
            override fun validated() = network.validated.value
            override fun unmetered() = network.unmetered.value
        },
        sleep = sleep,
        log = log,
        beforeSelfTests = ::awaitNotGuiding,
        onManifest = { m, _ ->
            manifest = m
            manifestAt = clock()
            recompute(m)
        },
    )

    /**
     * The routing self-test shares the `:routing` process with guidance, so it waits for guidance to end (a reroute
     * must not queue behind a cold engine). After [GUIDANCE_WAIT_MS] the job is retried later.
     */
    private suspend fun awaitNotGuiding(): Boolean =
        !guiding.value || withTimeoutOrNull(GUIDANCE_WAIT_MS) { guiding.first { !it } } != null

    // ---------------------------------------------------------------- internals

    private suspend fun fetchManifest(): PackManifest? {
        val h = http ?: return null
        return when (val r = h.fetchManifest()) {
            is ManifestResult.Ok -> {
                manifest = r.manifest
                manifestAt = clock()
                recompute(r.manifest)
                r.manifest
            }
            else -> null
        }
    }

    private fun recompute(m: PackManifest) {
        val installed = store.installed()
        _installed.value = installed
        val plan = PackRules.filesToFetch(m, installed, PackKind.entries.toSet())
        val stale = PackRules.filesToFetch(m, installed, PackRules.kindsFor(JobMode.STALE, installed))
        _facts.value = ManifestFacts(
            schemaKnown = PackRules.schemaKnown(m),
            downloadBytes = PackRules.downloadBytes(plan),
            requiredSpace = PackRules.requiredSpace(plan),
            staleDownloadBytes = PackRules.downloadBytes(stale),
            licenceUrl = m.licence.url,
        )
    }

    private suspend fun checkStale() {
        if (!enabled || !foreground || staleChecking) return
        if (guiding.value || _offer.value != null || userWork.value != null || runningMode != null) return
        val now = clock()
        val installed = store.installed()
        if (!StaleOfferRule.isStale(installed, now)) return
        if (!network.validated.value || network.unmetered.value) return
        if (staleShownThisSession || firstOfferThisSession || StaleOfferRule.declinePaused(prefs.lastStaleDecline, now)) return
        staleChecking = true
        try {
            val m = fetchManifest() ?: return
            val bytes = PackRules.downloadBytes(PackRules.filesToFetch(m, installed, PackRules.kindsFor(JobMode.STALE, installed)))
            val ok = StaleOfferRule.shouldOffer(
                StaleOfferRule.Input(
                    now = clock(),
                    foreground = foreground,
                    validated = network.validated.value,
                    unmetered = network.unmetered.value,
                    guiding = guiding.value,
                    installed = installed,
                    staleDownloadBytes = bytes,
                    lastDeclinedAt = prefs.lastStaleDecline,
                    shownThisSession = staleShownThisSession,
                    firstOfferThisSession = firstOfferThisSession,
                ),
            )
            if (ok && _offer.value == null) {
                _offer.value = PackOffer.Stale(bytes, installed[PackKind.ROUTING]?.dataTimestamp, installed[PackKind.SEARCH]?.dataTimestamp)
            }
        } finally {
            staleChecking = false
        }
    }

    /** Keeps the optimistic row until WorkManager reports the queued job (no flash of the idle state). */
    private suspend fun awaitQueued() {
        withTimeoutOrNull(QUEUE_WAIT_MS) { userWork.first { it != null } }
    }

    internal fun gc(deletePartials: Boolean = false): Int {
        val holds = buildSet {
            mapHold?.let(::add)
            routingHold()?.let { f -> relative(f)?.let(::add) }
        }
        return runCatching { store.collectGarbage(holds, deletePartials) }.getOrDefault(0)
    }

    private fun relative(f: File): String? {
        val base = store.packsDir.canonicalFile.path + File.separator
        val p = runCatching { f.canonicalFile.path }.getOrNull() ?: return null
        return p.takeIf { it.startsWith(base) }?.removePrefix(base)
    }

    private data class Quad(val work: UserWork?, val active: JobUi?, val final: JobUi?, val preflight: JobUi?)

    companion object {
        const val MANIFEST_FRESH_MS = 5 * 60_000L
        const val CANCEL_WAIT_MS = 4_000L
        const val POLL_MS = 50L
        const val QUEUE_WAIT_MS = 2_000L
        /** AC 12: progress at least every 2 s. */
        const val NOTIFY_EVERY_MS = 1_000L
        const val GC_AFTER_GUIDANCE_MS = 2_000L
        const val GUIDANCE_WAIT_MS = 8 * 60_000L

        private fun packLog(): DebugLog = if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.pack", it) } else DebugLog.NONE
    }
}

/** AC 6: `StorageManager.getAllocatableBytes` on the pack directory's volume (free space as a fallback). */
class PlatformSpaceProbe(context: Context) : SpaceProbe {
    private val sm = context.getSystemService(StorageManager::class.java)

    override fun allocatable(dir: File): Long {
        dir.mkdirs()
        return runCatching { sm.getAllocatableBytes(sm.getUuidForPath(dir)) }.getOrElse { runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L) }
    }
}
