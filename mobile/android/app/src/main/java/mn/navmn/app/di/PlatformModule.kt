package mn.navmn.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.location.LocationSource
import mn.navmn.app.location.PlatformLocationSource
import mn.navmn.app.map.MapSurface
import mn.navmn.app.ui.components.MapLibreSurface
import mn.navmn.app.voice.GuidanceVoice
import mn.navmn.app.voice.VoiceOutput

/**
 * Platform seams (ADR-0009 §10): location, map view and voice. Robolectric Activity tests replace this module with
 * fakes (MapLibre's and the TTS engine's native parts do not run on the JVM).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {
    @Binds abstract fun location(impl: PlatformLocationSource): LocationSource

    @Binds abstract fun mapSurface(impl: MapLibreSurface): MapSurface

    @Binds abstract fun voice(impl: VoiceOutput): GuidanceVoice
}
