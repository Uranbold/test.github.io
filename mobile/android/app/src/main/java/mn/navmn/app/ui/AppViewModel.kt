package mn.navmn.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mn.navmn.app.engine.GuidanceSession
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.engine.Trip
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.BrowseDotRules
import mn.navmn.app.location.DisplayLocation
import mn.navmn.app.location.Fix
import mn.navmn.app.location.LocationDisplayFilter
import mn.navmn.app.location.LocationSource
import mn.navmn.app.net.NetworkMonitor
import mn.navmn.app.permission.LocationAccess
import mn.navmn.app.permission.LocationAction
import mn.navmn.app.permission.PermissionStatus
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.preview.PreviewController
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.points.MyLocationOption
import mn.navmn.app.preview.points.OriginAttempts
import mn.navmn.app.preview.points.PointEditorState
import mn.navmn.app.preview.points.PointRules
import mn.navmn.app.preview.points.PointSide
import mn.navmn.app.preview.points.PointsUi
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.preview.points.StepFocus
import mn.navmn.app.routing.FallbackRouteRequester
import mn.navmn.app.routing.OnDeviceRouting
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchClient
import mn.navmn.app.search.SearchController
import mn.navmn.app.search.SearchCooldown
import mn.navmn.app.search.reverse.ReverseClient
import mn.navmn.app.search.reverse.ReverseController
import mn.navmn.app.settings.SettingsRepository
import mn.navmn.app.settings.ThemeChoice
import mn.navmn.app.typinglock.LockFixSource
import mn.navmn.app.typinglock.TypingLockController
import mn.navmn.app.variant.ReplayVariant
import mn.navmn.app.voice.GuidanceVoice
import java.util.Optional
import javax.inject.Inject

enum class Orientation { HEADING_UP, NORTH_UP }

/** NAV-011 C1 (D142): a one-shot camera request for the typed-coordinate option; [seq] tells requests apart. */
data class CoordinateFocus(val point: LatLon, val seq: Int)

