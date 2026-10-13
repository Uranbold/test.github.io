package mn.navmn.app.voice.pack

import android.content.Context
import android.content.res.AssetManager
import mn.navmn.app.BuildConfig
import mn.navmn.app.log.DebugLog
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The pack bundled in the APK assets `voicepack/` (NAV-016 AC 20). Read-only, local: 0 network requests (AC 37). */
class AssetPackFiles(private val assets: AssetManager, private val prefix: String = "voicepack/") : PackFiles {
    override fun read(path: String): ByteArray? = runCatching { assets.open(prefix + path).use { it.readBytes() } }.getOrNull()
}

/**
 * NAV-016 M6 (AC 39, 40): the pack decision of this process. [prepare] starts the V1–V10 validation once on a
 * background thread (route preview open, the first prompt of a restored session); [state] is [PackState.Pending] until
 * it finishes. A new process (NAV-012 restore after process death) starts again from Pending and validates again,
 * offline. The reason of an invalid pack goes only to the debug log, never with coordinates (AC 44).
 */
class VoicePackSource(
    val files: PackFiles,
    private val inventory: () -> VoiceInventory,
    private val policy: PackPolicy,
    private val log: DebugLog,
    private val executor: Executor,
) {
    @Volatile var state: PackState = PackState.Pending
        private set

    private val started = AtomicBoolean(false)

    fun prepare() {
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            val s = runCatching { VoicePackValidator.validate(files, inventory(), policy) }
                .getOrElse { PackState.Invalid(PackReason.MANIFEST_UNREADABLE) }
            state = s
            log.d(
                when (s) {
                    is PackState.Valid -> "voice pack valid: ${s.pack.voiceId} v${s.pack.packVersion} (${s.pack.entries.size} clips)"
                    is PackState.Invalid -> "voice pack fallback: ${s.reason}"
                    PackState.Pending -> "voice pack pending"
                },
            )
        }
    }

    companion object {
        @Volatile private var app: VoicePackSource? = null

        /** The process-wide source over the APK assets with the build's policy (BuildConfig.VOICE_PACK_POLICY). */
        fun app(context: Context, log: DebugLog): VoicePackSource =
            app ?: synchronized(this) {
                app ?: VoicePackSource(
                    files = AssetPackFiles(context.applicationContext.assets),
                    inventory = { VoiceInventory.fromAssets(context.applicationContext) },
                    policy = PackPolicy.of(BuildConfig.VOICE_PACK_POLICY),
                    log = log,
                    executor = Executors.newSingleThreadExecutor { r -> Thread(r, "navmn-voicepack").apply { isDaemon = true } },
                ).also { app = it }
            }
    }
}
