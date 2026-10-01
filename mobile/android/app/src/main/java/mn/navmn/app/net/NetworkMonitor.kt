package mn.navmn.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0009 §5 network: the default network with NET_CAPABILITY_VALIDATED (AC 50, 54). Drives the offline indicator and
 * the reroute policy (P8); guidance itself never depends on it.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _validated = MutableStateFlow(currentlyValidated())
    val validated: StateFlow<Boolean> = _validated.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        _validated.value = caps.isValidated()
                    }

                    override fun onLost(network: Network) {
                        _validated.value = false
                    }
                },
            )
        }
    }

    private fun NetworkCapabilities.isValidated() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun currentlyValidated(): Boolean =
        runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.isValidated() ?: false }.getOrDefault(false)

    fun isOnline(): Boolean = _validated.value
}
