package mn.navmn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mn.navmn.app.R
import mn.navmn.app.i18n.Templates
import mn.navmn.app.licences.LicenceAssets
import mn.navmn.app.licences.LicenceEntry
import mn.navmn.app.licences.LicenceIndex
import mn.navmn.app.licences.LicencePart
import mn.navmn.app.pack.PackKind
import mn.navmn.app.pack.PackRules
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** Detail ids of the two map-data texts (AC 92); every other detail id is a [LicenceEntry.id]. */
private const val OPEN_ODBL = "data:odbl"
private const val OPEN_CCBY = "data:ccby"

/** UX P4: lines stay near 80 characters on wide windows. */
private val MAX_CONTENT_WIDTH = 640.dp

/**
 * NAV-005 section P (AC 88–98; screen spec android-licences.md): the full-screen licences page S9 and the detail page
 * S10, over whatever is below (guidance keeps running, AC 88). Offline by construction: everything comes from the APK
 * assets ([LicenceAssets]) and the app's resources; no link, no browser, 0 requests (AC 89). [packDates] = the OF20 dates
 * of the installed pack files (NAV-022 AC 39); empty = no pack, no pack block (AC 93). The open entry and both scroll
 * positions survive rotation, a theme or language switch and process death (UX P6, AC 95).
 */
@Composable
fun LicencesOverlay(
    packDates: List<Pair<PackKind, String>>,
    onClose: () -> Unit,
    assets: LicenceAssets = rememberLicenceAssets(),
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val index by produceState<IndexState>(IndexState.Loading, attempt) {
        value = withContext(Dispatchers.IO) { assets.index() }?.let { IndexState.Ready(it) } ?: IndexState.Failed
    }
    val listState = rememberLazyListState()
    BackHandler { if (open != null) open = null else onClose() }
    val t = LocalTokens.current
    Box(Modifier.fillMaxSize().background(t.uiSurface.c())) {
        val current = open
        val ready = index as? IndexState.Ready
        if (current == null || ready == null) {
            LicencesList(index, packDates, listState, onOpen = { open = it }, onRetry = { attempt++ }, onBack = onClose)
        } else {
            key(current) {
                LicenceDetail(ready.index, current, assets, onBack = { open = null })
            }
        }
    }
}

@Composable
fun rememberLicenceAssets(): LicenceAssets {
    val context = LocalContext.current.applicationContext
    return remember(context) { LicenceAssets { path -> context.assets.open(path) } }
}

private sealed interface IndexState {
    data object Loading : IndexState
    data object Failed : IndexState
    data class Ready(val index: LicenceIndex) : IndexState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicencesPage(tag: String, onBack: () -> Unit, content: LazyListScope.() -> Unit, state: LazyListState) {
    val t = LocalTokens.current
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = t.uiSurface.c(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences_title), style = NavType.title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    // AC 88: the back control's accessible name is the existing «Хаах» resource.
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("licences-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = t.uiSurface.c(),
                    scrolledContainerColor = t.uiSurfaceContainer.c(),
                    titleContentColor = t.uiOnSurface.c(),
                    navigationIconContentColor = t.uiOnSurface.c(),
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = MAX_CONTENT_WIDTH).testTag(tag),
            content = content,
        )
    }
}

// ------------------------------------------------------------------------------------------------ S9 list

