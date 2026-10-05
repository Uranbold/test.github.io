package mn.navmn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.navmn.app.R
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.Templates
import mn.navmn.app.pack.JobUi
import mn.navmn.app.pack.PackKind
import mn.navmn.app.pack.PackMessage
import mn.navmn.app.pack.PackOffer
import mn.navmn.app.pack.PackRules
import mn.navmn.app.pack.PackSectionModel
import mn.navmn.app.pack.PackState
import mn.navmn.app.pack.SizeFormat
import mn.navmn.app.ui.components.MessageCard
import mn.navmn.app.ui.theme.LocalNight
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** NAV-022 Terms "Size display" with the resource units OF26 / OF27 and a no-break space. */
@Composable
fun packSize(bytes: Long, lang: Lang): String =
    SizeFormat.format(bytes, lang, stringResource(R.string.unit_mb), stringResource(R.string.unit_gb))

@Composable
private fun fill(res: Int, vararg values: Pair<String, String>): String = Templates.fill(stringResource(res), *values)

/** UI actions of the pack surfaces (unidirectional; implemented by the pack ViewModel). */
class PackActions(
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onOpenLicence: (String) -> Unit = {},
)

/**
 * O2: the «Офлайн газрын зураг» section at the end of S7 (screen spec F2, States). Status → facts → the one primary
 * action → (installed) dates, space used, divider, delete, attribution, ODbL line. The delete confirmation O5 is owned
 * here (AC 36, 37).
 */
@Composable
fun OfflineSection(state: PackState, lang: Lang, a: PackActions, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val m = PackSectionModel.of(state)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("offline-section")) {
        HorizontalDivider(color = t.uiOutlineVariant.c())
        Text(
            stringResource(R.string.offline_title),
            style = NavType.label,
            color = t.uiPrimary.c(),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        if (m.dates.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 24.dp).testTag("offline-dates")) {
                Text(stringResource(R.string.offline_data_date), style = NavType.label, color = t.uiOnSurfaceVariant.c())
                for ((kind, ts) in m.dates) {
                    val date = PackRules.dataDate(ts) ?: continue
                    val res = when (kind) {
                        PackKind.TILES -> R.string.offline_date_map
                        PackKind.ROUTING -> R.string.offline_date_routes
                        PackKind.SEARCH -> R.string.offline_date_search
                    }
                    Text(fill(res, "date" to date), style = NavType.bodyLarge.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurface.c())
                }
            }
        }
        m.spaceUsed?.let {
            Text(fill(R.string.offline_space_used, "size" to packSize(it, lang)), style = NavType.body, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        if (m.benefit) {
            Text(stringResource(R.string.offline_benefit), style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        m.status?.let { StatusRow(it, lang, m.primary, a) }
        m.downloadSize?.let { Fact(R.drawable.ic_download, fill(R.string.offline_download_size, "size" to packSize(it, lang))) }
        m.spaceNeeded?.let { Fact(R.drawable.ic_smartphone, fill(R.string.offline_space_needed, "size" to packSize(it, lang))) }
        when (m.primary) {
            PackSectionModel.Primary.DOWNLOAD -> PrimaryButton(stringResource(R.string.offline_download_action), "offline-download", a.onDownload)
            PackSectionModel.Primary.UPDATE -> PrimaryButton(stringResource(R.string.offline_update), "offline-update", a.onDownload)
            else -> Unit // CANCEL / RETRY live in the status row
        }
        if (m.delete) {
            HorizontalDivider(color = t.uiOutlineVariant.c(), modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp).testTag("offline-delete")) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = t.uiError.c(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.offline_delete), style = NavType.label, color = t.uiError.c())
            }
            Text(stringResource(R.string.attribution_osm), style = NavType.caption, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 24.dp))
            m.licenceUrl?.let { url ->
                TextButton(
                    onClick = { a.onOpenLicence(url) },
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp).testTag("offline-licence").semantics { role = Role.Button },
                ) {
                    Text(stringResource(R.string.offline_licence), style = NavType.label, color = t.uiPrimary.c())
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (confirmDelete) {
        DeleteDialog(state.installed.totalBytes, lang, onConfirm = {
            confirmDelete = false
            a.onDelete()
        }, onCancel = { confirmDelete = false })
    }
}

@Composable
private fun Fact(@DrawableRes icon: Int, text: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c())
    }
}

