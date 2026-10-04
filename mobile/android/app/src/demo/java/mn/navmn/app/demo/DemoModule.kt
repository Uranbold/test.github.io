package mn.navmn.app.demo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.variant.ReplayVariant

/** ADR-0016 §3: the demo build type is the only place that binds the optional [ReplayVariant]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DemoModule {
    @Binds
    abstract fun replayVariant(impl: DemoVariant): ReplayVariant
}
