package mn.navmn.app.demo

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import mn.navmn.app.BuildConfig
import mn.navmn.app.demo.replay.DemoNetworkBlock
import mn.navmn.app.demo.replay.PauseGatedVoice
import mn.navmn.app.demo.replay.ReplayClock
import mn.navmn.app.demo.replay.ReplayLocationSource
import mn.navmn.app.demo.replay.TileRequestPolicy
import mn.navmn.app.engine.Clock
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.location.LocationSource
import mn.navmn.app.variant.ReplayHost
import mn.navmn.app.variant.ReplayVariant
import mn.navmn.app.variant.TilesState
import mn.navmn.app.voice.GuidanceVoice
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * NAV-019 / ADR-0016: the demo build's [ReplayVariant]. Only the location source (a replay of the recorded track), the
 * engine clock (replay time) and the network (none) differ from the real app; the guidance engine, Ferrostar, the
 * voice path, the foreground service, the notification and the lock-screen gate run unchanged.
 */
@Singleton
class DemoVariant @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Provider: the session itself depends on this variant (location, clock). */
    private val session: Provider<GuidanceSession>,
) : ReplayVariant {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replayClock = ReplayClock(realNow = { SystemClock.elapsedRealtime() })
    private val locationManager: LocationManager? = context.getSystemService(LocationManager::class.java)

    private val source = ReplayLocationSource(
        clock = replayClock,
        // Reads the system setting only (Android 14+ FGS prerequisite, ADR-0016 §5); no listener is registered.
        servicesEnabled = { locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false },
        wakeLock = AndroidReplayWakeLock(context),
    ).apply {
        // AC 25: the track ended without arrival → the «Дуусгах» path (service stops, back to the picker).
        onTrackEnd = { session.get().end() }
    }

    private var gate: PauseGatedVoice? = null
    private val tilesUrl: String = BuildConfig.DEMO_TILES_URL
    private val demoTiles = DemoTiles(context, tilesUrl)
    private val picker = DemoPicker(context, scope, source)

    override val clock: Clock = replayClock
    override val location: LocationSource = source
    override val httpInterceptor: Interceptor = DemoNetworkBlock()
    override val tiles: StateFlow<TilesState> = demoTiles.state
    override val tilesBundled: Boolean = tilesUrl.isBlank()

    override fun voice(real: GuidanceVoice): GuidanceVoice = PauseGatedVoice(real) { replayClock.paused.value }.also { gate = it }

    override fun retryTiles() = demoTiles.prepare(scope)

    /** Main thread (the map's first composition); set once per process. */
    private var mapLibreClientSet = false

    override fun onApplicationCreate(app: Application) {
        // B-NAV019-01: no MapLibre call here. Before MapLibre.getInstance, HttpRequestUtil's static initialiser throws
        // (MapLibreConfigurationException → ExceptionInInitializerError: the demo crashed on open). See onMapLibreInitialised.
        demoTiles.prepare(scope)
        picker.load()
    }

    override fun onMapLibreInitialised() {
        if (mapLibreClientSet) return
        mapLibreClientSet = true
        // ADR-0016 §7: MapLibre's own HTTP client refuses every request (file mode) or allows only the archive URL. The map
        // calls this right after MapLibre.getInstance and before its first MapView, so no MapLibre request precedes it.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder().cache(null).addInterceptor(TileRequestPolicy(tilesUrl.ifBlank { null })).build(),
        )
    }

    /** «Түр зогсоох» (AC 22): the replay clock stops, emission stops, the utterance stops, the wake lock is released. */
    fun pause() {
        if (replayClock.pause()) gate?.onPaused()
    }

    /** «Үргэлжлүүлэх»: the replay continues from the same track time. */
    fun resume() {
        replayClock.resume()
    }

    @Composable
    override fun StartScreen(host: ReplayHost, modifier: Modifier) {
        val list by picker.state.collectAsState()
        val failed by picker.failed.collectAsState()
        val opening by picker.opening.collectAsState()
        val tilesState by tiles.collectAsState()
        DemoPickerScreen(
            host = host,
            list = list,
            failedId = failed,
            openingId = opening,
            tiles = tilesState,
            onSelect = { entry ->
                picker.select(entry) { o -> host.openPreview(o.outcome, o.origin, o.destination, o.entry.mode) }
            },
            modifier = modifier,
        )
    }

    @Composable
    override fun GuidanceControls(arrived: Boolean, modifier: Modifier) {
        val paused by replayClock.paused.collectAsState()
        DemoRow(arrived = arrived, paused = paused, onPause = ::pause, onResume = ::resume, modifier = modifier)
    }

    @Composable
    override fun Badge(modifier: Modifier) = DemoBadge(modifier)
}