@Composable
private fun PrimaryButton(label: String, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp).testTag(tag)) {
        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = NavType.label)
    }
}

/**
 * The shared status row (screen spec Components): icon, text, M3 linear progress (indeterminate while checking), and
 * «Цуцлах» or «Дахин оролдох». Polite live region, announced at most once per 10 s while the percentage moves (AC 45).
 */
@Composable
private fun StatusRow(job: JobUi, lang: Lang, primary: PackSectionModel.Primary, a: PackActions) {
    val t = LocalTokens.current
    val text = when (job) {
        JobUi.Waiting -> stringResource(R.string.offline_waiting_wifi)
        is JobUi.Downloading -> fill(R.string.offline_downloading, "percent" to job.percent.toString())
        JobUi.Verifying -> fill(R.string.offline_downloading, "percent" to "100") // UX Open question 1 working assumption
        JobUi.Failed -> stringResource(R.string.offline_failed)
        is JobUi.NoSpace -> fill(R.string.offline_no_space, "size" to packSize(job.toFree, lang))
        JobUi.Idle -> ""
    }
    // AC 45: the announced text changes at most every 10 s (the visible text follows the 2 s updates).
    var spoken by remember { mutableStateOf(text) }
    var spokenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(text) {
        val now = System.currentTimeMillis()
        val wait = ANNOUNCE_EVERY_MS - (now - spokenAt)
        if (job is JobUi.Downloading && wait > 0) delay(wait)
        spoken = text
        spokenAt = System.currentTimeMillis()
    }
    val error = job == JobUi.Failed || job is JobUi.NoSpace
    val icon = when {
        error -> R.drawable.ic_warning
        job == JobUi.Waiting -> R.drawable.ic_wifi
        else -> R.drawable.ic_download
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag("offline-status")) {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = if (error) t.uiError.c() else t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = NavType.bodyLarge.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
        }
        when (job) {
            is JobUi.Downloading -> LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            JobUi.Verifying -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            else -> Unit
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when (primary) {
                PackSectionModel.Primary.CANCEL -> TextButton(onClick = a.onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("offline-cancel")) {
                    Text(stringResource(R.string.offline_cancel), style = NavType.label)
                }
                PackSectionModel.Primary.RETRY -> TextButton(onClick = a.onDownload, modifier = Modifier.heightIn(min = 48.dp).testTag("offline-retry")) {
                    Text(stringResource(R.string.action_retry), style = NavType.label)
                }
                else -> Unit
            }
        }
    }
}

/** O5 (F5): «Офлайн газрын зургийг устгах уу?», OF28, «Цуцлах» / «Устгах» (error colour). */
@Composable
fun DeleteDialog(bytes: Long, lang: Lang, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalTokens.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.offline_delete_question)) },
        text = { Text(fill(R.string.offline_space_used, "size" to packSize(bytes, lang)), color = t.uiOnSurfaceVariant.c()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.offline_delete_confirm), color = t.uiError.c()) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.offline_cancel)) } },
        modifier = Modifier.testTag("offline-delete-dialog"),
    )
}

/** O3 (AC 8, 9): «Мобайл датагаар {size} татах уу?», «Wi-Fi хүлээх» / «Татах». Back and outside = wait for Wi-Fi. */
@Composable
fun MobileDataDialog(bytes: Long, lang: Lang, onConfirm: () -> Unit, onWait: () -> Unit) {
    AlertDialog(
        onDismissRequest = onWait,
        title = { Text(fill(R.string.offline_mobile_data_question, "size" to packSize(bytes, lang))) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.offline_download)) } },
        dismissButton = { TextButton(onClick = onWait) { Text(stringResource(R.string.offline_wait_wifi)) } },
        modifier = Modifier.testTag("offline-mobile-data-dialog"),
    )
}

/**
 * O1 / O4 (screen spec F3, F4): an in-layout sheet over the map region only, so R5 stays visible below it. Scrim tap,
 * Back and a downward swipe on the handle are «Дараа». The button row stays pinned; the content scrolls.
 */
