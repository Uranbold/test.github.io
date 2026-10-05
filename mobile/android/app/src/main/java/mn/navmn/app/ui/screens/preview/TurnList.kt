package mn.navmn.app.ui.screens.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.Strings
import mn.navmn.app.preview.turnlist.TurnRow
import mn.navmn.app.preview.turnlist.TurnRowText
import mn.navmn.app.ui.components.maneuverIcon
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** Lazy-list key of the turn-list item in the expanded body; rows use `"step-<route>-<index>"` (AC 25). */
internal const val TURN_LIST_KEY = "turn-list"

/**
 * «Маршрутын заавар» (AC 18–25) as the last item of the expanded body's lazy list: the heading, then the rows in their
 * own lazy list, which is the collection node named «Маршрутын заавар» and the parent of every row (AC 23: TalkBack
 * announces "n of N" inside it). Only the visible rows plus a small buffer are composed (AC 25). The section is at most
 * [viewport] tall; [outer] (the body list) scrolls first until the section fills the body, then the rows scroll
 * ([OuterFirst]). [routeIndex] is the selected route's position in the response; [active] the activated row (AC 22).
 */
internal fun LazyListScope.turnListItem(
    rows: List<TurnRow>,
    routeIndex: Int,
    active: Int?,
    lang: Lang,
    strings: Strings,
    onRow: (Int) -> Unit,
    turnState: LazyListState,
    outer: LazyListState,
    viewport: Dp,
) {
    item(key = TURN_LIST_KEY) {
        val name = stringResource(R.string.route_directions)
        val connection = remember(outer) { OuterFirst(outer) }
        Column(Modifier.fillMaxWidth().heightIn(max = viewport).nestedScroll(connection)) {
            TurnListHeading(name)
            LazyColumn(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    // AC 23: the collection «Маршрутын заавар»; its children are the rows with their positions.
                    .semantics {
                        contentDescription = name
                        collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = 1)
                    }
                    .testTag("route-steps-list"),
                state = turnState,
            ) {
                items(rows, key = { "step-$routeIndex-${it.index}" }) { row -> TurnRowItem(row, row.index == active, lang, strings, onRow) }
            }
        }
    }
}

/**
 * Moving the content up (finger up), the body list scrolls first until the turn list fills it; then the rows scroll.
 * Moving down, the rows scroll back to their top first and the rest goes to the body list (default nested scrolling).
 */
private class OuterFirst(private val outer: LazyListState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f) return Offset.Zero
        val consumed = outer.dispatchRawDelta(-available.y)
        return Offset(0f, -consumed)
    }
}

@Composable
private fun TurnListHeading(name: String) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = t.uiOutlineVariant.c())
        Text(
            name,
            style = NavType.title,
            color = t.uiOnSurface.c(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                .semantics { heading() } // AC 23: heading semantics; the collection is the row list below it
                .testTag("route-steps"),
        )
    }
}

/**
 * One row (Components › Turn list): ≥ 56 dp, padding 8/16, icon 24 dp · text · distance, gap 16 dp; the distance stacks
 * under the text from font scale 1.5. The row reads as one node: instruction, street name, distance (AC 23).
 */
@Composable
private fun TurnRowItem(row: TurnRow, active: Boolean, lang: Lang, strings: Strings, onRow: (Int) -> Unit) {
    val t = LocalTokens.current
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val text = TurnRowText.instruction(row, lang, strings)
    val distance = TurnRowText.distance(row, lang, strings)
    val description = TurnRowText.contentDescription(row, lang, strings)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { onRow(row.index) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                collectionItemInfo = CollectionItemInfo(rowIndex = row.index, rowSpan = 1, columnIndex = 0, columnSpan = 1)
            }
            .testTag("route-step"),
    ) {
        // Activated row: 4 dp start-edge bar in route.selected (plus the manoeuvre point on the map).
        Box(Modifier.width(4.dp).fillMaxHeight().then(if (active) Modifier.background(t.routeSelected.c()) else Modifier))
        Row(
            Modifier.weight(1f).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(maneuverIcon(row.key.key)), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = NavType.bodyLarge, color = t.uiOnSurface.c())
                if (row.street.isNotEmpty()) {
                    Text(row.street, style = NavType.body, color = t.uiOnSurfaceVariant.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (stacked && distance != null) Text(distance, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c())
            }
            if (!stacked && distance != null) {
                Text(distance, style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurfaceVariant.c(), maxLines = 1, softWrap = false)
            }
        }
    }
}
