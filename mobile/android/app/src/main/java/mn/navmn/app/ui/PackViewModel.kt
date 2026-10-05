package mn.navmn.app.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.navmn.app.pack.JobMode
import mn.navmn.app.pack.MobileConfirm
import mn.navmn.app.pack.PackManager
import mn.navmn.app.pack.PackMessage
import mn.navmn.app.pack.PackOffer
import mn.navmn.app.pack.PackState
import javax.inject.Inject

/**
 * NAV-022 UI state of the offline map (MVVM, unidirectional): the app-wide [PackManager] state plus the one-shot
 * request to open S7 at the pack section (B4). Kept apart from [AppViewModel] so the NAV-005…NAV-019 screens and
 * their tests are unchanged when the pack is absent.
 */
@HiltViewModel
class PackViewModel @Inject constructor(private val manager: PackManager) : ViewModel() {
    val enabled: Boolean get() = manager.enabled
    val state: StateFlow<PackState> = manager.state
    val offer: StateFlow<PackOffer?> = manager.offer
    val message: StateFlow<PackMessage?> = manager.message
    val confirm: StateFlow<MobileConfirm?> = manager.confirm

    private val _scrollToSection = MutableStateFlow(false)
    /** B4: S7 opens scrolled to the pack section (from a pack notification or message). */
    val scrollToSection: StateFlow<Boolean> = _scrollToSection.asStateFlow()

    fun requestSection() {
        _scrollToSection.value = true
    }

    fun onSectionShown() {
        _scrollToSection.value = false
    }

    fun onBrowseShown() = manager.onBrowseShown()
    fun onForeground() = manager.onForeground()
    fun onBackground() = manager.onBackground()
    fun onSettingsOpened() = manager.onSettingsOpened()
    fun onOfferShown(offer: PackOffer) = manager.onOfferShown(offer)
    fun onOfferClosed(offer: PackOffer, accepted: Boolean) = manager.onOfferClosed(offer, accepted)
    fun download() {
        manager.download(JobMode.USER)
    }
    fun cancel() {
        manager.cancel()
    }
    fun delete() {
        manager.delete()
    }
    fun confirmMobileData() = manager.confirmMobileData()
    fun waitForWifi() = manager.waitForWifi()
    fun dismissMessage() = manager.dismissMessage()
    fun setMapTilesInUse(path: String?) = manager.setMapTilesInUse(path)

    /** The installed basemap of [s] for the map style (AC 31), or null (online PMTiles, AC 34). */
    fun tilesPath(s: PackState?): String? = s?.let { manager.installedTiles(it.installed)?.absolutePath }
}
