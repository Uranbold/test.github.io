package mn.navmn.app.demo

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mn.navmn.app.demo.replay.DemoCatalogue
import mn.navmn.app.demo.replay.ReplayWakeLock
import mn.navmn.app.demo.replay.TileArchiveCopier
import mn.navmn.app.variant.TilesState
import java.io.File

/**
 * ADR-0016 §4.5: a partial wake lock (tag `navmn:demo-replay`) while the replay runs, so a screen-off replay keeps its
 * timing (no GNSS wake-ups in a simulation). Released on pause, arrival, end and cancellation; always with a timeout.
 */
class AndroidReplayWakeLock(context: Context) : ReplayWakeLock {
    private val lock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        ?.apply { setReferenceCounted(false) }

    override fun acquire(timeoutMs: Long) {
        runCatching { lock?.acquire(timeoutMs.coerceAtLeast(1_000L)) }
    }

    override fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    companion object {
        const val TAG = "navmn:demo-replay"
    }
}

/**
 * ADR-0016 §9: the basemap of the demo build. File mode copies `demo/basemap.pmtiles` once per installed version to
 * no-backup storage and publishes `pmtiles://file://<path>`; URL mode publishes `pmtiles://<nav.demoTilesUrl>` at once.
 * A missing asset, a short copy or a bad header gives [TilesState.Failed] («Газрын зургийг ачаалж чадсангүй»).
 */
class DemoTiles(private val context: Context, private val tilesUrl: String) {
    private val _state = MutableStateFlow<TilesState>(TilesState.Loading)
    val state: StateFlow<TilesState> = _state.asStateFlow()
    private var job: Job? = null

    fun prepare(scope: CoroutineScope) {
        if (tilesUrl.isNotBlank()) {
            _state.value = TilesState.Ready("pmtiles://$tilesUrl")
            return
        }
        if (job?.isActive == true) return
        _state.value = TilesState.Loading
        job = scope.launch {
            _state.value = runCatching {
                @Suppress("DEPRECATION")
                val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
                val length = runCatching { context.assets.openFd(DemoCatalogue.TILES_ASSET).use { it.length } }.getOrDefault(-1L)
                val file = TileArchiveCopier(File(context.noBackupFilesDir, DIR)).prepare(version, length) {
                    context.assets.open(DemoCatalogue.TILES_ASSET)
                }
                TilesState.Ready(TileArchiveCopier.pmtilesUrl(file))
            }.getOrElse { TilesState.Failed }
        }
    }

    companion object {
        const val DIR = "demo-tiles"
    }
}
