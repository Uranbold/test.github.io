package mn.navmn.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.format.Formatters
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.instructions.BannerText
import mn.navmn.app.reroute.RerouteSecondary
import mn.navmn.app.ui.Orientation
import mn.navmn.app.ui.components.CappedFontScale
import mn.navmn.app.ui.components.MapIconButton
import mn.navmn.app.ui.components.maneuverIcon
import mn.navmn.app.ui.theme.LocalNight
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c
import java.time.ZoneId

/** QA hook (screen spec NavBanner): `variant` = maneuver | reroute | arrival. */
val BannerVariant = SemanticsPropertyKey<String>("variant")
var SemanticsPropertyReceiver.bannerVariant by BannerVariant

/** QA hook (status message): `kind` = gps-lost | gps-restored | offline. */
val StatusKind = SemanticsPropertyKey<String>("kind")
var SemanticsPropertyReceiver.statusKind by StatusKind

/** Guidance overlay font caps (navigation-ux §2.5). */
const val OVERLAY_CAP = 1.3f
const val DISTANCE_CAP = 1.2f
const val STACKED_FROM = 1.15f

/** Heights covered by the overlay, in px, for the camera padding (Layout rule 2). */
data class Covered(val top: Int = 0, val bottom: Int = 0, val left: Int = 0)

fun rerouteSecondaryText(s: RerouteSecondary, strings: Strings): String = strings[
    when (s) {
        RerouteSecondary.UNAVAILABLE -> StringKey.ROUTE_UNAVAILABLE
        RerouteSecondary.NO_ROUTE -> StringKey.ROUTE_NO_ROUTE
        RerouteSecondary.ERROR -> StringKey.STATUS_GENERIC_ERROR
        RerouteSecondary.OFFLINE -> StringKey.STATUS_OFFLINE
    },
]