/** UI-only state (everything else comes from the controllers, the session and the settings). */
data class UiState(
    val query: String = "",
    val searchActive: Boolean = false,
    /** S2 coordinate card from a long-press or the typed-coordinate option (NAV-011 D140) («Сонгосон цэг»). */
    val card: LatLon? = null,
    /** S4 rationale dialog. */
    val rationale: Boolean = false,
    /** Ask the Activity to launch the OS location permission dialog. */
    val requestLocationPermission: Boolean = false,
    /** S1 location message (R2). */
    val mapProblem: LocationProblem? = null,
    val tilesFailed: Boolean = false,
    /** S1 following my location (NAV-002 behaviour). */
    val followingMe: Boolean = false,
    /**
     * NAV-005 AC 76 (D172): a my-location press is waiting (≤ 10 s) for the first showable fix; the button shows
     * `location_searching` (screen spec › Location dot on the browse map).
     */
    val locating: Boolean = false,
    val settingsOpen: Boolean = false,
    /** Guidance session-only UI state (navigation-ux §8): orientation and camera follow. */
    val orientation: Orientation = Orientation.HEADING_UP,
    val cameraFollowing: Boolean = true,
    val lastGestureAt: Long = 0,
    /** Ask the Activity to request POST_NOTIFICATIONS once at the first «Эхлэх» (AC 13). */
    val requestNotificationPermission: Boolean = false,
    /** NAV-011 D55: route preview sheet expanded (kept across rotation, theme, language and new responses, P4). */
    val sheetExpanded: Boolean = false,
    /** NAV-011 AC 34: bumped by «Би зорчигч» so the search field takes focus and opens the keyboard. */
    val focusSearch: Int = 0,
    /** NAV-011 AC 7a (D140, D142): pending one-shot C1 camera move; cleared once NavRoot has run it (not repeated on rotation). */
    val coordinateFocus: CoordinateFocus? = null,
    /** NAV-011 AC 7a (screen spec › Accessibility): focus moves to the card title once after the typed option. */
    val cardTitleFocus: Boolean = false,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    val settings: SettingsRepository,
    private val location: LocationSource,
    private val network: NetworkMonitor,
    /** NAV-021: the preview goes online first with the on-device fallback (a pass-through without a routing file). */
    private val routes: FallbackRouteRequester,
    private val onDeviceRouting: OnDeviceRouting,
    private val searchClient: SearchClient,
    private val session: GuidanceSession,
    private val voice: GuidanceVoice,
    private val reverseClient: ReverseClient,
    lockFixes: LockFixSource,
    replayVariant: Optional<ReplayVariant>,
) : ViewModel() {
    /** ADR-0016 §3: present only in a replay (demo) build; NavRoot reads it for the start screen, tiles and slots. */
    val replay: ReplayVariant? = replayVariant.orElse(null)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val online: StateFlow<Boolean> = network.validated
    val theme: StateFlow<ThemeChoice> = settings.theme
    val lang: StateFlow<Lang> = settings.lang
    val muted: StateFlow<Boolean> = settings.muted

    /**
     * Raw latest map fix (ADR-0009 Amendment 7 §9.4): the input of the route-preview origin / guidance start
     * ([freshFix], D177), the NAV-018 device start and the NAV-012 sun theme. Never drawn directly.
     */
    private val _myLocation = MutableStateFlow<Fix?>(null)
    val myLocation: StateFlow<Fix?> = _myLocation.asStateFlow()

    /**
     * NAV-005 section N (AC 74–78): the shown position after [LocationDisplayFilter]. It drives the browse dot, the
     * accuracy circle, the browse follow camera, the my-location centring and the D30 search bias (D174). Memory only.
     */
    private val displayFilter = LocationDisplayFilter()
    private val _display = MutableStateFlow<DisplayLocation?>(null)
    val displayLocation: StateFlow<DisplayLocation?> = _display.asStateFlow()

    /** AC 76 (D172): the running 10 s wait after a my-location press with no shown position. */
    private var locateJob: Job? = null

    /** AC 76: the «10 s» card is the my-location timeout's (it closes by itself when a showable fix arrives). */
    private var locateCardShown = false

    /** Set once a location action proceeded (permission and services OK): the map may show «Миний байршил». */
    private val mapLocationWanted = MutableStateFlow(false)

    /** Map centre for the search bias when there is no fresh device fix (D30). Updated by the map. */
    @Volatile var mapCenter: LatLon = DEFAULT_CENTER

    /** NAV-018 (ADR-0015 §5): one `search` 429 cooldown for the device, shared by both search instances. */
    private val searchCooldown = SearchCooldown()

    val search = SearchController(
        scope = viewModelScope,
        search = { q, l, b -> searchClient.search(q, l, b) },
        lang = { settings.lang.value },
        bias = { biasPoint() },
        isOnline = { network.isOnline() },
        now = { SystemClock.elapsedRealtime() },
        cooldown = searchCooldown,
    )

    /**
     * NAV-018 AC 4 (ADR-0015 §5): the route-preview fields' own instance of the same search code (same profile, bias and
     * assistance), so the map-screen query and list survive while the preview is open. One field list at a time.
     */
    val fieldSearch = SearchController(
        scope = viewModelScope,
        search = { q, l, b -> searchClient.search(q, l, b) },
        lang = { settings.lang.value },
        bias = { biasPoint() },
        isOnline = { network.isOnline() },
        now = { SystemClock.elapsedRealtime() },
        cooldown = searchCooldown,
    )

    /** NAV-018 UI state (point editor, activated turn row): ViewModel memory only, never saved or logged (ADR-0015 §10). */
    private val _points = MutableStateFlow(PointsUi())
    val points: StateFlow<PointsUi> = _points.asStateFlow()

    /** NAV-018 (ADR-0015 §4): a location fix sets the start only for the attempt that asked for it. */
    private val originAttempts = OriginAttempts()

    val preview = PreviewController(
        scope = viewModelScope,
        fetch = { req -> routes.fetch(req, 0) },
        lang = { settings.lang.value },
        // NAV-021 AC 8 (D163): with a usable routing file a preview needs no network (answered on the device, 0 requests).
        isOnline = { network.isOnline() || onDeviceRouting.available() },
        elapsedNow = { SystemClock.elapsedRealtime() },
        wallNow = { System.currentTimeMillis() },
    )

    /** NAV-011 AC 8–13: nearest place on the coordinate card (one `reverse` per card). */
    val reverse = ReverseController(
        scope = viewModelScope,
        reverse = { p, l -> reverseClient.reverse(p, l) },
        lang = { settings.lang.value },
        isOnline = { network.isOnline() },
        now = { SystemClock.elapsedRealtime() },
    )

    /** NAV-011 section E: typing lock while moving, with the passenger override (process memory only). */
    val typingLock = TypingLockController(lockFixes) { SystemClock.elapsedRealtime() }

    val guidance: StateFlow<GuidanceState?> = session.engine
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val guiding: Boolean get() = session.engine.value != null

    /**
     * Map-screen location (ADR-0009 Amendment 1 §9): fixes for the my-location dot and the search bias. The platform
     * request is registered only while this flow is collected, and the Activity collects it only while it is at least
     * STARTED ([collectMapLocation] inside `repeatOnLifecycle(STARTED)`). While guidance runs (also on the arrival
     * panel) no map request is made; the guidance provider is the only listener. Nothing is registered in the
     * background without the foreground service (AC 16, 19).
     */
    val mapLocation: Flow<Fix> = combine(mapLocationWanted, session.engine.map { it != null }) { wanted, guiding -> wanted && !guiding }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) location.mapUpdates().catch { } else emptyFlow() }
        .onEach {
            _myLocation.value = it
            onDisplay(displayFilter.onFix(it, SystemClock.elapsedRealtime()))
        }

    /**
     * Called by the Activity from `repeatOnLifecycle(Lifecycle.State.STARTED)`; returns when the Activity stops. The
     * AC 76 stale timer runs only while this is collected (Amendment 7 §9.2 step 7), so it costs nothing in the
     * background or during guidance.
     */
    suspend fun collectMapLocation() = coroutineScope {
        launch { runStaleTimer() }
        mapLocation.collect()
    }

    /**
     * AC 76: the shown position turns stale 10 s after the last showable fix. Instead of a fixed 1 s ticker it sleeps
     * until that deadline (+1 ms) and re-evaluates; every new output restarts the wait, so the switch lands within 1 s.
     */
    private suspend fun runStaleTimer() {
        _display.collectLatest { d ->
            var cur = d
            while (cur != null && !cur.stale) {
                val wait = cur.lastShowableElapsedMs + BrowseDotRules.STALE_MS - SystemClock.elapsedRealtime() + 1
                delay(wait.coerceAtLeast(1))
                cur = displayFilter.tick(SystemClock.elapsedRealtime())
                _display.value = cur
            }
        }
    }

    private fun onDisplay(d: DisplayLocation?) {
        _display.value = d
        if (d == null) return
        // AC 76: a showable fix during the wait ends it; on the «10 s» card the pending action continues (F3).
        if (locateJob?.isActive == true) {
            locateJob?.cancel()
            _ui.update { it.copy(locating = false) }
        }
        if (locateCardShown) {
            locateCardShown = false
            if (_ui.value.mapProblem == LocationProblem.UNAVAILABLE) _ui.update { it.copy(mapProblem = null, followingMe = true) }
        }
    }

    /** Amendment 7 §9.3: back to "no shown position" (guidance start; location permission or services lost). */
    private fun resetDisplay() {
        displayFilter.reset()
        _display.value = null
    }

    private var pending: LocationAction? = null
    private var askedLocation = false
    private var askedNotifications = false

    init {
        viewModelScope.launch {
            network.validated.drop(1).collect {
                if (it) {
                    preview.onNetworkRestored()
                    reverse.onNetworkRestored() // NAV-011 AC 11: one request ≤ 2 s after the network returns
                }
            }
        }
        viewModelScope.launch { settings.lang.drop(1).collect { voice.onLanguageChanged() } }
        // Amendment 7 §9.3: guidance takes over the position (puck); afterwards the browse map starts from "no fix yet".
        viewModelScope.launch {
            session.engine.map { it != null }.distinctUntilChanged().collect { guiding -> if (guiding) resetDisplay() }
        }
        // ADR-0016 §3 (NAV-019 AC 23, 25): in a replay build every end of guidance (screen, notification, lock screen,
        // end of the recorded track) returns to the start screen, so the recorded preview is closed with it.
        if (replay != null) {
            viewModelScope.launch {
                session.engine.map { it != null }.distinctUntilChanged().drop(1).collect { alive -> if (!alive) closePreview() }
            }
        }
    }

    /**
     * D30 search bias, NAV-005 AC 78 (D174): the shown position when my location is on, the camera follows and the last
     * showable fix (a held one counts) is ≤ 60 s old; otherwise the map centre. Rounded to 3 decimals by the client.
     */
    private fun biasPoint(): LatLon {
        val d = _display.value
        val fresh = d != null && SystemClock.elapsedRealtime() - d.lastShowableElapsedMs <= Fix.PREVIEW_FRESH_MS
        return if (d != null && fresh && _ui.value.followingMe) d.latLon else mapCenter
    }

    // ------------------------------------------------------------------------------------------- search, card

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q, searchActive = true) }
        search.onQuery(q)
    }

    fun onSearchActive(active: Boolean) = _ui.update { it.copy(searchActive = active) }

    /** NAV-011 re-focus: the search field gained focus (typing allowed); a kept coordinate query shows its option again. */
    fun onSearchFocused() {
        val q = _ui.value.query
        if (q.isNotEmpty() && search.onRefocus(q)) _ui.update { it.copy(searchActive = true) }
    }

    fun clearSearch() {
        _ui.update { it.copy(query = "") }
        search.close()
    }

    fun onResult(info: PlaceDisplay.Info, name: String) {
        _ui.update { it.copy(searchActive = false, card = null) }
        reverse.close()
        openPreview(Destination(info.point, name))
    }

    fun onLongPress(p: LatLon) {
        // AC 9: a long-press never moves the camera, also not a typed option's C1 move that has not run yet.
        _ui.update { it.copy(card = p, searchActive = false, coordinateFocus = null, cardTitleFocus = false) }
        reverse.open(p) // NAV-011 AC 8: exactly one `reverse` per card
    }

    /**
     * NAV-011 AC 7a, 8 (D140, D142, D146; ADR-0012 Amendment A3): the typed-coordinate option. The list closes as after a
     * result (the field keeps the typed text), the card opens exactly as for a long-press (one `reverse` at the typed
     * point), following stops so the follow effect does not pull the camera back, and NavRoot centres the point once
     * (camera rule C1). 0 `search` requests.
     */
    fun onCoordinateOption(p: LatLon) {
        search.close()
        coordinateFocusSeq++
        _ui.update {
            it.copy(
                card = p, searchActive = false, followingMe = false,
                coordinateFocus = CoordinateFocus(p, coordinateFocusSeq), cardTitleFocus = true,
            )
        }
        reverse.open(p) // NAV-011 AC 8: exactly one `reverse` per card
    }

    private var coordinateFocusSeq = 0

    /** NavRoot ran (or dropped) the C1 move [seq]: a rotation does not repeat it. */
    fun onCoordinateFocusHandled(seq: Int) = _ui.update { if (it.coordinateFocus?.seq == seq) it.copy(coordinateFocus = null) else it }

    fun onCardTitleFocused() = _ui.update { it.copy(cardTitleFocus = false) }

    fun closeCard() {
        _ui.update { it.copy(card = null, coordinateFocus = null, cardTitleFocus = false) }
        reverse.close()
    }

    fun onCardDirections() {
        val p = _ui.value.card ?: return
        _ui.update { it.copy(card = null) }
        reverse.close()
        // AC 9: the destination text stays «Сонгосон цэг» (the nearest place is not the point's address).
        openPreview(Destination(p, null))
    }

    // ------------------------------------------------------------------------------------------- typing lock (NAV-011)

    /** Called inside `repeatOnLifecycle(STARTED)` while S1/S3 are shown (not during guidance); returns when cancelled. */
    suspend fun collectTypingLock() = typingLock.collect()

    /** A tap on the search field: true → focus and keyboard; false → the lock card is shown instead (AC 31). */
    fun onSearchFieldTap(): Boolean = typingLock.onTextFieldTap()

    /** The lock engaged while the keyboard was open: the UI hid it; show the card (AC 31). */
    fun onLockedWhileTyping() {
        typingLock.onTextFieldTap()
    }

    fun dismissTypingLock() = typingLock.dismissCard()

    /** «Би зорчигч» (AC 34): override for the app session; the field takes focus and the keyboard opens. */
    fun onPassenger() {
        typingLock.passenger()
        _ui.update { it.copy(focusSearch = it.focusSearch + 1) }
    }

    // ------------------------------------------------------------------------------------------- preview

    private var currentStatus: () -> Pair<PermissionStatus, Boolean> = { PermissionStatus.NOT_ASKED to true }

    /** NAV-011 P4: the Activity reports whether TalkBack touch exploration is on (the sheet then opens expanded). */
    var touchExploration: () -> Boolean = { false }

    /** The Activity provides the platform permission state (precise/approximate/denied, services on). */
    fun bindPermissionState(provider: () -> Pair<PermissionStatus, Boolean>) {
        currentStatus = provider
    }

    private fun openPreview(d: RoutePoint, expanded: Boolean = false) {
        voice.prepare() // navigation-ux §4.6: TTS initialises when the preview opens
        _ui.update { it.copy(sheetExpanded = expanded || touchExploration()) } // P4: collapsed, TalkBack → expanded
        // NAV-018 AC 32: a new preview starts from AC 1–2 (no chosen start kept); no attempt of an older preview applies.
        resetPoints()
        preview.open(d)
        requireLocation(LocationAction.PREVIEW_ORIGIN)
    }

    fun closePreview() {
        resetPoints()
        preview.close()
    }

    /**
     * ADR-0016 §3 (NAV-019 AC 9): a replay build's start screen opens the normal preview for a recorded response, with
     * 0 requests and no device position (the start is the recorded route's chosen start).
     */
    fun openRecordedRoute(outcome: RouteOutcome.Ok, origin: RoutePoint, destination: RoutePoint, mode: TravelMode) {
        voice.prepare() // navigation-ux §4.6: TTS initialises when the preview opens
        _ui.update { it.copy(sheetExpanded = touchExploration(), card = null, mapProblem = null, searchActive = false) }
        resetPoints()
        preview.showRoute(outcome, origin, destination, mode)
    }

    private fun resetPoints() {
        originAttempts.cancel()
        if (pending == LocationAction.PREVIEW_ORIGIN) pending = null
        _points.value = PointsUi()
        fieldSearch.close()
    }

    // ------------------------------------------------------------------------------------------- points (NAV-018)

    /** A tap on the start or destination field: the point editor opens in the search position (Q5); 0 requests. */
    fun openPointEditor(side: PointSide) {
        if (preview.state.value == null) return
        fieldSearch.close()
        editorSessions++
        _points.update { it.copy(editor = PointEditorState(side, session = editorSessions)) }
    }

    /** Each editor opening is a new session (its text starts from the field text again). */
    private var editorSessions = 0

    /** Back, a map tap or «Хаах»: the editor closes, the field shows its previous text, 0 requests (AC 7). */
    fun closePointEditor() {
        val e = _points.value.editor ?: return
        // A «Миний байршил» attempt started from the editor (waiting, or failed with a PREVIEW_ORIGIN action still pending,
        // e.g. services off) ends with it, so a later onResume never replaces the start (ADR-0015 §4, AC 10). The
        // preview-open attempt (no start yet) goes on.
        if (e.myLocation != MyLocationOption.Idle && preview.state.value?.origin != null) {
            originAttempts.cancel()
            if (pending == LocationAction.PREVIEW_ORIGIN) pending = null
        }
        fieldSearch.close()
        if (typingLock.state.value.cardVisible) typingLock.dismissCard()
        _points.update { it.copy(editor = null) }
    }

    fun onPointQuery(q: String) = fieldSearch.onQuery(q)

    /** AC 4: a result sets the edited point to the feature's coordinate; the editor closes; one request (both set). */
    fun onPointResult(info: PlaceDisplay.Info, name: String) {
        val e = _points.value.editor ?: return
        _points.update { it.copy(editor = null) }
        fieldSearch.close()
        val p = RoutePoint.Place(info.point, name)
        if (e.side == PointSide.ORIGIN) setChosenOrigin(p) else preview.setDestination(p)
    }

    /**
     * AC 5, second bullet (D140/D145, ADR-0012 Amendment A3): choosing the typed-coordinate option in a field sets that
     * point to the coordinate («Сонгосон цэг»), with no coordinate card, 0 `reverse` and 0 `search` requests; one route
     * request once both points are set (PreviewController).
     */
    fun onPointTypedCoordinate(point: LatLon) {
        val e = _points.value.editor ?: return
        _points.update { it.copy(editor = null) }
        fieldSearch.close()
        val p = RoutePoint.TypedCoordinate(point)
        if (e.side == PointSide.ORIGIN) setChosenOrigin(p) else preview.setDestination(p)
    }

    /** AC 3: «Миний байршил» in the start editor runs the NAV-005 flow; on failure the start stays, 0 requests. */
    fun onPointMyLocation() {
        if (_points.value.editor?.side != PointSide.ORIGIN) return
        _points.update { it.copy(editor = it.editor?.copy(myLocation = MyLocationOption.Waiting)) }
        requireLocation(LocationAction.PREVIEW_ORIGIN)
    }

    /** AC 6: «Эхлэх цэг болгох» / «Очих газар болгох» on the coordinate card during the preview («Сонгосон цэг»). */
    fun onCardSetPoint(side: PointSide) {
        val p = _ui.value.card ?: return
        _ui.update { it.copy(card = null) }
        reverse.close()
        val point = RoutePoint.MapPoint(p)
        if (side == PointSide.ORIGIN) setChosenOrigin(point) else preview.setDestination(point)
    }

    /** AC 11: swap (one request); a running location attempt can no longer set the start. */
    fun swapPoints() {
        if (preview.state.value?.origin == null) return
        cancelOriginAttempt()
        preview.swap()
    }

    /**
     * AC 22 (Q8): activates turn-list row [index] of the selected route; the camera moves to it ([collapse]: portrait
     * touch use collapses the sheet first). 0 requests; the route, the selection and the list position stay.
     */
    fun focusStep(index: Int, collapse: Boolean) {
        val r = preview.state.value?.result as? PreviewResult.Route ?: return
        val step = r.route.plan.steps.getOrNull(index) ?: return
        if (collapse) setSheetExpanded(false)
        _points.update { it.copy(step = StepFocus(r.route, index, step.location, (it.step?.seq ?: 0) + 1, collapse)) }
    }

    private fun setChosenOrigin(p: RoutePoint) {
        cancelOriginAttempt()
        preview.setOrigin(p)
    }

    /** ADR-0015 §4: the user set the start; a late fix must not overwrite it, and no pending origin action resumes. */
    private fun cancelOriginAttempt() {
        originAttempts.cancel()
        if (pending == LocationAction.PREVIEW_ORIGIN) pending = null
        _points.update { it.copy(editor = it.editor?.copy(myLocation = MyLocationOption.Idle)) }
    }

    /** A PREVIEW_ORIGIN problem: the start field's own message (AC 2), or inside the editor's option card (AC 3). */
    private fun originProblem(p: LocationProblem) {
        val waiting = _points.value.editor?.myLocation == MyLocationOption.Waiting
        if (waiting) _points.update { it.copy(editor = it.editor?.copy(myLocation = MyLocationOption.Failed(p))) }
        val s = preview.state.value
        if (s == null) _ui.update { it.copy(mapProblem = p) } else if (s.origin == null) preview.locationProblem(p)
    }
    fun setMode(m: TravelMode) = preview.setMode(m)

    /** NAV-011 AC 17: a line tap or a «Маршрут сонгох» row; 0 requests, no camera move. */
    fun selectRoute(index: Int) = preview.select(index)

    fun setSheetExpanded(expanded: Boolean) = _ui.update { it.copy(sheetExpanded = expanded) }
    fun setAvoid(v: Boolean) = preview.setAvoidUnpaved(v)
    fun retryPreview() {
        val r = preview.state.value?.result
        if (r is PreviewResult.Location) requireLocation(LocationAction.PREVIEW_ORIGIN) else preview.retry()
    }

    // ------------------------------------------------------------------------------------------- location access

    fun requireLocation(action: LocationAction) {
        pending = action
        val (status, services) = currentStatus()
        apply(action, LocationAccess.decide(action, status, services))
    }

    private fun apply(action: LocationAction, d: LocationAccess.Decision) {
        when (d) {
            LocationAccess.Decision.Proceed -> {
                _ui.update { it.copy(mapProblem = null) }
                pending = null
                run(action)
            }
            LocationAccess.Decision.ShowRationale -> _ui.update { it.copy(rationale = true) }
            is LocationAccess.Decision.Problem -> if (action == LocationAction.PREVIEW_ORIGIN) originProblem(d.problem) else showProblem(d.problem)
        }
    }

    private fun showProblem(p: LocationProblem) {
        if (preview.state.value != null) preview.locationProblem(p) else _ui.update { it.copy(mapProblem = p) }
    }

    fun onRationaleContinue() = _ui.update { it.copy(rationale = false, requestLocationPermission = true) }

    fun onRationaleClose() {
        _ui.update { it.copy(rationale = false) }
        // NAV-018: for the start, a set start is never replaced by the message (AC 3); the editor card shows it instead.
        if (pending == LocationAction.PREVIEW_ORIGIN) {
            originProblem(LocationProblem.NEEDS_PERMISSION)
        } else if (preview.state.value != null) {
            preview.locationProblem(LocationProblem.NEEDS_PERMISSION)
        }
        pending = null
    }

    fun onLocationPermissionResult() {
        askedLocation = true
        _ui.update { it.copy(requestLocationPermission = false) }
        val action = pending ?: return
        val (status, services) = currentStatus()
        apply(action, LocationAccess.afterRequest(action, status, services))
    }

    val hasAskedLocation: Boolean get() = askedLocation

    /** AC 11–12: back from the system settings with the permission / location on → the pending action continues. */
    fun onResume() {
        // Amendment 7 §9.3: permission or location services lost while away → no shown position (AC 76).
        val (st, on) = currentStatus()
        if (!on || (st != PermissionStatus.PRECISE && st != PermissionStatus.APPROXIMATE)) resetDisplay()
        val action = pending ?: return
        val (status, services) = currentStatus()
        val d = LocationAccess.decide(action, status, services)
        if (d == LocationAccess.Decision.Proceed) apply(action, d)
    }

    fun dismissMapProblem() {
        // AC 76: «Хаах» on the «10 s» card ends the wait; a later showable fix shows the dot without moving the camera.
        locateCardShown = false
        _ui.update { it.copy(mapProblem = null) }
        pending = null
    }

    private fun run(action: LocationAction) {
        startLocationUpdates()
        when (action) {
            LocationAction.PREVIEW_ORIGIN -> {
                // NAV-018 (ADR-0015 §4): only this attempt's fix may set the start; a start the user chose meanwhile wins.
                val token = originAttempts.begin()
                viewModelScope.launch {
                    if (preview.state.value?.origin == null) preview.waitingForLocation()
                    val fix = freshFix(Fix.PREVIEW_FRESH_MS)
                    if (!originAttempts.finish(token)) return@launch
                    val point = fix?.let { PointRules.myLocation(it, SystemClock.elapsedRealtime()) }
                    if (point == null) {
                        originProblem(LocationProblem.UNAVAILABLE)
                    } else {
                        if (_points.value.editor?.side == PointSide.ORIGIN) _points.update { it.copy(editor = null) }
                        fieldSearch.close()
                        preview.setOrigin(point)
                    }
                }
            }
            LocationAction.START_GUIDANCE -> viewModelScope.launch { startGuidanceWithFix() }
            LocationAction.MY_LOCATION -> {
                _ui.update { it.copy(followingMe = true) }
                // AC 76 (D172): with no shown position yet, wait up to 10 s for the first showable fix (no new request:
                // the map listener above is the only one); a shown (also stale) dot is centred at once, no message.
                if (_display.value == null) startLocateWait()
            }
        }
    }

    /** AC 76 / screen spec: `location_searching` up to 10 s, then «Байршил тодорхойлж чадсангүй»; 0 route requests. */
    private fun startLocateWait() {
        locateJob?.cancel()
        locateCardShown = false
        _ui.update { it.copy(locating = true) }
        locateJob = viewModelScope.launch {
            val got = withTimeoutOrNull(BrowseDotRules.LOCATE_TIMEOUT_MS) { _display.first { it != null } }
            if (got != null) return@launch // onDisplay() already ended the wait
            _ui.update { it.copy(locating = false, followingMe = false) }
            // The S1 message only (the button lives on S1/S2); an open route preview keeps its own location states.
            if (preview.state.value == null && session.engine.value == null) {
                locateCardShown = true
                _ui.update { it.copy(mapProblem = LocationProblem.UNAVAILABLE) }
            }
        }
    }

    private fun cancelLocateWait() {
        locateJob?.cancel()
        locateJob = null
        if (_ui.value.locating) _ui.update { it.copy(locating = false) }
    }

    private suspend fun freshFix(maxAgeMs: Long): Fix? {
        val now = SystemClock.elapsedRealtime()
        _myLocation.value?.takeIf { it.isGood(now, maxAgeMs) }?.let { return it }
        return location.freshGoodFix()?.takeIf { it.isGood(SystemClock.elapsedRealtime(), maxAgeMs) }
    }

    private fun startLocationUpdates() {
        mapLocationWanted.value = true
    }

    fun onMyLocation() {
        // Already following a shown dot, or already waiting for the first one (screen spec: a second press does nothing).
        // Following with no dot and no wait (e.g. the dot was reset) arms the AC 76 wait again (Amendment 7 §9.5).
        if (_ui.value.followingMe && (_display.value != null || _ui.value.locating)) return
        requireLocation(LocationAction.MY_LOCATION)
    }

    /** A map gesture on S1–S3: following stops; a running AC 76 wait ends with no card later (screen spec). */
    fun stopFollowingMe() {
        cancelLocateWait()
        _ui.update { it.copy(followingMe = false) }
    }

    // ------------------------------------------------------------------------------------------- guidance

    /** «Эхлэх» (AC 15): F4 checks (precise location, fresh good fix), then the guidance screen within 1 s. */
    fun onStart() {
        if (preview.state.value?.canStart != true) return
        if (!askedNotifications) {
            askedNotifications = true
            _ui.update { it.copy(requestNotificationPermission = true) }
        }
        requireLocation(LocationAction.START_GUIDANCE)
    }

    fun onNotificationPermissionHandled() = _ui.update { it.copy(requestNotificationPermission = false) }

    private suspend fun startGuidanceWithFix() {
        val s = preview.state.value ?: return
        val route = (s.result as? PreviewResult.Route)?.route ?: return
        val fix = freshFix(Fix.FRESH_MS)
        if (fix == null) {
            preview.locationProblem(LocationProblem.UNAVAILABLE)
            return
        }
        // NAV-019 edge case: a double tap on «Эхлэх» starts one replay only (no second depart prompt).
        if (replay != null && session.engine.value != null) return
        _ui.update { it.copy(orientation = Orientation.HEADING_UP, cameraFollowing = true, followingMe = false) }
        session.start(route, Trip(s.destination.point, PointRules.storedName(s.destination), s.mode, s.avoidUnpaved && s.mode == TravelMode.CAR), fix)
    }

    fun onEnd() {
        session.end()
        preview.close()
    }

    /** S6 «Хаах» after arrival. */
    fun onArrivalClose() = onEnd()

    fun toggleMute() = settings.setMuted(!settings.muted.value)

    fun toggleOrientation() = _ui.update {
        it.copy(orientation = if (it.orientation == Orientation.HEADING_UP) Orientation.NORTH_UP else Orientation.HEADING_UP)
    }

    fun onMapGesture() = _ui.update { it.copy(cameraFollowing = false, lastGestureAt = SystemClock.elapsedRealtime(), followingMe = false) }
    fun recenter() = _ui.update { it.copy(cameraFollowing = true) }
    fun dismissVoiceNotice() = session.engine.value?.dismissVoiceNotice()

    // ------------------------------------------------------------------------------------------- settings, map

    fun openSettings(open: Boolean) = _ui.update { it.copy(settingsOpen = open) }
    fun setTheme(t: ThemeChoice) = settings.setTheme(t)
    fun setLanguage(l: Lang) = settings.setLanguage(l)
    fun setVoice(on: Boolean) = settings.setMuted(!on)
    fun setTilesFailed(v: Boolean) = _ui.update { it.copy(tilesFailed = v) }

    companion object {
        /** D13: Ulaanbaatar, P1, zoom 12. */
        val DEFAULT_CENTER = LatLon(47.9189, 106.9176)
        const val DEFAULT_ZOOM = 12.0
    }
}
