package mn.navmn.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mn.navmn.app.R
import mn.navmn.app.format.Formatters
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.i18n.Templates
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchView
import mn.navmn.app.ui.components.MapIconButton
import mn.navmn.app.ui.components.MessageCard
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** Actions of the S1–S4 overlay (unidirectional: the overlay only calls these). */
class BrowseActions(
    val onQuery: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onResult: (PlaceDisplay.Info, String) -> Unit,
    val onRetrySearch: () -> Unit,
    val onSettings: () -> Unit,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onNorthUp: () -> Unit,
    val onMyLocation: () -> Unit,
    val onCardClose: () -> Unit,
    val onCardDirections: () -> Unit,
    val onPreviewClose: () -> Unit,
    val onMode: (TravelMode) -> Unit,
    val onAvoid: (Boolean) -> Unit,
    val onPreviewRetry: () -> Unit,
    val onStart: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    val onDismissMapProblem: () -> Unit,
    val onRetryTiles: () -> Unit,
    val onSheetHeight: (Int) -> Unit,
)

data class BrowseModel(
    val lang: Lang,
    val query: String,
    val searchView: SearchView,
    val card: LatLon?,
    val preview: PreviewState?,
    val mapProblem: LocationProblem?,
    val tilesFailed: Boolean,
    val offline: Boolean,
    val bearing: Double,
    val followingMe: Boolean,
)

@Composable
fun LocationMessage(p: LocationProblem, actions: BrowseActions, onRetry: () -> Unit, onClose: () -> Unit, onMessageSurface: Boolean, modifier: Modifier = Modifier) {
    val openSettings = stringResource(R.string.action_open_settings) to actions.onOpenAppSettings
    val close = stringResource(R.string.action_close) to onClose
    when (p) {
        LocationProblem.NEEDS_PERMISSION, LocationProblem.DENIED -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_denied_title), stringResource(R.string.location_denied_hint),
            listOf(openSettings, close), modifier, onMessageSurface,
        )
        LocationProblem.APPROXIMATE -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_precise), null, listOf(openSettings, close), modifier, onMessageSurface,
        )
        LocationProblem.SERVICES_OFF -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_services_off), null,
            listOf(stringResource(R.string.action_open_settings) to actions.onOpenLocationSettings, close), modifier, onMessageSurface,
        )
        LocationProblem.UNAVAILABLE -> MessageCard(
            R.drawable.ic_gps_off, stringResource(R.string.location_unavailable), null,
            listOf(stringResource(R.string.action_retry) to onRetry, close), modifier, onMessageSurface,
        )
    }
}

