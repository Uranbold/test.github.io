package mn.navmn.app.ui.screens.preview

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.navmn.app.R
import mn.navmn.app.i18n.Strings
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.preview.points.MyLocationOption
import mn.navmn.app.preview.points.PointEditorState
import mn.navmn.app.preview.points.PointRules
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.search.SearchView
import mn.navmn.app.typinglock.TypingLockState
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.LocationMessage
import mn.navmn.app.ui.screens.SearchResults
import mn.navmn.app.ui.screens.TypingLockCard
import mn.navmn.app.ui.theme.LocalNight
import mn.navmn.app.ui.theme.LocalTokens
import mn.navmn.app.ui.theme.NavType
import mn.navmn.app.ui.theme.c

/** QA hook: `origin` | `destination` on the point editor (screen spec › Components, test tag `point-editor`). */
val EditingKey = SemanticsPropertyKey<String>("editing")
var SemanticsPropertyReceiver.editing by EditingKey

/** «Ачаалж байна…» appears 300 ms after the wait starts (NAV-005). */
private const val LOADING_LINE_MS = 300L

/**
 * Q5 point editor, in the S1/S2 search position while the sheet is hidden: the editor field (56 dp, pinned), then the
 * «Миний байршил» option card (start editor, when the start is empty or chosen), the NAV-011 lock card, and the NAV-011
 * results card from the field's own SearchController (AC 3, 4, 34). It opens with the current field text fully selected
 * (0 requests on open); leaving without a choice changes nothing (AC 7).
 */
@Composable
internal fun PointEditor(
    s: PreviewState,
    editor: PointEditorState,
    fieldView: SearchView,
    lock: TypingLockState,
    focusSearch: Int,
    strings: Strings,
    a: BrowseActions,
    modifier: Modifier = Modifier,
) {
    val pa = a.points
    val origin = editor.side == PointSide.ORIGIN
    val myLocation = stringResource(R.string.marker_my_location)
    val current = if (origin) fieldText(s.origin, !s.originEmpty, strings, myLocation) else PointRules.label(s.destination, strings)
    // AC 3: the option is offered when the start is empty or a chosen start (not while it already is «Миний байршил»).
    val showOption = origin && (s.originEmpty || PointRules.isChosenStart(s.origin) || editor.myLocation != MyLocationOption.Idle)
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        EditorField(editor, current, lock, focusSearch, pa)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (showOption) MyLocationCard(editor.myLocation, a, Modifier.padding(top = 8.dp))
            TypingLockCard(lock, a, Modifier.padding(top = 8.dp))
        }
        if (fieldView !is SearchView.Closed) {
            Spacer(Modifier.size(8.dp))
            SearchResults(
                m = null, strings = strings, a = a, view = fieldView,
                onResult = pa.onEditorResult, onRetry = pa.onEditorRetry, tag = "point-results",
                modifier = Modifier.weight(1f, fill = false),
                sidePadding = 0.dp,
            )
        }
    }
}

/**
 * The editor field (S1 search-bar shape): label line «Эхлэх цэг» / «Очих газар» and the input; IME action Search;
 * «Хайлтыг арилгах» while there is text. Typing lock as the S1 field (NAV-011 AC 31, 34).
 */
@Composable
private fun EditorField(editor: PointEditorState, current: String, lock: TypingLockState, focusSearch: Int, pa: PointActions) {
    val t = LocalTokens.current
    val night = LocalNight.current
    val origin = editor.side == PointSide.ORIGIN
    val label = stringResource(if (origin) R.string.route_origin else R.string.route_destination)
    val placeholder = stringResource(if (origin) R.string.route_origin_placeholder else R.string.route_destination_placeholder)
    var value by rememberSaveable(editor.session, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current, TextRange(0, current.length)))
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val locked = lock.locked
    // Opens with focus and the keyboard, unless the lock is engaged (then the lock card shows, AC 34).
    var opened by rememberSaveable(editor.session) { mutableStateOf(false) }
    LaunchedEffect(editor.session) {
        if (!opened) {
            opened = true
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(locked) {
        if (locked && focused) {
            keyboard?.hide()
            focusManager.clearFocus()
            pa.onEditorFieldTap()
        }
    }
    var handledFocus by rememberSaveable(editor.session) { mutableIntStateOf(focusSearch) }
    LaunchedEffect(focusSearch) {
        if (focusSearch > handledFocus) {
            handledFocus = focusSearch
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = t.uiSurface.c(),
        shadowElevation = if (night) 0.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (night) Modifier.border(1.dp, t.uiOutlineVariant.c(), shape) else Modifier)
            .semantics { editing = if (origin) "origin" else "destination" }
            .testTag("point-editor"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (origin) R.drawable.ic_ring else R.drawable.ic_place),
                contentDescription = null,
                tint = if (origin) t.uiOnSurfaceVariant.c() else t.pinFill.c(),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(label, style = NavType.caption, color = t.uiOnSurfaceVariant.c())
                Box {
                    if (value.text.isEmpty()) Text(placeholder, style = NavType.bodyLarge, color = t.uiOnSurfaceVariant.c())
                    BasicTextField(
                        value = value,
                        onValueChange = { v ->
                            if (locked) return@BasicTextField
                            val capped = if (v.text.length > 200) v.copy(text = v.text.take(200)) else v
                            val changed = capped.text != value.text
                            value = capped
                            if (changed) pa.onEditorQuery(capped.text)
                        },
                        singleLine = true,
                        readOnly = locked,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        textStyle = NavType.bodyLarge.copy(color = t.uiOnSurface.c()),
                        cursorBrush = SolidColor(t.uiPrimary.c()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { fs ->
                                val gained = fs.isFocused && !focused
                                focused = fs.isFocused
                                if (gained) {
                                    if (pa.onEditorFieldTap()) keyboard?.show() else {
                                        keyboard?.hide()
                                        focusManager.clearFocus()
                                    }
                                }
                            }
                            .semantics { contentDescription = label },
                    )
                }
            }
            if (value.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        value = TextFieldValue("")
                        pa.onEditorQuery("")
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                }
            }
        }
    }
}

/**
 * Option card «Миний байршил» (AC 3): one 56 dp row; while a fix is awaited a progress indicator and, after 300 ms,
 * «Ачаалж байна…»; on failure the NAV-005 message for the case inside the card (the editor stays, the start is unchanged).
 */
@Composable
private fun MyLocationCard(option: MyLocationOption, a: BrowseActions, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val pa = a.points
    var showLoading by remember(option) { mutableStateOf(false) }
    LaunchedEffect(option) {
        if (option == MyLocationOption.Waiting) {
            delay(LOADING_LINE_MS)
            showLoading = true
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = t.uiSurface.c(), shadowElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, enabled = option != MyLocationOption.Waiting, onClick = pa.onEditorMyLocation)
                    .padding(horizontal = 16.dp)
                    .testTag("point-option-my-location"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, tint = t.uiPrimary.c(), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.marker_my_location), style = NavType.bodyLarge.copy(fontWeight = NavType.label.fontWeight), color = t.uiPrimary.c())
                    if (option == MyLocationOption.Waiting && showLoading) {
                        Text(stringResource(R.string.status_loading), style = NavType.body, color = t.uiOnSurfaceVariant.c())
                    }
                }
                if (option == MyLocationOption.Waiting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (option is MyLocationOption.Failed) {
                LocationMessage(option.problem, a, pa.onEditorMyLocation, pa.onEditorClose, onMessageSurface = false, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}
