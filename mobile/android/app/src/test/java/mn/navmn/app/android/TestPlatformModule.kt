package mn.navmn.app.android

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import mn.navmn.app.di.PlatformModule
import mn.navmn.app.geo.LatLon
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationSource
import mn.navmn.app.map.CameraRules
import mn.navmn.app.map.MapCamera
import mn.navmn.app.map.MapContent
import mn.navmn.app.map.MapSurface
import mn.navmn.app.ui.theme.TokenColours
import mn.navmn.app.voice.GuidanceVoice
import mn.navmn.app.voiceplan.SpokenPrompt
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton

/**
 * Fake platform location (ADR-0009 §10): counts the listeners each flow would have registered with LocationManager
 * (one per active collection), so tests can assert "0 listeners while stopped / while guiding" (Amendment 1 §9).
 */
object FakeLocation : LocationSource {
    @Volatile var servicesOn = true
    val mapListeners = AtomicInteger(0)
    val guidanceListeners = AtomicInteger(0)
    /** Every map-flow registration ever made (a stopped Activity must not add any). */
    val mapRegistrations = AtomicInteger(0)
    val mapFixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    val guidanceFixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    @Volatile var freshFix: () -> Fix? = { goodFix() }

    fun goodFix(lat: Double = 47.9189, lon: Double = 106.9176) =
        Fix(lat, lon, 5.0, 180.0, 10.0, 10.0, SystemClock.elapsedRealtime(), System.currentTimeMillis())

    fun reset() {
        servicesOn = true
        mapListeners.set(0)
        guidanceListeners.set(0)
        mapRegistrations.set(0)
        freshFix = { goodFix() }
    }

    override fun servicesEnabled(): Boolean = servicesOn

    override fun guidanceUpdates(): Flow<Fix> = callbackFlow {
        guidanceListeners.incrementAndGet()
        val job = launch { guidanceFixes.collect { send(it) } }
        awaitClose {
            job.cancel()
            guidanceListeners.decrementAndGet()
        }
    }

    override fun mapUpdates(): Flow<Fix> = callbackFlow {
        mapListeners.incrementAndGet()
        mapRegistrations.incrementAndGet()
        val job = launch { mapFixes.collect { send(it) } }
        awaitClose {
            job.cancel()
            mapListeners.decrementAndGet()
        }
    }

    override suspend fun freshGoodFix(timeoutMs: Long): Fix? = freshFix()
}

/** No-op camera behind the recording surface. */
object FakeCamera : MapCamera {
    val styles = AtomicInteger(0)
    override fun setStyle(night: Boolean, colours: TokenColours, pmtilesUrl: String) {
        styles.incrementAndGet()
    }
    override fun moveTo(p: LatLon, zoom: Double) = Unit
    override fun easeTo(p: LatLon, zoom: Double?, durationMs: Int) = Unit
    override fun fit(points: List<LatLon>, left: Int, top: Int, right: Int, bottom: Int) = Unit
    override fun follow(target: LatLon, bearing: Double, tilt: Double, zoom: Double, padding: CameraRules.Padding, animate: Boolean) = Unit
    override fun resetNorth() = Unit
    override fun zoomBy(delta: Double) = Unit
    override val bearing: Double get() = 0.0
    override val center: LatLon? get() = null
    override val zoom: Double get() = 12.0
    override val heightPx: Int get() = 1_000
}

/**
 * Recording map surface for Robolectric (MapLibre's native library does not load on the JVM): one node with the map's
 * content description, the last [MapContent], and a [longPress] that calls the screen's long-press handler.
 */
object RecordingMapSurface : MapSurface {
    @Volatile var content: MapContent? = null
    @Volatile private var onLongPress: ((LatLon) -> Unit)? = null

    fun reset() {
        content = null
        onLongPress = null
    }

    /** Simulates a long-press on the map; false when the screen has no long-press handler (guidance, preview). */
    fun longPress(p: LatLon): Boolean {
        val cb = onLongPress ?: return false
        cb(p)
        return true
    }

    @Composable
    override fun Map(
        modifier: Modifier,
        description: String,
        night: Boolean,
        colours: TokenColours,
        pmtilesUrl: String,
        content: MapContent,
        onReady: (MapCamera) -> Unit,
        onLongPress: ((LatLon) -> Unit)?,
        onGesture: () -> Unit,
        onCameraIdle: (center: LatLon, bearing: Double, zoom: Double) -> Unit,
        onFailed: () -> Unit,
    ) {
        SideEffect {
            this.content = content
            this.onLongPress = onLongPress
        }
        LaunchedEffect(Unit) { onReady(FakeCamera) }
        Box(modifier.semantics { contentDescription = description })
    }
}

/** Records every prompt the engine plays (AC 64: 0 repeated prompts) and completes it at once. */
object RecordingVoice : GuidanceVoice {
    val played: MutableList<SpokenPrompt> = Collections.synchronizedList(ArrayList())
    @Volatile override var onDone: ((Long) -> Unit)? = null
    @Volatile override var onFallback: (() -> Unit)? = null

    fun reset() = played.clear()

    override fun play(prompt: SpokenPrompt) {
        played += prompt
        onDone?.invoke(prompt.id)
    }

    override fun stop() = Unit
    override fun prepare() = Unit
    override fun newSession() = Unit
    override fun onLanguageChanged() = Unit
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PlatformModule::class])
object TestPlatformModule {
    @Provides @Singleton fun location(): LocationSource = FakeLocation
    @Provides @Singleton fun mapSurface(): MapSurface = RecordingMapSurface
    @Provides @Singleton fun voice(): GuidanceVoice = RecordingVoice
}