/** S1 search bar (docked) with the settings button; S2 results list under it. */
@Composable
private fun SearchArea(m: BrowseModel, strings: Strings, a: BrowseActions) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(28.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("search-bar")) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = t.uiOnSurfaceVariant.c())
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        val placeholder = stringResource(R.string.search_placeholder)
                        if (m.query.isEmpty()) Text(placeholder, style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c())
                        BasicTextField(
                            value = m.query,
                            onValueChange = { a.onQuery(it.take(200)) },
                            singleLine = true,
                            textStyle = NavType.bodyLarge.copy(color = t.uiOnSurface.c()),
                            cursorBrush = SolidColor(t.uiPrimary.c()),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                        )
                    }
                    if (m.query.isNotEmpty()) {
                        IconButton(onClick = a.onClearSearch, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            MapIconButton(R.drawable.ic_settings, stringResource(R.string.settings_title), a.onSettings, Modifier.testTag("settings"))
        }
        val v = m.searchView
        if (v !is SearchView.Closed) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = t.uiSurface.c(), shadowElevation = 2.dp) {
                val listName = stringResource(R.string.search_results)
                Column(Modifier.heightIn(max = 360.dp).semantics { contentDescription = listName }.testTag("search-results")) {
                    when (v) {
                        SearchView.Loading -> StateRow(stringResource(R.string.status_loading), progress = true)
                        SearchView.NoResults -> StateRow(stringResource(R.string.search_no_results))
                        SearchView.Unavailable -> StateRow(stringResource(R.string.search_unavailable), retry = a.onRetrySearch)
                        SearchView.Error -> StateRow(stringResource(R.string.status_generic_error))
                        SearchView.Offline -> StateRow(stringResource(R.string.status_offline))
                        is SearchView.RateLimited -> StateRow(stringResource(R.string.search_rate_limited), retry = a.onRetrySearch, retryEnabled = v.retryEnabled)
                        is SearchView.Results -> LazyColumn {
                            items(v.items) { info ->
                                val name = info.name ?: strings[info.type]
                                Column(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { a.onResult(info, name) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(name, style = NavType.bodyLarge, color = t.uiOnSurface.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val second = listOfNotNull(strings[info.type].takeIf { info.name != null }, info.context).joinToString(" · ")
                                    if (second.isNotEmpty()) Text(second, style = NavType.body, color = t.uiOnSurfaceVariant.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = t.uiOutlineVariant.c())
                            }
                        }
                        SearchView.Closed -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun StateRow(text: String, progress: Boolean = false, retry: (() -> Unit)? = null, retryEnabled: Boolean = true) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (progress) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
        if (retry != null) TextButton(onClick = retry, enabled = retryEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
    }
}

/** S2 coordinate card «Сонгосон цэг» (AC 4): 5 decimals, «Маршрут гаргах», 0 reverse requests. */
@Composable
private fun CoordinateCard(p: LatLon, a: BrowseActions, modifier: Modifier) {
    val t = LocalTokens.current
    Surface(shape = RoundedCornerShape(16.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = modifier.fillMaxWidth().testTag("coordinate-card")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.place_selected_point), style = NavType.title, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
                IconButton(onClick = a.onCardClose, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                }
            }
            Text(Formatters.coordinates(p.lat, p.lon), style = NavType.body.copy(fontFeatureSettings = "tnum"), color = t.uiOnSurface.c())
            Spacer(Modifier.height(12.dp))
            Button(onClick = a.onCardDirections, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c())) {
                Icon(painterResource(R.drawable.ic_directions), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.route_get_directions), style = NavType.label)
            }
        }
    }
}

/** S3 route preview sheet (non-modal, content height up to 80 %, «Эхлэх» pinned). */
@Composable
private fun PreviewSheet(s: PreviewState, m: BrowseModel, strings: Strings, a: BrowseActions, maxHeight: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val t = LocalTokens.current
    val lang = m.lang
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = t.uiSurface.c(),
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("route-preview"),
    ) {
        Column {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.route_title), style = NavType.title, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f).padding(top = 8.dp))
                    IconButton(onClick = a.onPreviewClose, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                    }
                }
                val originLabel = stringResource(R.string.marker_my_location)
                val originA11y = stringResource(R.string.route_origin) + ": " + originLabel
                PointRow(R.drawable.ic_my_location, originLabel, originA11y)
                val destText = s.destination.name ?: stringResource(R.string.place_selected_point)
                PointRow(R.drawable.ic_place, destText, stringResource(R.string.route_destination) + ": " + destText)
                val modes = stringResource(R.string.route_modes)
                TabRow(selectedTabIndex = if (s.mode == TravelMode.CAR) 0 else 1, containerColor = t.uiSurface.c(), contentColor = t.uiPrimary.c(), modifier = Modifier.semantics { contentDescription = modes }) {
                    Tab(selected = s.mode == TravelMode.CAR, onClick = { a.onMode(TravelMode.CAR) }, text = { Text(stringResource(R.string.route_mode_car)) }, icon = { Icon(painterResource(R.drawable.ic_car), null) })
                    Tab(selected = s.mode == TravelMode.WALK, onClick = { a.onMode(TravelMode.WALK) }, text = { Text(stringResource(R.string.route_mode_walk)) }, icon = { Icon(painterResource(R.drawable.ic_walk), null) })
                }
                if (s.mode == TravelMode.CAR) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.route_avoid_unpaved), style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
                        Switch(checked = s.avoidUnpaved, onCheckedChange = a.onAvoid)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.testTag("preview-result")) { PreviewResultRegion(s, lang, strings, a) }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = a.onStart,
                enabled = s.canStart,
                colors = ButtonDefaults.buttonColors(containerColor = t.uiPrimary.c(), contentColor = t.uiOnPrimary.c()),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp).testTag("nav-start"),
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nav_start), style = NavType.label)
            }
        }
    }
}

