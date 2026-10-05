package mn.navmn.app.demo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.navmn.app.demo.replay.DemoCatalogue
import mn.navmn.app.demo.replay.DemoEntry
import mn.navmn.app.demo.replay.OpenedDemoRoute
import mn.navmn.app.demo.replay.ReplayLocationSource
import mn.navmn.app.engine.FerrostarRouteParser
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.alternatives.PreviewRoutes

/** The picker list (UX P1 states). */
sealed interface PickerState {
    data object Loading : PickerState

    data class Ready(val entries: List<DemoEntry>) : PickerState

    /** The packaged manifest is unreadable (should not happen: the build validates it). */
    data object Failed : PickerState
}

/**
 * ADR-0016 §12: reads the packaged manifest once (AC 6: the picker within 2 s, summaries without a Ferrostar parse) and
 * opens an entry through the normal preview parse path on a background thread (AC 9: within 1 s). A failure shows
 * «Алдаа гарлаа» under that entry and arms nothing (AC 8); a second tap while loading is ignored.
 */
class DemoPicker(private val context: Context, private val scope: CoroutineScope, private val source: ReplayLocationSource) {
    private val _state = MutableStateFlow<PickerState>(PickerState.Loading)
    val state: StateFlow<PickerState> = _state.asStateFlow()

    private val _failed = MutableStateFlow<String?>(null)

    /** The id of the entry whose files could not be read (inline error row). */
    val failed: StateFlow<String?> = _failed.asStateFlow()

    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening.asStateFlow()

    private val processor by lazy { RouteProcessor(FerrostarRouteParser()) }

    private fun read(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    fun load() {
        scope.launch(Dispatchers.IO) {
            _state.value = runCatching { PickerState.Ready(DemoCatalogue.load(::read)) }.getOrElse { PickerState.Failed }
        }
    }

    fun select(entry: DemoEntry, onOpened: (OpenedDemoRoute) -> Unit) {
        if (_opening.value != null) return
        _opening.value = entry.id
        scope.launch(Dispatchers.IO) {
            val result = DemoCatalogue.open(entry, ::read) { bytes -> PreviewRoutes.process(processor, bytes, 0) }
            withContext(Dispatchers.Main) {
                _opening.value = null
                result.onSuccess { opened ->
                    _failed.value = null
                    source.arm(opened.track)
                    onOpened(opened)
                }.onFailure { _failed.value = entry.id }
            }
        }
    }
}
