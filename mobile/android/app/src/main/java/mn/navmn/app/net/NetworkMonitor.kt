package mn.navmn.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAV-021 R7 (ADR-0017 §5): the network facts the online-first policy needs; NAV-022 (Wi-Fi vs mobile data) and
 * NAV-023 reuse it.
 */
interface NetworkStateSource {
    /** The default network has `NET_CAPABILITY_VALIDATED` (NAV-021 Terms "Validated network"). */
    val validated: StateFlow<Boolean>

    /** The default network has `NET_CAPABILITY_NOT_METERED` (NAV-022: Wi-Fi = validated and not metered). */
    val unmetered: StateFlow<Boolean>

    /** One event each time the network becomes validated after it was not (NAV-021 AC 11 "newly validated"). */
    val newlyValidated: Flow<Unit>

    fun isOnline(): Boolean
}

/**
 * ADR-0009 §5 network: the default network with NET_CAPABILITY_VALIDATED (AC 50, 54). Drives the offline indicator and
 * the reroute policy (P8); guidance itself never depends on it.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) : NetworkStateSource {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _validated = MutableStateFlow(currentlyValidated())
    override val validated: StateFlow<Boolean> = _validated.asStateFlow()
    private val _unmetered = MutableStateFlow(currentlyUnmetered())
    override val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()
    override val newlyValidated: Flow<Unit> = validated.drop(1).filter { it }.map { }

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        _validated.value = caps.isValidated()
                        _unmetered.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }

                    override fun onLost(network: Network) {
                        _validated.value = false
                        _unmetered.value = false
                    }
                },
            )
        }
    }

    private fun NetworkCapabilities.isValidated() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun currentlyValidated(): Boolean =
        runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.isValidated() ?: false }.getOrDefault(false)

    private fun currentlyUnmetered(): Boolean = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false
    }.getOrDefault(false)

    override fun isOnline(): Boolean = _validated.value
}
