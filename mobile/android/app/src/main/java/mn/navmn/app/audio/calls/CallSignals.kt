package mn.navmn.app.audio.calls

import android.content.Context
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transient loss of the prompt's audio focus (AC 35 second half). Set by the voice output's focus listener while a
 * prompt holds focus; cleared when the app regains or abandons focus (after abandoning there are no more callbacks,
 * so a stuck "lost" flag would block every later prompt).
 */
object AudioFocusSignals {
    private val _transientLoss = MutableStateFlow(false)
    val transientLoss: StateFlow<Boolean> = _transientLoss.asStateFlow()

    fun onFocusChange(change: Int) {
        _transientLoss.value = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }

    fun onAbandoned() {
        _transientLoss.value = false
    }
}

/** The raw "call in progress" signal for the guidance engine (AC 35). */
fun interface CallSignals {
    fun inCall(): Flow<Boolean>
}

/**
 * ADR-0013 §6.1 without phone-state permissions: the `AudioManager` mode, from `addOnModeChangedListener` on API 31+
 * (plus one read at subscription) and polled every [POLL_MS] on API 26–30 (AC 36's 500 ms), combined with the focus
 * signal. Collected only while guidance runs (the engine's scope).
 */
@Singleton
class AudioModeCallSignals @Inject constructor(@ApplicationContext private val context: Context) : CallSignals {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    private fun modes(): Flow<Int> {
        val am = audio ?: return flow { emit(CallModes.MODE_NORMAL) }
        return callbackFlow {
            val listener = if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    val l = AudioManager.OnModeChangedListener { trySend(it) }
                    am.addOnModeChangedListener(Executor { it.run() }, l)
                    l
                }.getOrNull()
            } else {
                null
            }
            trySend(runCatching { am.mode }.getOrDefault(CallModes.MODE_NORMAL))
            val poll = if (listener == null) {
                launch {
                    while (true) {
                        delay(POLL_MS)
                        trySend(runCatching { am.mode }.getOrDefault(CallModes.MODE_NORMAL))
                    }
                }
            } else {
                null
            }
            awaitClose {
                poll?.cancel()
                if (listener != null && Build.VERSION.SDK_INT >= 31) runCatching { am.removeOnModeChangedListener(listener) }
            }
        }
    }

    override fun inCall(): Flow<Boolean> =
        combine(modes(), AudioFocusSignals.transientLoss) { mode, lost -> CallModes.isCall(mode) || lost }.distinctUntilChanged()

    companion object {
        const val POLL_MS = 250L
    }
}
