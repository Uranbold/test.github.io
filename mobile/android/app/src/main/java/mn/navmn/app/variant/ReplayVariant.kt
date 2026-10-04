package mn.navmn.app.variant

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import mn.navmn.app.engine.Clock
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.Strings
import mn.navmn.app.location.LocationSource
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.TravelMode
import mn.navmn.app.voice.GuidanceVoice
import okhttp3.Interceptor

/**
 * ADR-0016 §3: the one optional binding through which a replay build (the NAV-019 `demo` build type) changes the app.
 * Only the demo source set provides it; when it is absent (debug, release, every existing test) each call site keeps
 * exactly today's code path. Generic on purpose: no demo logic and no `BuildConfig` branches live in `src/main`.
 */
interface ReplayVariant {
    /** Engine clock: replay time, frozen while paused (§4.4). */
    val clock: Clock

    /** Simulated fixes along the recorded track; never registers a platform location listener (AC 13). */
    val location: LocationSource

    /** Pause gate around the real TTS / chime path (§11). Called once (singleton binding). */
    fun voice(real: GuidanceVoice): GuidanceVoice

    /** First application interceptor of the app's single OkHttpClient: blocks every request in-process (§7). */
    val httpInterceptor: Interceptor

    /** The basemap: [TilesState.Loading] while the bundled archive is copied, then ready or failed (§9). */
    val tiles: StateFlow<TilesState>

    /** True when the basemap is bundled (no network needed): the S5 offline status is not shown (UX Design note 4). */
    val tilesBundled: Boolean

    fun retryTiles()

    /**
     * NavApplication.onCreate: the tile copy and the picker catalogue (§9). Must not touch MapLibre: nothing has called
     * `MapLibre.getInstance` yet, and MapLibre's HTTP classes throw in their static initialiser before it (bug
     * B-NAV019-01, the demo crashed on open).
     */
    fun onApplicationCreate(app: Application)

    /**
     * Called by the map right after `MapLibre.getInstance(context)` and before the first `MapView` is created, on every
     * map creation (implementations make it idempotent). The demo gives MapLibre its own HTTP client here (§7): no
     * MapLibre request can precede this point.
     */
    fun onMapLibreInitialised() {}

    /** The idle screen (route picker, UX P1) in place of the browse overlay. */
    @Composable
    fun StartScreen(host: ReplayHost, modifier: Modifier)

    /** The demo row RD under the guidance banner (badge, pause / resume; badge only on arrival, UX P3/P4). */
    @Composable
    fun GuidanceControls(arrived: Boolean, modifier: Modifier)

    /** The «Туршилтын горим» badge pill (UX P2). */
    @Composable
    fun Badge(modifier: Modifier)
}

/** ADR-0016 §9: what the map shows. */
sealed interface TilesState {
    data object Loading : TilesState

    data class Ready(val pmtilesUrl: String) : TilesState

    data object Failed : TilesState
}

/** ADR-0016 §3: what the start screen may do in the app (main owns the preview and settings state). */
class ReplayHost(
    val lang: Lang,
    val strings: Strings,
    /** The map reported a load failure (NavMap onFailed) although the archive was ready. */
    val mapFailed: Boolean,
    /** Opens the normal route preview for a recorded response with 0 requests (PreviewController.showRoute). */
    val openPreview: (route: RouteOutcome.Ok, origin: RoutePoint, destination: RoutePoint, mode: TravelMode) -> Unit,
    val openSettings: () -> Unit,
    /** «Дахин оролдох» on the tiles card. */
    val retryTiles: () -> Unit,
)

/** Declares the optional binding; only the demo source set binds [ReplayVariant]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VariantModule {
    @BindsOptionalOf
    abstract fun replayVariant(): ReplayVariant
}