@Composable
fun OfflineOfferSheet(offer: PackOffer, lang: Lang, onShown: () -> Unit, onAccept: () -> Unit, onDecline: () -> Unit) {
    val t = LocalTokens.current
    val night = LocalNight.current
    LaunchedEffect(offer) { onShown() }
    BackHandler(onBack = onDecline)
    val heading = when (offer) {
        is PackOffer.First -> stringResource(R.string.offline_download_action)
        is PackOffer.Stale -> stringResource(R.string.offline_stale_title)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        Box(
            Modifier.fillMaxSize().background(t.uiScrim.c().copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDecline),
        )
        Surface(
            color = t.uiSurface.c(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = if (night) 0.dp else 3.dp,
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .heightIn(max = maxHeight - if (portrait) 56.dp else 16.dp)
                .then(if (night) Modifier.border(1.dp, t.uiOutlineVariant.c(), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) else Modifier)
                .semantics { paneTitle = heading; isTraversalGroup = true }
                .testTag(if (offer is PackOffer.First) "offline-offer" else "offline-stale-offer"),
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
                        detectVerticalDragGestures { _, drag -> if (drag > DRAG_DISMISS_PX) onDecline() }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(32.dp, 4.dp).background(t.uiOutline.c(), RoundedCornerShape(2.dp))) }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    Icon(
                        painterResource(if (offer is PackOffer.First) R.drawable.ic_map else R.drawable.ic_download),
                        contentDescription = null,
                        tint = t.uiPrimary.c(),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(heading, style = NavType.titleLarge, color = t.uiOnSurface.c(), modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    when (offer) {
                        is PackOffer.First -> {
                            Text(stringResource(R.string.offline_benefit), style = NavType.bodyLarge, color = t.uiOnSurface.c())
                            Spacer(Modifier.height(8.dp))
                            OfferFact(R.drawable.ic_download, fill(R.string.offline_download_size, "size" to packSize(offer.downloadBytes, lang)))
                            OfferFact(R.drawable.ic_smartphone, fill(R.string.offline_space_needed, "size" to packSize(offer.requiredSpace, lang)))
                        }
                        is PackOffer.Stale -> {
                            Text(fill(R.string.offline_stale_question, "size" to packSize(offer.downloadBytes, lang)), style = NavType.bodyLarge, color = t.uiOnSurface.c())
                            Spacer(Modifier.height(8.dp))
                            offer.routingTimestamp?.let(PackRules::dataDate)?.let { Text(fill(R.string.offline_date_routes, "date" to it), style = NavType.body, color = t.uiOnSurfaceVariant.c()) }
                            offer.searchTimestamp?.let(PackRules::dataDate)?.let { Text(fill(R.string.offline_date_search, "date" to it), style = NavType.body, color = t.uiOnSurfaceVariant.c()) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = 48.dp).testTag(if (offer is PackOffer.First) "offline-offer-later" else "offline-stale-later")) {
                        Text(stringResource(R.string.offline_not_now), style = NavType.label)
                    }
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c()),
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (offer is PackOffer.First) "offline-offer-download" else "offline-stale-update"),
                    ) {
                        Text(stringResource(if (offer is PackOffer.First) R.string.offline_download else R.string.offline_update), style = NavType.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferFact(@DrawableRes icon: Int, text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c())
    }
}

/** O6 S1 message (B2, B3): ready for 3 s; failed and storage with «Дахин оролдох» and «Хаах». */
@Composable
fun PackMessageCard(message: PackMessage, lang: Lang, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    if (message == PackMessage.Ready) {
        LaunchedEffect(message) {
            delay(READY_MESSAGE_MS)
            onClose()
        }
    }
    val retry = stringResource(R.string.action_retry) to onRetry
    val close = stringResource(R.string.action_close) to onClose
    Box(modifier.testTag("offline-message")) {
        when (message) {
            PackMessage.Ready -> MessageCard(R.drawable.ic_check_circle, stringResource(R.string.offline_ready), null, emptyList())
            PackMessage.Failed -> MessageCard(R.drawable.ic_warning, stringResource(R.string.offline_failed), null, listOf(retry, close))
            is PackMessage.NoSpace -> MessageCard(R.drawable.ic_warning, fill(R.string.offline_no_space, "size" to packSize(message.toFree, lang)), null, listOf(retry, close))
        }
    }
}

private const val ANNOUNCE_EVERY_MS = 10_000L
private const val READY_MESSAGE_MS = 3_000L
private const val DRAG_DISMISS_PX = 24f
