package mn.navmn.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.i18n.Strings
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.ui.theme.LocalNight
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

@Composable
fun rememberStrings(lang: Lang): Strings {
    val context = LocalContext.current
    return remember(lang) { ResourceStrings.of(context, lang) }
}

/** The guidance overlay scales text up to [cap] of the system font scale (navigation-ux §2.5). */
@Composable
fun CappedFontScale(cap: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, cap)), content = content)
}

/** NAV-004 icon table: the same key that picks the text picks the icon (screen spec › Manoeuvre icons). */
@DrawableRes
fun maneuverIcon(key: ManeuverKey): Int = when {
    key.isDepart -> R.drawable.ic_depart
    key.isArrive -> R.drawable.ic_flag
    key.id.startsWith("roundabout.") -> R.drawable.ic_roundabout_ccw
    else -> when (key) {
        ManeuverKey.TURN_LEFT -> R.drawable.ic_turn_left
        ManeuverKey.TURN_RIGHT -> R.drawable.ic_turn
        ManeuverKey.TURN_SLIGHT_LEFT -> R.drawable.ic_slight_left
        ManeuverKey.TURN_SLIGHT_RIGHT -> R.drawable.ic_slight
        ManeuverKey.TURN_SHARP_LEFT -> R.drawable.ic_sharp_left
        ManeuverKey.TURN_SHARP_RIGHT -> R.drawable.ic_sharp
        ManeuverKey.UTURN -> R.drawable.ic_uturn
        ManeuverKey.KEEP_LEFT -> R.drawable.ic_fork_left
        ManeuverKey.KEEP_RIGHT -> R.drawable.ic_fork
        ManeuverKey.MERGE, ManeuverKey.MERGE_LEFT -> R.drawable.ic_merge
        ManeuverKey.MERGE_RIGHT -> R.drawable.ic_merge_mirror
        ManeuverKey.ON_RAMP_LEFT, ManeuverKey.OFF_RAMP_LEFT -> R.drawable.ic_ramp_left
        ManeuverKey.ON_RAMP, ManeuverKey.ON_RAMP_RIGHT, ManeuverKey.OFF_RAMP, ManeuverKey.OFF_RAMP_RIGHT -> R.drawable.ic_ramp
        else -> R.drawable.ic_straight
    }
}

/**
 * R5 attribution strip (Layout rule 1): its own region at the bottom of every map screen; nothing is laid out over it.
 * The ESA WorldCover credit follows on S1 below zoom 8 (NAV-002 rule).
 */
@Composable
fun AttributionStrip(showEsa: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .background(t.uiSurfaceContainer.c())
            .heightIn(min = 24.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("attribution")
            .semantics(mergeDescendants = true) {},
    ) {
        Text(stringResource(R.string.attribution_osm), style = NavType.caption, color = t.uiOnSurfaceVariant.c())
        if (showEsa) Text(stringResource(R.string.attribution_esa), style = NavType.caption, color = t.uiOnSurfaceVariant.c())
    }
}

/** 48 dp icon button on a surface (map controls, RC control pair). */
@Composable
fun MapIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val t = LocalTokens.current
    val night = LocalNight.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (highlighted) t.uiPrimaryContainer.c() else t.uiSurface.c(),
        contentColor = if (highlighted) t.uiOnPrimaryContainer.c() else t.uiOnSurface.c(),
        shadowElevation = 1.dp,
        modifier = modifier
            .size(48.dp)
            .then(if (night) Modifier.border(1.dp, t.uiOutlineVariant.c(), CircleShape) else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp))
        }
    }
}

/** S4 / S1 message card (screen spec › Location message): icon, title, optional hint, text-button actions. */
@Composable
fun MessageCard(
    @DrawableRes icon: Int,
    title: String,
    hint: String?,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    onMessageSurface: Boolean = true,
) {
    val t = LocalTokens.current
    val bg = if (onMessageSurface) t.uiMessageSurface.c() else t.uiSurface.c()
    val fg = if (onMessageSurface) t.uiOnMessageSurface.c() else t.uiOnSurface.c()
    val action = if (onMessageSurface) t.uiMessageAction.c() else t.uiPrimary.c()
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(12.dp), shadowElevation = if (onMessageSurface) 2.dp else 0.dp, modifier = modifier.testTag("location-message")) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = NavType.body, color = fg)
                    if (hint != null) Text(hint, style = NavType.body, color = if (onMessageSurface) fg else t.uiOnSurfaceVariant.c())
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                for ((label, onClick) in actions) {
                    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label, style = NavType.label, color = action) }
                }
            }
        }
    }
}

fun Color.Companion.fromToken(v: Long) = v.c()