/** NavBanner (RB + RT): three variants, never blank (AC 21, 31, 42, 55), stacked layout from an effective 1.15. */
@Composable
fun NavBanner(banner: Banner, lang: Lang, strings: Strings, maxHeight: Dp, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val night = LocalNight.current
    val fontScale = LocalDensity.current.fontScale
    val stacked = minOf(fontScale, OVERLAY_CAP) >= STACKED_FROM
    val variant = when (banner) {
        is Banner.Maneuver -> "maneuver"
        is Banner.Rerouting -> "reroute"
        is Banner.Arrival -> "arrival"
    }
    val bg = if (banner is Banner.Rerouting) t.navBannerReroute.c() else t.navBanner.c()
    val onBg = t.navOnBanner.c()
    val variantColour = if (banner is Banner.Rerouting) t.navOnBannerRerouteVariant.c() else t.navOnBannerVariant.c()
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = bg,
        contentColor = onBg,
        shape = shape,
        shadowElevation = 2.dp,
        border = if (night) BorderStroke(1.dp, t.navBannerOutline.c()) else null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .testTag("nav-banner")
            .semantics { bannerVariant = variant },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val icon = when (banner) {
                is Banner.Maneuver -> maneuverIcon(banner.key.key)
                is Banner.Rerouting -> R.drawable.ic_route
                is Banner.Arrival -> R.drawable.ic_flag
            }
            val instruction = when (banner) {
                is Banner.Maneuver -> BannerText.text(banner.key, lang, strings)
                is Banner.Rerouting -> strings[StringKey.NAV_REROUTING]
                is Banner.Arrival -> BannerText.text(banner.key, lang, strings)
            }
            val second: String? = when (banner) {
                is Banner.Maneuver -> banner.street.ifEmpty { null }
                is Banner.Rerouting -> banner.secondary?.let { rerouteSecondaryText(it, strings) }
                is Banner.Arrival -> banner.street.ifEmpty { null }
            }
            val distance = (banner as? Banner.Maneuver)?.let { Formatters.distance(it.distanceM, lang, strings) }
            val distanceColour = if ((banner as? Banner.Maneuver)?.stale == true) variantColour else onBg
            val iconView: @Composable () -> Unit = {
                Icon(painterResource(icon), contentDescription = null, tint = onBg, modifier = Modifier.size(56.dp))
            }
            val distanceView: @Composable () -> Unit = {
                if (distance != null) {
                    CappedFontScale(DISTANCE_CAP) {
                        Text(distance, style = NavType.navDistance, color = distanceColour, modifier = Modifier.testTag("nav-banner-distance"))
                    }
                }
            }
            val textView: @Composable () -> Unit = {
                // One polite live region for instruction + street: announced once per change, never per fix (AC 62).
                Column(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(instruction, style = NavType.navInstruction, color = onBg, maxLines = 3, modifier = Modifier.testTag("nav-banner-text"))
                    if (second != null) {
                        Text(
                            second,
                            style = NavType.navStreet,
                            color = variantColour,
                            maxLines = if (banner is Banner.Rerouting) 3 else 1,
                            overflow = if (banner is Banner.Rerouting) TextOverflow.Clip else TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("nav-banner-street"),
                        )
                    }
                }
            }
            if (stacked) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        iconView()
                        if (distance != null) {
                            Spacer(Modifier.width(12.dp))
                            distanceView()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    textView()
                }
            } else {
                Row(Modifier.padding(16.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.Top) {
                    iconView()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        distanceView()
                        textView()
                    }
                }
            }
            val then = (banner as? Banner.Maneuver)?.then
            if (then != null) {
                val thenWord = BannerText.capitalizeFirst(strings[StringKey.NAV_THEN], lang)
                val thenText = BannerText.text(then, lang, strings)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(t.navBannerThen.c())
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("nav-then")
                        .clearAndSetSemantics { contentDescription = "$thenWord, $thenText" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(thenWord, style = NavType.label, color = onBg)
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(maneuverIcon(then.key)), contentDescription = null, tint = onBg, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** RS status message (GPS lost / restored, offline), polite live region, no buttons. */
@Composable
fun StatusMessage(kind: String, icon: Int, text: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val night = LocalNight.current
    Surface(
        color = t.uiMessageSurface.c(),
        contentColor = t.uiOnMessageSurface.c(),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp,
        border = if (night) BorderStroke(1.dp, t.uiMessageOutline.c()) else null,
        modifier = modifier
            .fillMaxWidth()
            .testTag("nav-status")
            .semantics(mergeDescendants = true) {
                statusKind = kind
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = NavType.body)
        }
    }
}

/** TripProgressPanel (RP): «Хүрэх цаг», remaining time · distance, «Тохиргоо», round «Дуусгах» (AC 22). */
@Composable
fun TripProgressPanel(state: GuidanceState, lang: Lang, strings: Strings, onSettings: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val night = LocalNight.current
    val p = state.progress
    val eta = Formatters.eta(p.etaBaseWallMs, p.durationRemaining, ZoneId.systemDefault())
    val etaText = Formatters.etaText(eta, strings)
    val time = Formatters.duration(p.durationRemaining, strings)
    val dist = Formatters.distance(p.distanceRemaining, lang, strings)
    val a11y = "$etaText, ${strings[StringKey.NAV_REMAINING_TIME]} $time, ${strings[StringKey.NAV_REMAINING_DISTANCE]} $dist"
    Surface(
        color = t.uiSurface.c(),
        contentColor = t.uiOnSurface.c(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        border = if (night) BorderStroke(1.dp, t.uiOutlineVariant.c()) else null,
        modifier = modifier.fillMaxWidth().testTag("nav-progress"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = a11y }) {
                Text(etaText, style = NavType.titleLarge, color = t.uiOnSurface.c(), modifier = Modifier.testTag("nav-eta"))
                Text("$time · $dist", style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.testTag("nav-remaining"))
            }
            val settings = stringResource(R.string.settings_title)
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).testTag("nav-settings")) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = settings)
            }
            Spacer(Modifier.width(8.dp))
            val end = stringResource(R.string.nav_end)
            OutlinedIconButton(
                onClick = onEnd,
                border = BorderStroke(1.dp, t.uiOutline.c()),
                modifier = Modifier.size(48.dp).testTag("nav-end"),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = end, tint = t.uiError.c())
            }
        }
    }
}

/** S6 arrival panel: flag, destination text, «Хаах». */
@Composable
fun ArrivalPanel(destination: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val night = LocalNight.current
    Surface(
        color = t.uiSurface.c(),
        contentColor = t.uiOnSurface.c(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        border = if (night) BorderStroke(1.dp, t.uiOutlineVariant.c()) else null,
        modifier = modifier.fillMaxWidth().testTag("nav-arrival"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.ic_flag), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(destination, style = NavType.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c())) {
                    Text(stringResource(R.string.action_close), style = NavType.label)
                }
            }
        }
    }
}

/**
 * S5 / S6 overlay over the map (screen spec › Regions RB, RT, RS, RC, Recenter, RN, RP; portrait and landscape).
 * Reports the covered heights so the camera keeps the puck in the uncovered map (Layout rule 2).
 */