@Composable
private fun PointRow(icon: Int, text: String, a11y: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = NavType.bodyLarge, color = t.uiOnSurface.c(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PreviewResultRegion(s: PreviewState, lang: Lang, strings: Strings, a: BrowseActions) {
    val t = LocalTokens.current
    when (val r = s.result) {
        PreviewResult.WaitingForLocation, PreviewResult.Loading -> StateRow(stringResource(R.string.status_loading), progress = true)
        PreviewResult.Pending -> Spacer(Modifier.height(56.dp))
        is PreviewResult.Location -> LocationMessage(r.problem, a, a.onPreviewRetry, a.onPreviewClose, onMessageSurface = false)
        is PreviewResult.Route -> {
            val plan = r.route.plan
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Formatters.duration(plan.duration, strings), style = NavType.titleLarge, color = t.uiOnSurface.c())
                    Text(" · " + Formatters.distance(plan.distance, lang, strings), style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c())
                }
                val eta = Formatters.eta(r.receivedWallMs, plan.duration, java.time.ZoneId.systemDefault())
                Text(Formatters.etaText(eta, strings), style = NavType.bodyLarge, color = t.uiOnSurface.c())
                val snap = plan.snapDistances.lastOrNull() ?: 0.0
                if (snap > 500.0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp), tint = t.uiOnSurfaceVariant.c())
                        Spacer(Modifier.width(8.dp))
                        Text(Templates.fill(strings[StringKey.ROUTE_SNAP_NOTICE], "distance" to Formatters.distance(snap, lang, strings)), style = NavType.body, color = t.uiOnSurfaceVariant.c())
                    }
                }
            }
        }
        PreviewResult.SamePoint -> StateRow(stringResource(R.string.route_same_point))
        is PreviewResult.NoRoute -> Column {
            StateRow(stringResource(R.string.route_no_route))
            if (r.avoidHint) Text(stringResource(R.string.route_no_route_avoid_hint), style = NavType.body, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 16.dp))
        }
        PreviewResult.OutOfArea -> StateRow(stringResource(R.string.route_out_of_area))
        PreviewResult.TooFar -> StateRow(stringResource(R.string.route_too_far))
        PreviewResult.Unavailable -> StateRow(stringResource(R.string.route_unavailable), retry = a.onPreviewRetry)
        PreviewResult.Offline -> StateRow(stringResource(R.string.status_offline))
        is PreviewResult.RateLimited -> StateRow(stringResource(R.string.route_rate_limited), retry = a.onPreviewRetry, retryEnabled = r.retryEnabled)
        PreviewResult.Error -> StateRow(stringResource(R.string.status_generic_error))
    }
}

/** S1–S4 overlay over the map. */
@Composable
fun BrowseOverlay(m: BrowseModel, strings: Strings, a: BrowseActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxH = maxHeight
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            SearchArea(m, strings, a)
            val problem = m.mapProblem
            if (problem != null && m.preview == null) {
                LocationMessage(problem, a, a.onMyLocation, a.onDismissMapProblem, onMessageSurface = true, modifier = Modifier.padding(horizontal = 16.dp))
            } else if (m.tilesFailed) {
                MessageCard(R.drawable.ic_warning, stringResource(R.string.status_tiles_unavailable), null, listOf(stringResource(R.string.action_retry) to a.onRetryTiles), Modifier.padding(horizontal = 16.dp))
            } else if (m.offline) {
                MessageCard(R.drawable.ic_cloud_off, stringResource(R.string.status_offline), null, emptyList(), Modifier.padding(horizontal = 16.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (m.preview == null) {
                Column(Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapIconButton(R.drawable.ic_add, stringResource(R.string.control_zoom_in), a.onZoomIn)
                    MapIconButton(R.drawable.ic_remove, stringResource(R.string.control_zoom_out), a.onZoomOut)
                    if (Math.abs(m.bearing) > 0.5) MapIconButton(R.drawable.ic_compass_north, stringResource(R.string.control_north_up), a.onNorthUp)
                    MapIconButton(R.drawable.ic_my_location, stringResource(R.string.marker_my_location), a.onMyLocation, highlighted = m.followingMe)
                }
                m.card?.let { CoordinateCard(it, a, Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) }
            } else {
                PreviewSheet(m.preview, m, strings, a, maxH * 0.8f, Modifier.onGloballyPositioned { a.onSheetHeight(it.size.height) })
            }
        }
    }
}
