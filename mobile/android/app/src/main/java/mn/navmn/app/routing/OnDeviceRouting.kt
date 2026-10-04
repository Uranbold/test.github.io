package mn.navmn.app.routing

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import mn.navmn.app.BuildConfig
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkStateSource
import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.RouteClient
import mn.navmn.app.routing.ipc.BoundRoutingEngine
import mn.navmn.app.variant.ReplayVariant
import java.io.File
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAV-021 (ADR-0017 §2, §5): the app-wide state of on-device routing in the main process.
 *
 * - **Installed file** from [InstalledRoutingSource] (`packs/active.json`, NAV-022 format; debug provisioning until
 *   NAV-022). Nothing installed → [available] is false and the app behaves exactly as before (AC 13, 30); the
 *   `:routing` process is never started. A replay (demo) build never uses the engine (screen spec B7).
 * - **Version pinned per guidance session** (AC 17, R3): captured at «Эхлэх» / restore, released at the end.
 * - **Health** (AC 24): 3 deaths of `:routing` in 10 min disable on-device routing until the next app start or a new
 *   routing file.
 * - **Lifecycle** (AC 21, 26): `BIND_IMPORTANT` while guiding; pre-bind when the validated network is lost during
 *   guidance; unbind on `onTrimMemory(RUNNING_CRITICAL)` (or background trim levels) when not guiding.
 */
@Singleton
class OnDeviceRouting internal constructor(
    context: Context,
    private val network: NetworkStateSource,
    private val source: InstalledRoutingSource,
    private val engineFactory: (onDeath: () -> Unit) -> RoutingEngineHandle,
    private val enabledBuild: Boolean,
    private val elapsed: () -> Long,
    private val log: DebugLog,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        network: mn.navmn.app.net.NetworkMonitor,
        replay: Optional<ReplayVariant>,
    ) : this(
        context = context,
        network = network,
        source = PackFiles(File(context.noBackupFilesDir, PackFiles.PACKS_DIR), routingLog()),
        engineFactory = { onDeath -> RoutingEngineHandle.Bound(BoundRoutingEngine(context, onDeath, routingLog())) },
        enabledBuild = !replay.isPresent,
        elapsed = { SystemClock.elapsedRealtime() },
        log = routingLog(),
    )

    val health = RoutingHealth()
    val policy = OnlineFirstPolicy()
    private val lock = Any()
    private var guiding = false
    private var pinned: InstalledRouting? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The engine client; created on first use, so without an installed file nothing about `:routing` exists. */
    private val engineLazy = lazy { engineFactory { health.onDeath(elapsed(), routingForRequest()?.key) } }
    val engine: RoutingEngineHandle by engineLazy
    private val requestExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "navmn-ondevice").apply { isDaemon = true } }

    init {
        scope.launch { network.newlyValidated.collect { policy.onNewlyValidated() } }
        scope.launch { network.validated.drop(1).collect { if (!it) onNetworkLost() } }
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) = this@OnDeviceRouting.onTrimMemory(level)
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = this@OnDeviceRouting.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            },
        )
    }

    /** The active compatible routing file (ignores the guidance pin). */
    fun installed(): InstalledRouting? = if (enabledBuild) source.current() else null

    /** The file for the next on-device request: the guidance session's pinned version while guiding (AC 17). */
    fun routingForRequest(): InstalledRouting? = synchronized(lock) { if (guiding) pinned else installed() }

    /** A usable routing file and on-device routing not disabled (AC 13, 24). Cheap; called per request and per fix. */
    fun available(): Boolean {
        val r = routingForRequest() ?: return false
        return health.enabled(r.key)
    }

    fun onGuidanceStarted() {
        val prebind = synchronized(lock) {
            guiding = true
            pinned = installed()
            pinned != null
        }
        if (!prebind) return
        engine.setImportant(true)
        if (!network.isOnline() && available()) engine.prebind()
    }

    fun onGuidanceEnded() {
        val had = synchronized(lock) {
            val h = pinned != null
            guiding = false
            pinned = null
            h
        }
        if (had) engine.setImportant(false)
    }

    /** AC 21: the validated network was lost during guidance → bind now, before an off-route needs the engine. */
    internal fun onNetworkLost() {
        val g = synchronized(lock) { guiding }
        if (g && available()) {
            log.d("routing pre-bind (network lost during guidance)")
            engine.prebind()
        }
    }

    /** AC 26: memory pressure while not guiding → drop the binding so `:routing` can be reclaimed. */
    internal fun onTrimMemory(level: Int) {
        val g = synchronized(lock) { guiding }
        if (g) return
        @Suppress("DEPRECATION")
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (critical && engineLazy.isInitialized()) engine.unbind()
    }

    /** The composed route transport (R7): the preview, reroutes and the NAV-012 restore use it. */
    fun requester(online: RouteClient): FallbackRouteRequester = FallbackRouteRequester(
        online = online,
        onDevice = OnDeviceRouteRequester(EngineProxy(), online.processor, requestExecutor, ::routingForRequest),
        policy = policy,
        clock = SystemRoutingClock,
        validated = { network.isOnline() },
        onDeviceAvailable = ::available,
        log = log,
    )

    /** Defers creating the engine client until the first on-device request. */
    private inner class EngineProxy : OnDeviceEngine {
        override fun route(routing: InstalledRouting, requestJson: String, timeoutMs: Long): EngineAnswer =
            engine.route(routing, requestJson, timeoutMs)
    }

    companion object {
        private fun routingLog(): DebugLog =
            if (BuildConfig.DEBUG_LOGS) DebugLog { android.util.Log.d("navmn.routing", it) } else DebugLog.NONE
    }
}

/** The engine client plus its lifecycle controls (a seam for JVM tests of [OnDeviceRouting]). */
interface RoutingEngineHandle : OnDeviceEngine {
    fun prebind()
    fun setImportant(value: Boolean)
    fun unbind()

    class Bound(val client: BoundRoutingEngine) : RoutingEngineHandle {
        override fun route(routing: InstalledRouting, requestJson: String, timeoutMs: Long) = client.route(routing, requestJson, timeoutMs)
        override fun prebind() = client.prebind()
        override fun setImportant(value: Boolean) = client.setImportant(value)
        override fun unbind() = client.unbind()
    }
}

/** Elapsed real time and one shared timer thread for the 3.0 s header budget. */
object SystemRoutingClock : RoutingClock {
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "navmn-route-budget").apply { isDaemon = true }
    }

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, block: () -> Unit): Cancelable {
        val f = timer.schedule(block, delayMs, TimeUnit.MILLISECONDS)
        return Cancelable { f.cancel(false) }
    }
}