@Composable
fun GuidanceOverlay(
    state: GuidanceState,
    lang: Lang,
    strings: Strings,
    orientation: Orientation,
    following: Boolean,
    onEnd: () -> Unit,
    onSettings: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleOrientation: () -> Unit,
    onRecenter: () -> Unit,
    onDismissNotice: () -> Unit,
    onArrivalClose: () -> Unit,
    onCovered: (Covered) -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrived = state.phase == GuidancePhase.ARRIVED || state.phase == GuidancePhase.ENDED
    val destinationText = state.trip.destinationName ?: stringResource(R.string.place_selected_point)
    val statuses = buildList {
        if (!arrived) {
            if (state.gpsLost) add(Triple("gps-lost", R.drawable.ic_gps_off, strings[StringKey.NAV_GPS_LOST]))
            else if (state.gpsRestoredVisible) add(Triple("gps-restored", R.drawable.ic_my_location, strings[StringKey.NAV_GPS_RESTORED]))
            val bannerSaysOffline = (state.banner as? Banner.Rerouting)?.secondary == RerouteSecondary.OFFLINE
            if (state.offline && !bannerSaysOffline) add(Triple("offline", R.drawable.ic_cloud_off, strings[StringKey.STATUS_OFFLINE]))
        }
    }.take(2)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val bannerMax = maxHeight * 0.5f
        // [top, bottom, left] in px, kept across recompositions; reported only when it changes.
        val px = remember { IntArray(3) }
        val last = remember { arrayOfNulls<Covered>(1) }
        val margin = with(density) { 16.dp.roundToPx() }
        fun report() {
            val c = Covered(px[0] + margin, px[1] + margin, px[2])
            if (c != last[0]) {
                last[0] = c
                onCovered(c)
            }
        }

        val topBlock: @Composable (Modifier) -> Unit = { m ->
            CappedFontScale(OVERLAY_CAP) {
                Column(m) {
                    NavBanner(state.banner, lang, strings, bannerMax, Modifier.padding(horizontal = 8.dp))
                    for ((kind, icon, text) in statuses) {
                        Spacer(Modifier.height(8.dp))
                        StatusMessage(kind, icon, text, Modifier.padding(horizontal = if (landscape) 8.dp else 16.dp))
                    }
                }
            }
        }
        val bottomBlock: @Composable (Modifier) -> Unit = { m ->
            CappedFontScale(OVERLAY_CAP) {
                Column(m) {
                    if (!arrived) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                            if (!following) {
                                ExtendedFloatingActionButton(
                                    onClick = onRecenter,
                                    icon = { Icon(painterResource(R.drawable.ic_my_location), contentDescription = null) },
                                    text = { Text(stringResource(R.string.control_recenter), style = NavType.label) },
                                    containerColor = LocalTokens.current.uiPrimaryContainer.c(),
                                    contentColor = LocalTokens.current.uiOnPrimaryContainer.c(),
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp).testTag("nav-recenter"),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            MapIconButton(
                                icon = if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                                description = stringResource(if (state.muted) R.string.nav_unmute else R.string.nav_mute),
                                onClick = onToggleMute,
                                modifier = Modifier.testTag("nav-voice"),
                            )
                            Spacer(Modifier.width(8.dp))
                            MapIconButton(
                                icon = if (orientation == Orientation.HEADING_UP) R.drawable.ic_heading_up else R.drawable.ic_compass_north,
                                // names the mode it switches TO (AC 24)
                                description = stringResource(if (orientation == Orientation.HEADING_UP) R.string.control_north_up else R.string.control_heading_up),
                                onClick = onToggleOrientation,
                                modifier = Modifier.testTag("nav-orientation"),
                            )
                        }
                        Spacer(Modifier.height(if (!following) 12.dp else 16.dp))
                        if (state.voiceNoticeVisible) {
                            Box(Modifier.padding(horizontal = 8.dp).clickable(onClick = onDismissNotice).testTag("nav-voice-notice")) {
                                StatusMessage("voice-notice", R.drawable.ic_info, strings[StringKey.NAV_VOICE_UNAVAILABLE])
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        TripProgressPanel(state, lang, strings, onSettings, onEnd, Modifier.padding(horizontal = 8.dp))
                    } else {
                        ArrivalPanel(destinationText, onArrivalClose, Modifier.padding(horizontal = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (!landscape) {
            topBlock(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(top = 8.dp)
                    .onGloballyPositioned { px[0] = it.size.height + it.positionInRoot().y.toInt(); report() },
            )
            bottomBlock(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onGloballyPositioned { px[1] = it.size.height; report() },
            )
        } else {
            val columnWidth = (maxWidth * 0.4f).coerceIn(320.dp, 400.dp)
            topBlock(
                Modifier.align(Alignment.TopStart).width(columnWidth).statusBarsPadding().padding(top = 8.dp)
                    .verticalScroll(rememberScrollState())
                    .onGloballyPositioned { px[2] = it.size.width; px[0] = 0; report() },
            )
            bottomBlock(
                Modifier.align(Alignment.BottomEnd).width(maxWidth - columnWidth)
                    .onGloballyPositioned { px[1] = it.size.height; report() },
            )
        }
    }
}