@Composable
private fun LicencesList(
    index: IndexState,
    packDates: List<Pair<PackKind, String>>,
    state: LazyListState,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val t = LocalTokens.current
    val ready = (index as? IndexState.Ready)?.index
    LicencesPage("licences-list", onBack, state = state, content = {
        // Block 1 (AC 92, UX P2): always first, app text, so it stays even when the index cannot be read.
        item(key = "notice") {
            Column(Modifier.fillMaxWidth().testTag("licences-notice")) {
                SectionHeading(stringResource(R.string.licences_section_data))
                Text(stringResource(R.string.attribution_osm), style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.padding(horizontal = 16.dp))
                Text(stringResource(R.string.licences_osm_notice), style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
                LinkRow(stringResource(R.string.offline_licence), "licences-odbl", enabled = ready != null) { onOpen(OPEN_ODBL) }
                Text(stringResource(R.string.attribution_esa), style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.padding(horizontal = 16.dp))
                ready?.let { LinkRow(it.data.ccby.licence, "licences-ccby", enabled = true) { onOpen(OPEN_CCBY) } }
            }
        }
        // Block 2 (AC 93): only while a pack file is installed; resources and active.json only.
        if (packDates.isNotEmpty()) {
            item(key = "pack") {
                Column(Modifier.fillMaxWidth().testTag("licences-pack")) {
                    HorizontalDivider(color = t.uiOutlineVariant.c())
                    SectionHeading(stringResource(R.string.offline_title))
                    Text(stringResource(R.string.licences_pack_notice), style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(stringResource(R.string.offline_licence), "licences-pack-odbl", enabled = ready != null) { onOpen(OPEN_ODBL) }
                    Text(stringResource(R.string.offline_data_date), style = NavType.label, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 16.dp))
                    for ((kind, ts) in packDates) {
                        val date = PackRules.dataDate(ts) ?: continue
                        val res = when (kind) {
                            PackKind.TILES -> R.string.offline_date_map
                            PackKind.ROUTING -> R.string.offline_date_routes
                            PackKind.SEARCH -> R.string.offline_date_search
                        }
                        Text(
                            Templates.fill(stringResource(res), "date" to date),
                            style = NavType.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            color = t.uiOnSurface.c(),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        when (index) {
            IndexState.Loading -> item(key = "loading") { DelayedLoadingRow() }
            IndexState.Failed -> item(key = "error") { ErrorRow(onRetry) }
            is IndexState.Ready -> {
                item(key = "software-heading") {
                    HorizontalDivider(color = t.uiOutlineVariant.c())
                    SectionHeading(stringResource(R.string.licences_section_software))
                }
                items(index.index.software, key = { "e-" + it.id }) { EntryRow(it) { onOpen(it.id) } }
                if (index.index.fonts.isNotEmpty()) {
                    item(key = "fonts-heading") { SectionHeading(stringResource(R.string.licences_section_fonts)) }
                    items(index.index.fonts, key = { "e-" + it.id }) { EntryRow(it) { onOpen(it.id) } }
                }
            }
        }
    })
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = NavType.label,
        color = LocalTokens.current.uiPrimary.c(),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A 56 dp row that opens a bundled text (OF29 «Open Database License (ODbL) 1.0», «CC BY 4.0»). */
@Composable
private fun LinkRow(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = NavType.bodyLarge, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = t.uiOutline.c(), modifier = Modifier.size(24.dp))
    }
}

/** «{version} · {licence names}» or the licence names only (families with many versions, UX P3). */
private fun supporting(e: LicenceEntry): String = listOfNotNull(e.version, e.licences.joinToString(" · ")).joinToString(" · ")

/** S9 row (UX P3): one TalkBack node «{name}, {version}, {licence name}», role button, ≥ 56 dp (AC 95). */
@Composable
private fun EntryRow(e: LicenceEntry, onClick: () -> Unit) {
    val t = LocalTokens.current
    val spoken = listOfNotNull(e.name, e.version, e.licences.joinToString(", ")).joinToString(", ")
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("licence-entry-${e.id}")
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = NavType.bodyLarge, color = t.uiOnSurface.c())
                Text(supporting(e), style = NavType.body, color = t.uiOnSurfaceVariant.c())
            }
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = t.uiOutline.c(), modifier = Modifier.size(24.dp))
        }
        HorizontalDivider(color = t.uiOutlineVariant.c(), modifier = Modifier.padding(start = 16.dp))
    }
}

/** The NAV-005 loading row, only after 300 ms (UX States › Loading). */
@Composable
private fun DelayedLoadingRow() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        visible = true
    }
    if (visible) {
        Text(
            stringResource(R.string.status_loading),
            style = NavType.body,
            color = LocalTokens.current.uiOnSurfaceVariant.c(),
            modifier = Modifier.padding(16.dp).testTag("licences-loading"),
        )
    }
}

/** «Алдаа гарлаа» + «Дахин оролдох» (UX States › Error); no automatic retry. */
@Composable
private fun ErrorRow(onRetry: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("licences-error"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = t.uiOnSurfaceVariant.c(), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.status_generic_error), style = NavType.body, color = t.uiOnSurface.c(), modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
    }
}

// ------------------------------------------------------------------------------------------------ S10 detail

/** What a detail page shows: an entry, or one of the map-data texts presented the same way. */
private data class Detail(val name: String, val version: String?, val licence: String, val copyright: List<String>, val parts: List<LicencePart>, val artifacts: List<String>)

