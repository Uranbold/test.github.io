package mn.navmn.app.background

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import mn.navmn.app.background.battery.BatteryHint
import mn.navmn.app.background.battery.BatteryHintRules
import mn.navmn.app.background.battery.BatteryHintSlot
import mn.navmn.app.background.battery.LocalBatteryHint
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState

/**
 * NAV-012 state the root composable needs from the Activity: the sunrise/sunset night flag for «Автомат» (T1) and the
 * battery hint (H1/H2). Kept in one holder so the shared root only gains one parameter.
 */
class BackgroundUi(
    val autoNight: StateFlow<Boolean>,
    val battery: BatteryHint,
)

/** The NAV-012 holder inside the root composable (null in tests and callers that do not wire NAV-012). */
val LocalBackgroundUi = staticCompositionLocalOf<BackgroundUi?> { null }

/**
 * Provides [LocalBatteryHint] to the route-preview sheet (screen spec Layout rule 10): visible only with a route on
 * the preview, never during guidance (AC 26). When a hint that was shown because of a restore leaves the screen, the
 * one after-restore showing is used up.
 */
@Composable
fun ProvideBatteryHint(
    battery: BatteryHint?,
    preview: StateFlow<PreviewState?>,
    guidance: StateFlow<GuidanceState?>,
    onExpand: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (battery == null) {
        content()
        return
    }
    val p by preview.collectAsState()
    val g by guidance.collectAsState()
    val restricted by battery.restricted.collectAsState()
    val dismissedAt by battery.dismissedAt.collectAsState()
    val afterRestore by battery.pendingAfterRestore.collectAsState()
    val visible = BatteryHintRules.show(
        BatteryHintRules.Input(
            restricted = restricted,
            routeShown = p?.result is PreviewResult.Route,
            guidanceActive = g != null,
            overLockScreen = false, // the preview is never shown over the lock screen (LockScreenPolicy)
            dismissedAtWallMs = dismissedAt,
            pendingAfterRestore = afterRestore,
            nowWallMs = System.currentTimeMillis(),
        ),
    )
    if (visible && afterRestore) {
        DisposableEffect(Unit) { onDispose { battery.consumeAfterRestore() } }
    }
    val slot = BatteryHintSlot(visible, onExpand, onDismiss = { battery.dismiss() }, onOpenSettings = onOpenSettings)
    CompositionLocalProvider(LocalBatteryHint provides slot, content = content)
}
