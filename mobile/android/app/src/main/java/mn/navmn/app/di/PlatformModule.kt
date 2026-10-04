package mn.navmn.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.location.LocationSource
import mn.navmn.app.location.PlatformLocationSource
import mn.navmn.app.map.MapSurface
import mn.navmn.app.ui.components.MapLibreSurface
import mn.navmn.app.variant.ReplayVariant
import mn.navmn.app.voice.GuidanceVoice
import mn.navmn.app.voice.VoiceOutput
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Platform seams (ADR-0009 §10): location, map view and voice. Robolectric Activity tests replace this module with
 * fakes (MapLibre's and the TTS engine's native parts do not run on the JVM). ADR-0016 §3: a replay build supplies the
 * location source and wraps the real voice; [PlatformLocationSource] is then never constructed.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides @Singleton
    fun location(replay: Optional<ReplayVariant>, platform: Provider<PlatformLocationSource>): LocationSource =
        if (replay.isPresent) replay.get().location else platform.get()

    @Provides
    fun mapSurface(impl: MapLibreSurface): MapSurface = impl

    @Provides @Singleton
    fun voice(replay: Optional<ReplayVariant>, real: VoiceOutput): GuidanceVoice =
        if (replay.isPresent) replay.get().voice(real) else real
}