@Composable
private fun detailFor(index: LicenceIndex, id: String): Detail? {
    val osm = stringResource(R.string.attribution_osm)
    val esa = stringResource(R.string.attribution_esa)
    return when (id) {
        OPEN_ODBL -> index.data.odbl.let { Detail(it.name, null, it.licence, listOf(osm), listOf(LicencePart(it.licence, it.licence, emptyList(), it.text)), emptyList()) }
        OPEN_CCBY -> index.data.ccby.let { Detail(it.name, null, it.licence, listOf(esa), listOf(LicencePart(it.licence, it.licence, emptyList(), it.text)), emptyList()) }
        else -> index.entry(id)?.let { e ->
            Detail(e.name, e.version, e.licences.joinToString(" · "), e.copyright, e.parts, e.artifacts.map { "${it.coordinate} · ${it.version} · ${it.licence}" })
        }
    }
}

private sealed interface TextState {
    data object Loading : TextState
    data object Failed : TextState
    data class Ready(val texts: Map<String, List<String>>) : TextState
}

@Composable
private fun LicenceDetail(index: LicenceIndex, id: String, assets: LicenceAssets, onBack: () -> Unit) {
    val t = LocalTokens.current
    val detail = detailFor(index, id)
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val paths = remember(detail) { detail?.parts?.flatMap { listOfNotNull(it.text, it.notice) }.orEmpty().distinct() }
    val texts by produceState<TextState>(TextState.Loading, paths, attempt) {
        value = withContext(Dispatchers.IO) {
            val loaded = paths.associateWith { p -> assets.text(p)?.let(LicenceAssets::paragraphs) }
            if (loaded.values.any { it == null }) TextState.Failed else TextState.Ready(loaded.mapValues { it.value!! })
        }
    }
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // AC 95: TalkBack focus moves to the component name when the page opens.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LicencesPage("licences-detail", onBack, state = state, content = {
        if (detail == null) {
            item(key = "error") { ErrorRow(onRetry = onBack) }
            return@LicencesPage
        }
        item(key = "name") {
            Text(
                detail.name,
                style = NavType.headlineSmall,
                color = t.uiOnSurface.c(),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).focusRequester(focus).focusable().semantics { heading() }.testTag("licence-name"),
            )
        }
        item(key = "facts") {
            val version = detail.version?.let { Templates.fill(stringResource(R.string.licences_version), "version" to it) }
            Text(listOfNotNull(version, detail.licence).joinToString(" · "), style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        item(key = "copyright") {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("licence-copyright"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (line in detail.copyright) Text(line, style = NavType.body, color = t.uiOnSurface.c())
                }
            }
        }
        item(key = "text-heading") {
            HorizontalDivider(color = t.uiOutlineVariant.c(), modifier = Modifier.padding(top = 8.dp))
            SectionHeading(stringResource(R.string.licences_text_heading))
        }
        when (val s = texts) {
            TextState.Loading -> item(key = "loading") { DelayedLoadingRow() }
            TextState.Failed -> item(key = "text-error") { ErrorRow { attempt++ } }
            is TextState.Ready -> detail.parts.forEachIndexed { i, part ->
                if (detail.parts.size > 1 || id.startsWith("data:").not()) {
                    item(key = "part-$i") {
                        Text(
                            "${part.title} · ${part.licence}",
                            style = NavType.label,
                            color = t.uiPrimary.c(),
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                    if (part.copyright.isNotEmpty() && detail.parts.size > 1) {
                        item(key = "part-$i-copyright") {
                            SelectionContainer {
                                Column(Modifier.padding(horizontal = 16.dp)) { for (line in part.copyright) Text(line, style = NavType.body, color = t.uiOnSurfaceVariant.c()) }
                            }
                        }
                    }
                }
                paragraphItems("part-$i-text", s.texts[part.text].orEmpty())
                part.notice?.let { paragraphItems("part-$i-notice", s.texts[it].orEmpty()) }
            }
        }
        if (detail.artifacts.isNotEmpty()) {
            item(key = "artifacts-gap") { Spacer(Modifier.height(16.dp)) }
            items(detail.artifacts, key = { "a-$it" }) { line ->
                Text(line, style = NavType.body, color = t.uiOnSurfaceVariant.c(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
    })
}

/** UX P4: one selectable item per paragraph, 8 dp apart; never one huge Text. */
private fun LazyListScope.paragraphItems(prefix: String, paragraphs: List<String>) {
    items(paragraphs.size, key = { "$prefix-$it" }) { i ->
        SelectionContainer {
            Text(
                paragraphs[i],
                style = NavType.body,
                color = LocalTokens.current.uiOnSurface.c(),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).testTag("licence-paragraph"),
            )
        }
    }
}
