package app.smugly.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import app.smugly.ui.components.AnimatedModalCard
import app.smugly.ui.components.ConfirmDialog
import app.smugly.ui.theme.SmuglyTextPrimary
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyInput
import app.smugly.ui.theme.SmuglyStroke
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.GlobalSettings
import app.smugly.S
import app.smugly.t
import app.smugly.ui.ConnectUiState
import app.smugly.ui.EditorDraft
import app.smugly.subscription.Subscription
import app.smugly.subscription.SubscriptionCategory
import app.smugly.ui.PlatformBackHandler
import app.smugly.ui.rememberTickHaptic
import app.smugly.ui.components.AccentLinkButton
import app.smugly.ui.components.BottomConnectBar
import app.smugly.ui.components.CategorySection
import app.smugly.ui.components.HintText
import app.smugly.ui.components.LabeledField
import app.smugly.ui.components.DropdownField
import app.smugly.ui.components.FolderDraft
import app.smugly.ui.components.FolderEditorDialog
import app.smugly.ui.components.FolderTabs
import app.smugly.ui.components.JsonSyntaxHighlightTransformation
import app.smugly.ui.components.MenuLayer
import app.smugly.ui.components.MenuRow
import app.smugly.ui.components.PillSelector
import app.smugly.ui.components.PrimaryButton
import app.smugly.ui.components.ProfileCard
import app.smugly.ui.components.ProfileNameField
import app.smugly.ui.components.SecondaryButton
import app.smugly.ui.components.SectionTitle
import app.smugly.ui.components.SmuglyCheckbox
import app.smugly.ui.components.SmuglyTextField
import app.smugly.ui.components.SubscriptionCard
import app.smugly.ui.components.TopBar
import app.smugly.ui.profileSubtitle
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextSecondary

@Composable
fun HomeScreen(
    profiles: List<ConfigProfile>,
    activeId: String?,
    connect: ConnectUiState,
    onMenu: () -> Unit,
    onAddNew: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    onSelect: (ConfigProfile) -> Unit,
    onEdit: (ConfigProfile) -> Unit,
    onDelete: (ConfigProfile) -> Unit,
    onExport: (ConfigProfile) -> Unit,
    /** Persist new order after long-press drag (ids top→bottom). */
    onReorder: (orderedIds: List<String>) -> Unit = {},
    onToggle: () -> Unit,
    /** Imported subscriptions; empty means no folder tabs are shown at all. */
    subscriptions: List<Subscription> = emptyList(),
    onRefreshSubscription: (String) -> Unit = {},
    /** Id of the subscription currently being fetched, if any. */
    refreshingSubscriptionId: String? = null,
    onDeleteSubscription: (String) -> Unit = {},
    onRenameSubscription: (String, String) -> Unit = { _, _ -> },
    /**
     * True while the folder pager sits on the first folder. Past it a rightward swipe means "back
     * one folder", so whoever owns the drawer needs to stand down.
     */
    onFirstFolder: (Boolean) -> Unit = {},
    /**
     * True while a modal / dropdown on Home owns the pointer (folder editor, confirm, menus).
     * The host disables full-screen drawer-swipe so long-press text selection is not stolen.
     */
    onBlockDrawerGestures: (Boolean) -> Unit = {},
    /** Where the Home tab sits among the folders. */
    homeFolderIndex: Int = 0,
    /** Folder to open on entry: a subscription id, or blank for Home. */
    initialFolderId: String = "",
    /** Reports the folder now on screen so it can be restored next launch. */
    onFolderOpened: (String) -> Unit = {},
    /** Measured latency per profile id (see LatencyProbe); missing = never measured. */
    latencies: Map<String, app.smugly.ui.LatencyUi> = emptyMap(),
    onMeasureLatency: (ConfigProfile) -> Unit = {},
    /** Write every profile of one folder out as an importable file. */
    onExportFolder: (profiles: List<ConfigProfile>, folderName: String) -> Unit = { _, _ -> },
    /** Create ([FolderDraft.id] null) or update a folder. */
    onSaveFolder: (FolderDraft) -> Unit = {},
    /** Categories the user has folded away, as `subscriptionId/categoryId`. */
    collapsedCategories: Set<String> = emptySet(),
    onCollapsedCategoriesChange: (Set<String>) -> Unit = {},
    /** New tab order: subscription ids in order, plus the slot Home ended up in. */
    onReorderFolders: (subscriptionIds: List<String>, homeIndex: Int) -> Unit = { _, _ -> }
) {
    var addMenu by remember { mutableStateOf(false) }
    var moreFor by remember { mutableStateOf<ConfigProfile?>(null) }
    var menuProfile by remember { mutableStateOf<ConfigProfile?>(null) }
    // Menus are drawn by this screen (see MenuLayer) instead of in their own Popup
    // window, so each anchor reports where its panel should start. Positions land in
    // plain (non-snapshot) holders — they are written on every layout pass and are
    // only read when a menu actually opens.
    val addAnchor = remember { intArrayOf(0) }
    val cardAnchors = remember { mutableMapOf<String, Int>() }
    var menuAnchorY by remember { mutableStateOf(0) }
    /** Set only for the folder menu, which drops under its tab instead of the right edge. */
    var menuAnchorX by remember { mutableStateOf<Int?>(null) }

    // --- folders (v2rayNG-style groups) -------------------------------------------------
    // Folder 0 is always the user's own profiles; one folder per subscription after it.
    // With no subscriptions there is a single folder and the tab row hides itself.
    // A real pager, not a hand-rolled gesture: it tracks the finger while dragging and settles
    // itself. The previous version only acted on drag-end, which is why a swipe showed nothing
    // until it completed and then jumped.
    /**
     * Subscription whose folder tab was long-pressed. [folderMenu] keeps the rows populated
     * while the panel fades out; only [folderMenuOpen] is cleared on dismiss.
     */
    var folderMenu by remember { mutableStateOf<Subscription?>(null) }
    /** Slot the folder menu belongs to, or -1. Home is a folder too and has no [Subscription]. */
    var folderMenuSlot by remember { mutableStateOf(-1) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    /** Folder being created or edited; kept while the dialog animates out. */
    var folderDraft by remember { mutableStateOf<FolderDraft?>(null) }
    var folderEditorOpen by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf<Subscription?>(null) }
    val scope = rememberCoroutineScope()
    // Slots are needed to turn the stored folder id back into a page, and they are computed
    // below — this only reads the same two inputs, so it cannot disagree with them.
    val initialPage = remember(subscriptions, homeFolderIndex, initialFolderId) {
        val ordered = subscriptions.toMutableList<Subscription?>()
            .also { it.add(homeFolderIndex.coerceIn(0, subscriptions.size), null) }
        ordered.indexOfFirst { (it?.id ?: "") == initialFolderId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { subscriptions.size + 1 }
    )
    val folderIndex = pagerState.currentPage
    /** Tab the user just tapped, held until the pager finishes travelling to it. */
    var pendingTab by remember { mutableStateOf<Int?>(null) }
    /**
     * True only while [onSelect]/[onMove] is driving the pager. Distinguishes that from a
     * finger swipe so we can clear a stale [pendingTab] without nuking it between the
     * intermediate scrollToPage and animateScrollToPage of a far-tab jump.
     */
    var tabNavigating by remember { mutableStateOf(false) }
    /** True while a folder tab is being held / dragged. */
    var tabDragging by remember { mutableStateOf(false) }
    // Tab slots in display order; null is the Home folder. Home is not a subscription, so its
    // position cannot live in the subscription list — it comes from settings and is spliced in.
    val slots = remember(subscriptions, homeFolderIndex) {
        subscriptions.toMutableList<Subscription?>()
            .also { it.add(homeFolderIndex.coerceIn(0, subscriptions.size), null) }
            .toList()
    }
    val folderNames = remember(slots) {
        slots.map { sub -> sub?.let { it.name.ifBlank { it.url } } ?: t(S.HOME_FOLDER) }
    }
    val currentSubscription = slots.getOrNull(folderIndex)
    val folderProfiles = remember(profiles, folderIndex, subscriptions) {
        if (currentSubscription == null) {
            profiles.filter { it.subscriptionId == null }
        } else {
            profiles.filter { it.subscriptionId == currentSubscription.id }
        }
    }

    // `ordered` is ONLY the drag-time override. Displaying it unconditionally meant the list
    // still held the previous folder for one frame — LaunchedEffect runs after composition, so
    // switching folders flashed the old contents.
    var ordered by remember { mutableStateOf(folderProfiles) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragFromIndex by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var gapTargetIndex by remember { mutableStateOf(-1) }
    /**
     * Finger Y in root coordinates — absolute, rewritten every pointer event.
     *
     * Never accumulate drag deltas: the pointer-input node lives on the list item, and when the
     * LazyColumn scrolls or reorders that item the next dragAmount includes the layout jump as if
     * the finger moved. That drifted the virtual finger down during auto-scroll, killed scroll-up,
     * and made the card thrash at the bottom edge. Absolute localToRoot positions do not drift.
     */
    var pointerRootY by remember { mutableStateOf(0f) }
    /** How far down inside the card the finger grabbed it — the card hangs off this point. */
    var grabWithinCard by remember { mutableStateOf(0f) }
    /** Lazy-list key of the card being carried, so it can be found among the measured items. */
    var dragKey by remember { mutableStateOf<String?>(null) }
    /** An edge pulls only after the finger has been away from it at least once (see drag start). */
    var armScrollUp by remember { mutableStateOf(true) }
    var armScrollDown by remember { mutableStateOf(true) }

    /**
     * What the list actually renders. Straight from the folder unless a drag is in progress —
     * routing it through [ordered] meant a folder switch showed the previous folder for one frame,
     * because the effect that refills [ordered] only runs after composition.
     */
    val displayed = if (draggingId == null) folderProfiles else ordered

    LaunchedEffect(folderProfiles, draggingId) {
        if (draggingId == null) ordered = folderProfiles
    }

    // Who may own a horizontal drag right now. Past the first folder it is the pager's; while a
    // tab is held it is the tab's — otherwise dragging a tab rightwards pulled the drawer out
    // from under it. Handed back when this screen goes away.
    // `settledPage`, not `currentPage`: the latter flips halfway through a swipe, and gating it
    // on "not scrolling" would drop the final report entirely when the page it lands on is the
    // one it already flipped to.
    LaunchedEffect(pagerState.settledPage, slots) {
        onFolderOpened(slots.getOrNull(pagerState.settledPage)?.id ?: "")
    }
    // Finger-swipe on the pager while a tab-tap left pendingTab set: drop it so the strip
    // follows currentPage. Gated on !tabNavigating so the far-tab hop (scroll to neighbour,
    // then animate) does not clear pending between the two steps.
    LaunchedEffect(pagerState.isScrollInProgress, pagerState.currentPage, tabNavigating) {
        val pending = pendingTab ?: return@LaunchedEffect
        if (tabNavigating || pagerState.isScrollInProgress) return@LaunchedEffect
        if (pagerState.currentPage != pending) {
            pendingTab = null
        }
    }
    LaunchedEffect(folderIndex, tabDragging) {
        onFirstFolder(folderIndex == 0 && !tabDragging)
    }
    // Drawer swipe runs on the Initial pass of the ancestor and would otherwise steal
    // long-press + horizontal drag inside dialog text fields (opens the side drawer).
    LaunchedEffect(
        folderEditorOpen,
        folderMenuOpen,
        addMenu,
        moreFor != null,
        deletingFolder != null,
        draggingId != null
    ) {
        onBlockDrawerGestures(
            folderEditorOpen ||
                folderMenuOpen ||
                addMenu ||
                moreFor != null ||
                deletingFolder != null ||
                // A card being carried is not always carried straight up: the drawer gesture runs
                // on the Initial pass, so a drag with any sideways lean was handed to the drawer
                // mid-move and the card was simply dropped.
                draggingId != null
        )
    }
    DisposableEffect(Unit) {
        onDispose { onBlockDrawerGestures(false) }
    }
    DisposableEffect(Unit) { onDispose { onFirstFolder(true) } }

    // The menus are plain overlays, not focusable Popup windows, so back has to be
    // handled here — otherwise it would fall through and close the app.
    PlatformBackHandler(enabled = addMenu || moreFor != null || folderMenuOpen) {
        addMenu = false
        moreFor = null
        folderMenuOpen = false
    }

    val nowMs = app.smugly.platform.PlatformTime.currentTimeMillis()
    val tick = rememberTickHaptic()
    /** Height of the floating connect bar — the strip a dragged card must stay clear of. */
    val connectBarPx = with(LocalDensity.current) { 72.dp.toPx() }
    /**
     * Band at each end of the list where holding a dragged card scrolls it.
     *
     * Wide enough to reach while the finger still sits on a card (≈96dp), but capped so a short
     * viewport is not half-edge. Bottom zone is measured above the connect bar, not under it —
     * otherwise "scroll down" only armed once the finger was already on the bar.
     */
    val autoScrollEdgePx = with(LocalDensity.current) { 96.dp.toPx() }
    fun edgeZone(viewport: Float): Float = minOf(autoScrollEdgePx, viewport * 0.22f)

    Box(Modifier.fillMaxSize().background(SmuglyBg)) {
        // TopBar full-bleed (menu / + on window edges); list content keeps side padding.
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = t(S.HOME),
                onMenu = onMenu,
                onAdd = {
                    menuAnchorY = addAnchor[0]
                    menuAnchorX = null
                    moreFor = null
                    folderMenuOpen = false
                    addMenu = true
                },
                onAddAnchor = { addAnchor[0] = it }
            )
            FolderTabs(
                names = folderNames,
                // The tab the user tapped lights up on the tap, not when the pager gets there:
                // the one-page approach below parks on the neighbour first, and following
                // currentPage made the *middle* tab flash before the target.
                // While pendingTab is set, a finger-swipe must still win: a cancelled
                // animateScrollToPage used to leave pendingTab stuck and freeze the highlight
                // on the old folder even as the pager moved underneath.
                selectedIndex = pendingTab ?: folderIndex,
                onSelect = { target ->
                    pendingTab = target
                    tabNavigating = true
                    scope.launch {
                        try {
                            // Always exactly one page of travel. Animating the whole distance would
                            // scroll through every folder in between and compose each on the way,
                            // which is what made a far tab feel like the app had hung; jumping
                            // outright lost the sense of direction. So land next to the target
                            // first, then slide the last page in.
                            val from = pagerState.currentPage
                            if (abs(target - from) > 1) {
                                pagerState.scrollToPage(if (target > from) target - 1 else target + 1)
                            }
                            pagerState.animateScrollToPage(target)
                        } finally {
                            // finally: swipe / cancel / dispose must not leave pendingTab stuck,
                            // or the top strip stops tracking pager swipes until the next tap.
                            pendingTab = null
                            tabNavigating = false
                        }
                    }
                },
                onMenuDismiss = { folderMenuOpen = false },
                onDragActive = { tabDragging = it },
                onMove = { from, to ->
                    // A move is expressed as the resulting slot order; whether Home moved or a
                    // subscription did falls out of where the null lands.
                    val next = slots.toMutableList().apply { add(to, removeAt(from)) }
                    // Follow the folder that was open, not the slot number it used to have —
                    // otherwise reordering silently swaps which folder you are looking at.
                    val open = slots.getOrNull(folderIndex)
                    val openNow = next.indexOfFirst { it?.id == open?.id }.coerceAtLeast(0)
                    onReorderFolders(
                        next.filterNotNull().map { it.id },
                        next.indexOfFirst { it == null }.coerceAtLeast(0)
                    )
                    pendingTab = openNow
                    tabNavigating = true
                    scope.launch {
                        try {
                            pagerState.scrollToPage(openNow)
                        } finally {
                            pendingTab = null
                            tabNavigating = false
                        }
                    }
                },
                onMenu = { index, x, y ->
                    // Home has no subscription to edit or delete, but it is still a folder: the
                    // two whole-folder actions apply to it exactly the same.
                    menuAnchorY = y
                    menuAnchorX = x
                    addMenu = false
                    moreFor = null
                    folderMenu = slots.getOrNull(index)
                    folderMenuSlot = index
                    folderMenuOpen = true
                }
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // A drag inside a profile row is a reorder, not a page change.
                userScrollEnabled = draggingId == null,
                beyondViewportPageCount = 0
            ) { page ->
                val pageSubscription = slots.getOrNull(page)
                // Remembered, not filtered inline: this runs for every visible page on every
                // recomposition, and a fresh list each time would make the LazyColumn re-diff
                // its items for nothing.
                val pageProfiles = remember(profiles, pageSubscription?.id) {
                    if (pageSubscription == null) {
                        profiles.filter { it.subscriptionId == null }
                    } else {
                        profiles.filter { it.subscriptionId == pageSubscription.id }
                    }
                }
                // Only the visible page owns the drag state, so a half-swiped neighbour never
                // renders a drag in progress.
                val live = page == folderIndex
                val pageDisplayed = if (live && draggingId != null) ordered else pageProfiles

                if (pageDisplayed.isEmpty() && pageSubscription == null) {
                    // BottomConnectBar is drawn as an overlay (not in this Column), so centering
                    // in raw fillMaxSize sits too low. Same 112.dp clearance LazyColumn uses.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp)
                            .padding(bottom = 112.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = t(S.NO_PROFILES_HINT),
                            color = SmuglyTextSecondary,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    // Lazy on purpose. A folder can hold a hundred servers and a plain Column
                    // composes every one of them — each ProfileCard carries eight animation states
                    // — on the single frame the folder becomes visible, which is exactly what made
                    // switching to a big folder stall. Only the cards actually on screen are built
                    // now. (This is the opposite call from ProfileEditorScreen, where LazyColumn
                    // lost: that is a fixed ~25 heterogeneous rows, this is an unbounded list of
                    // identical ones.)
                    // What the list actually renders: the servers, plus a heading per category
                    // when the panel groups them. Built in one place so the two cases cannot
                    // drift apart — a folder without categories produces exactly the flat list
                    // it always did.
                    val rows = remember(pageDisplayed, pageSubscription?.categories, collapsedCategories) {
                        folderRows(
                            profiles = pageDisplayed,
                            categories = pageSubscription?.categories.orEmpty(),
                            folderKey = pageSubscription?.id.orEmpty(),
                            collapsed = collapsedCategories
                        )
                    }
                    val listState = rememberLazyListState()
                    /** List top in root coords + its height: the frame the finger is judged against. */
                    val listBounds = remember { floatArrayOf(0f, 0f) }
                    /** Layout of the list host — converts pointer events to root Y while dragging. */
                    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    // Which way the list creeps while a card is held against an edge, in px per
                    // frame. Dragging a profile in a long folder was otherwise a dead end: the
                    // list cannot be scrolled by the finger that is holding a card.
                    var autoScroll by remember { mutableStateOf(0f) }
                    /** Guards dropCard so end+cancel+parent-up cannot persist twice. */
                    var dropStarted by remember { mutableStateOf(false) }

                    /**
                     * Hang the held card off the finger and, if its centre has crossed a neighbour's
                     * centre, swap the two — both from the list's **own measurements**.
                     *
                     * Offset is always recomputed from absolute finger Y vs the item's current
                     * layout slot, so a scroll or a swap cannot leave the card lagging. Swaps use
                     * centre-crossing hysteresis (not "any overlap") so the card cannot thrash
                     * between two slots at the bottom edge.
                     */
                    fun trackDrag() {
                        val key = dragKey ?: return
                        val info = listState.layoutInfo
                        val me = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
                        // Keys are exactly what the rows were built with, so this maps a measured
                        // item back to the profile sitting in that slot.
                        val indexOfKey = HashMap<String, Int>(ordered.size)
                        val seen = HashMap<String, Int>()
                        ordered.forEachIndexed { i, p ->
                            val n = (seen[p.id] ?: 0) + 1
                            seen[p.id] = n
                            indexOfKey[if (n == 1) p.id else p.id + "#" + n] = i
                        }
                        val from = indexOfKey[key] ?: return
                        // Keep the card inside the list: dragged past either end it would be drawn
                        // under the tab bar or the connect bar, where it is clipped away.
                        val wantTop = (pointerRootY - listBounds[0]) - grabWithinCard
                        // The card may travel the whole viewport except the strip the connect bar
                        // covers. The bar's own height is the line where it is still both near the
                        // finger and visible.
                        val lowest = info.viewportStartOffset.toFloat()
                        val highest = (info.viewportEndOffset - connectBarPx - me.size)
                            .coerceAtLeast(lowest)
                        val top = wantTop.coerceIn(lowest, highest)
                        dragOffsetY = top - me.offset
                        val center = top + me.size / 2f
                        // Nearest reorderable neighbour by centre distance; swap only once our
                        // centre has crossed theirs in that direction. Overlap-only targeting
                        // oscillated at the bottom: after a swap the centre still sat inside the
                        // previous neighbour's bounds and immediately swapped back.
                        val target = info.visibleItemsInfo
                            .asSequence()
                            .filter { other ->
                                other.key != key &&
                                    indexOfKey.containsKey(other.key as? String)
                            }
                            .minByOrNull { other ->
                                abs((other.offset + other.size / 2f) - center)
                            } ?: return
                        val to = indexOfKey[target.key as String] ?: return
                        if (to == from) return
                        val otherCenter = target.offset + target.size / 2f
                        val crossed = if (to > from) center > otherCenter else center < otherCenter
                        if (!crossed) return
                        val next = ordered.toMutableList()
                        next.add(to, next.removeAt(from))
                        ordered = next
                        dragFromIndex = to
                        gapTargetIndex = to
                        // Rebase onto the slot the card is about to occupy. Moving down it rests
                        // against the target's far edge; moving up it takes the target's place.
                        val landing = if (to > from) {
                            target.offset + target.size - me.size
                        } else {
                            target.offset
                        }
                        dragOffsetY = top - landing
                        tick()
                    }

                    /** Scroll speed from current finger Y vs list bounds. 0 when not in a zone. */
                    fun scrollSpeedForFinger(): Float {
                        val viewport = listBounds[1]
                        if (viewport <= 0f) return 0f
                        // Usable height excludes the connect bar so the bottom zone is reachable
                        // while the finger is still on a card, not only under the bar.
                        val usable = (viewport - connectBarPx).coerceAtLeast(1f)
                        val y = pointerRootY - listBounds[0]
                        val edge = edgeZone(usable)
                        if (y > edge) armScrollUp = true
                        if (y < usable - edge) armScrollDown = true
                        return when {
                            y < edge && armScrollUp ->
                                -speedFor((edge - y) / edge)
                            y > usable - edge && armScrollDown ->
                                speedFor((y - (usable - edge)) / edge)
                            // Finger above the list still pulls up (long folder → top).
                            y < 0f && armScrollUp -> -speedFor(1f)
                            // Finger on/under the connect bar still pulls down.
                            y > usable && armScrollDown -> speedFor(1f)
                            else -> 0f
                        }
                    }

                    /**
                     * Put the card down where it is now: ride the leftover offset into its slot
                     * and persist the order. Idempotent — finger-up may arrive from the card
                     * gesture and from the list-level pointer tracker in the same frame.
                     */
                    fun dropCard() {
                        if (draggingId == null || dropStarted) return
                        dropStarted = true
                        autoScroll = 0f
                        dragKey = null
                        // The list is already in its final order — the card was carried through it
                        // as the finger crossed each slot. All that is left is to drop the
                        // leftover offset into the slot it sits in, and persist.
                        val finalOrder = ordered.map { it.id }
                        scope.launch {
                            // Ride the card down rather than teleporting it: clearing the offset in
                            // one frame read as a snap-back.
                            Animatable(dragOffsetY).animateTo(
                                targetValue = 0f,
                                animationSpec = tween(180, easing = FastOutSlowInEasing)
                            ) { dragOffsetY = value }
                            draggingId = null
                            dragFromIndex = -1
                            gapTargetIndex = -1
                            dragOffsetY = 0f
                            dropStarted = false
                            onReorder(finalOrder)
                        }
                    }

                    // While a card is held: each display frame re-place from the absolute finger,
                    // then creep the list if the finger sits in an edge zone.
                    //
                    // After scrollBy the next layout will shift every item by -moved, but
                    // dragOffsetY is still based on the old slots until trackDrag runs again.
                    // Without the immediate += moved the card draws one frame at top-moved and
                    // the next at top — that is the ~10px edge shake. The extra withFrameNanos
                    // wait we used before also halved the scroll rate (one step per two frames).
                    LaunchedEffect(draggingId) {
                        if (draggingId == null) return@LaunchedEffect
                        dropStarted = false
                        while (true) {
                            withFrameNanos { }
                            if (draggingId == null || dropStarted) break
                            trackDrag()
                            val speed = scrollSpeedForFinger()
                            autoScroll = speed
                            if (speed == 0f) continue
                            val moved = listState.scrollBy(speed)
                            if (moved != 0f) {
                                dragOffsetY += moved
                            }
                        }
                    }
                    /**
                     * One server row.
                     *
                     * Shared by the flat list and by the cards inside a category panel: a grouped
                     * folder is the same list with panels around parts of it, and writing the row
                     * twice is how the two would quietly drift apart.
                     *
                     * [inCategory] rows sit on the panel's own fill, so they are drawn a shade
                     * lighter and never reorder — the panel's contents are the panel's order.
                     */
                    val serverCard: @Composable (ConfigProfile, Int, String, Boolean, Modifier) -> Unit =
                        { profile, index, rowKey, inCategory, rowModifier ->
                            ServerRow(
                                profile = profile,
                                index = index,
                                inCategory = inCategory,
                                rowModifier = rowModifier,
                                activeId = activeId,
                                latency = latencies[profile.id],
                                draggingId = draggingId,
                                dragOffsetY = dragOffsetY,
                                reorderable = !inCategory && pageDisplayed.size > 1 &&
                                    (
                                        pageSubscription == null ||
                                            (
                                                pageSubscription.allowReorder &&
                                                    pageSubscription.categories.isEmpty()
                                                )
                                        ),
                                onSelect = { if (draggingId == null) onSelect(profile) },
                                // Servers in a subscription folder are replaced wholesale on
                                // refresh, so deleting one individually would just come back.
                                onDelete = if (pageSubscription == null) {
                                    { onDelete(profile) }
                                } else {
                                    null
                                },
                                onMoreClick = {
                                    menuAnchorY = cardAnchors[profile.id] ?: 0
                                    menuAnchorX = null
                                    addMenu = false
                                    folderMenuOpen = false
                                    menuProfile = profile
                                    moreFor = profile
                                },
                                onMoreAnchor = { y -> cardAnchors[profile.id] = y },
                                onDragStart = { startPointerRootY ->
                                    moreFor = null
                                    draggingId = profile.id
                                    dragFromIndex = index
                                    gapTargetIndex = index
                                    dragOffsetY = 0f
                                    pointerRootY = startPointerRootY
                                    dragKey = rowKey
                                    // Where inside the card it was grabbed. Measured once, while
                                    // the card is still sitting in its slot untranslated, so the
                                    // card keeps hanging off that exact point for the whole drag.
                                    grabWithinCard = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == rowKey }
                                        ?.let { startPointerRootY - listBounds[0] - it.offset }
                                        ?: 0f
                                    // An edge only starts pulling once the finger has been outside
                                    // it. Otherwise picking up the top or bottom card scrolls the
                                    // list the instant it is touched, before the user has asked
                                    // for anything.
                                    val usable = (listBounds[1] - connectBarPx).coerceAtLeast(1f)
                                    val y = startPointerRootY - listBounds[0]
                                    val edge = edgeZone(usable)
                                    armScrollUp = y > edge
                                    armScrollDown = y < usable - edge
                                    autoScroll = 0f
                                },
                                onDrag = { _, rootY ->
                                    // Absolute finger Y only. Placement runs in the frame loop —
                                    // calling trackDrag here after scrollBy would recompute from
                                    // still-stale layoutInfo and wipe the dragOffsetY += moved
                                    // glue, bringing the edge shake back.
                                    pointerRootY = rootY
                                },
                                onDragEnd = { dropCard() },
                                // Do NOT drop on cancel. After ~one screen of auto-scroll the
                                // lazy item is often disposed despite pin; that cancels the
                                // card's pointerInput and used to end the reorder at ~8–10
                                // slots. The list host below owns the drag until finger-up.
                                onDragCancel = { }
                            )
                        }
                    // Host owns pointer lifecycle for the whole drag: the card starts it (long
                    // press) but a LazyColumn recycle must not abort auto-scroll mid-list.
                    // pointerInput only while dragging — an always-on block (even one that
                    // returns immediately) can still fight the HorizontalPager's horizontal
                    // swipe and leave pendingTab / tab highlight desynced from the page.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                listBounds[0] = it.positionInRoot().y
                                listBounds[1] = it.size.height.toFloat()
                                listCoords = it
                            }
                            .then(
                                if (draggingId != null) {
                                    Modifier.pointerInput(draggingId) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event =
                                                    awaitPointerEvent(PointerEventPass.Final)
                                                val coords =
                                                    listCoords?.takeIf { it.isAttached }
                                                val change = event.changes
                                                    .maxByOrNull { it.uptimeMillis }
                                                    ?: continue
                                                if (coords != null) {
                                                    pointerRootY =
                                                        coords.localToRoot(change.position).y
                                                }
                                                // Finger up or system cancel — settle.
                                                if (event.changes.none { it.pressed }) {
                                                    dropCard()
                                                    break
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        state = listState,
                        userScrollEnabled = draggingId == null,
                        // Space for bar (72) + half button (~33) sitting above the bar.
                        contentPadding = PaddingValues(bottom = 112.dp)
                    ) {
                        pageSubscription?.takeIf { it.showInfo }?.let { sub ->
                            item(key = "subscription") {
                                SubscriptionCard(
                                    subscription = sub,
                                    nowMs = nowMs,
                                    onRefresh = { onRefreshSubscription(sub.id) },
                                    refreshing = refreshingSubscriptionId == sub.id
                                )
                            }
                        }
                        // Only reachable with a subscription on this page — the profiles-empty
                        // Home folder took the branch above. "Здесь", not "У вас": the user does
                        // have configurations, this folder just came back without any.
                        if (pageDisplayed.isEmpty()) {
                            item(key = "empty") {
                                // Match LazyColumn viewport: tall enough that Center lands mid-list
                                // area above the floating connect bar.
                                Box(
                                    Modifier
                                        .fillParentMaxSize()
                                        .padding(horizontal = 32.dp)
                                        .padding(bottom = 112.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = t(S.NO_PROFILES_IN_FOLDER_HINT),
                                        color = SmuglyTextSecondary,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    when (row) {
                        // A category is ONE list item: its text and its cards move, fold and clip
                        // together. Drawing the heading as its own item is what let a card slide
                        // out over the next group's text while the list closed the gap.
                        is FolderRow.Block -> CategorySection(
                            name = row.category.name,
                            description = row.category.description,
                            collapsed = row.collapsed,
                            onToggle = {
                                onCollapsedCategoriesChange(
                                    if (row.collapsed) {
                                        collapsedCategories - row.toggleKey
                                    } else {
                                        collapsedCategories + row.toggleKey
                                    }
                                )
                            }
                            // Deliberately no `animateItem`: the panel above is already animating
                            // its own height while it folds, and animating the placement on top of
                            // that made the next panel chase a target that moved every frame — it
                            // fell behind, opening a gap the width of the whole group, then caught
                            // up in a jump. Following the layout directly keeps the groups welded
                            // together as one closes.
                        ) {
                            row.members.forEach { member ->
                                serverCard(member.profile, member.index, member.key, true, Modifier)
                            }
                        }
                        is FolderRow.Server -> serverCard(
                            row.profile,
                            row.index,
                            row.key,
                            false,
                            // Neighbours ease into their new slot as the held card crosses them.
                            // Never animate the carried row — its Y is the finger's, and a
                            // placement animation would fight dragOffsetY.
                            Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = if (row.profile.id == draggingId) {
                                    null
                                } else {
                                    tween(180, easing = FastOutSlowInEasing)
                                }
                            )
                        )
                    }
                }
                    }
                    }
                }
            }
        }

        BottomConnectBar(
            status = connect.statusText.ifBlank { t(S.STATUS_NOT_CONNECTED) },
            traffic = connect.trafficText,
            running = connect.running,
            loading = connect.connecting,
            onToggle = onToggle,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // [folderDraft] holds the content and is never cleared on dismiss — clearing it dropped the
        // card out of composition on the same frame, so its exit animation never got to run and
        // the dialog just vanished. Only [folderEditorOpen] flips, like the delete dialog.
        folderDraft?.let { draft ->
            FolderEditorDialog(
                visible = folderEditorOpen,
                draft = draft,
                onDismiss = { folderEditorOpen = false },
                onSave = {
                    onSaveFolder(it)
                    folderEditorOpen = false
                }
            )
        }
        ConfirmDialog(
            visible = deletingFolder != null,
            title = t(S.DELETE_FOLDER_TITLE),
            message = t(S.DELETE_FOLDER_MESSAGE),
            confirmLabel = t(S.DELETE_BTN),
            cancelLabel = t(S.CANCEL_BTN),
            onConfirm = {
                deletingFolder?.let { onDeleteSubscription(it.id) }
                deletingFolder = null
            },
            onDismiss = { deletingFolder = null }
        )

        // Menus last so they paint over the list and the bottom bar.
        MenuLayer(
            visible = addMenu,
            anchorY = menuAnchorY,
            onDismiss = { addMenu = false }
        ) {
            MenuRow(t(S.MENU_NEW_PROFILE)) { addMenu = false; onAddNew() }
            MenuRow(t(S.MENU_NEW_FOLDER)) {
                addMenu = false
                folderDraft = FolderDraft()
                folderEditorOpen = true
            }

            // Both of these take a single config *or* a subscription link — they used to have
            // separate "import subscription" twins that did the exact same thing.
            MenuRow(t(S.MENU_IMPORT_CLIPBOARD)) { addMenu = false; onImportClipboard() }
            MenuRow(t(S.MENU_IMPORT_FILE)) { addMenu = false; onImportFile() }
        }
        // [menuProfile] keeps the rows populated while the panel fades out; only
        // [moreFor] is cleared on dismiss.
        menuProfile?.let { p ->
            MenuLayer(
                visible = moreFor?.id == p.id,
                anchorY = menuAnchorY,
                onDismiss = { moreFor = null }
            ) {
                MenuRow(t(S.CD_EDIT_PROFILE)) { moreFor = null; onEdit(p) }
                MenuRow(t(S.MENU_MEASURE_LATENCY)) { moreFor = null; onMeasureLatency(p) }
                MenuRow(t(S.MENU_EXPORT_PROFILE)) { moreFor = null; onExport(p) }
            }
        }
        // Folder management: the same dropdown as + / ⋮, dropped under the tab that was
        // long-pressed. Rename and delete still open their own dialogs from here.
        if (folderMenuSlot >= 0) {
            val menuSub = folderMenu
            // Profiles of the folder the menu was opened on — not of the folder currently on
            // screen: the two differ the moment you long-press a tab you are not looking at.
            val menuProfiles = profiles.filter { it.subscriptionId == menuSub?.id }
            val menuFolderName = menuSub?.name?.ifBlank { menuSub.url } ?: t(S.HOME_FOLDER)
            MenuLayer(
                visible = folderMenuOpen,
                anchorY = menuAnchorY,
                anchorX = menuAnchorX,
                onDismiss = { folderMenuOpen = false }
            ) {
                if (menuSub != null) {
                    MenuRow(t(S.EDIT_FOLDER)) {
                        folderMenuOpen = false
                        folderDraft = FolderDraft.of(menuSub)
                        folderEditorOpen = true
                    }
                }
                MenuRow(t(S.MENU_MEASURE_LATENCY)) {
                    folderMenuOpen = false
                    menuProfiles.forEach { onMeasureLatency(it) }
                }
                MenuRow(t(S.MENU_EXPORT_FOLDER)) {
                    folderMenuOpen = false
                    onExportFolder(menuProfiles, menuFolderName)
                }
                if (menuSub != null) {
                    MenuRow(t(S.SUBSCRIPTION_DELETE)) {
                        folderMenuOpen = false
                        deletingFolder = menuSub
                    }
                }
            }
        }
    }
}

/**
 * One row of a folder's list. A folder whose subscription publishes no categories is nothing but
 * [FolderRow.Server]s, which is exactly the flat list this screen has always drawn.
 */
private sealed interface FolderRow {
    /** Lazy-list key; unique within the folder. */
    val key: String

    /**
     * A whole category — heading, description and servers — as a single item.
     *
     * Its servers are composed together rather than lazily. That is the point: they are one panel
     * that folds and moves as a unit, and a category is a handful of servers an operator grouped
     * on purpose. The unbounded case (a folder with hundreds of servers) has no categories and
     * still comes through as individual [Server] rows.
     */
    data class Block(
        val category: SubscriptionCategory,
        val members: List<FolderMember>,
        val collapsed: Boolean,
        /** Identifies the group across folders, so folding one cannot fold another's namesake. */
        val toggleKey: String,
        override val key: String
    ) : FolderRow

    data class Server(
        val profile: ConfigProfile,
        /** Position in the folder's profile list — what the reorder drag counts in. */
        val index: Int,
        override val key: String
    ) : FolderRow
}

/** A server inside a category panel: same three facts a [FolderRow.Server] carries. */
private data class FolderMember(
    val index: Int,
    val key: String,
    val profile: ConfigProfile
)

/**
 * One profile card, wherever it is drawn: loose in the list or inside a category panel.
 *
 * Everything the drag needs is passed in rather than reached for, so the same row works in a
 * panel — where dragging is off — without a second copy of the card.
 */
@Composable
private fun ServerRow(
    profile: ConfigProfile,
    index: Int,
    inCategory: Boolean,
    rowModifier: Modifier,
    activeId: String?,
    latency: app.smugly.ui.LatencyUi?,
    draggingId: String?,
    dragOffsetY: Float,
    reorderable: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    onMoreClick: () -> Unit,
    onMoreAnchor: (Int) -> Unit,
    onDragStart: (pointerRootY: Float) -> Unit,
    onDrag: (dy: Float, pointerRootY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val isDragging = profile.id == draggingId
    ProfileCard(
        name = profile.name.ifBlank { t(S.PROFILE_NAME_FALLBACK) },
        subtitle = profileSubtitle(profile),
        selected = profile.id == activeId,
        onClick = onSelect,
        latency = latency,
        modifier = rowModifier,
        // Inside a panel the card already sits on SmuglyCard; one shade up keeps the rows visible
        // instead of melting the whole group into one slab.
        containerColor = if (inCategory) SmuglyCardSoft else SmuglyCard,
        onDelete = onDelete,
        onMoreClick = onMoreClick,
        onMoreAnchor = onMoreAnchor,
        dragOffsetY = if (isDragging) dragOffsetY else 0f,
        isDragging = isDragging,
        enableReorder = reorderable,
        onLongPressDragStart = onDragStart,
        onLongPressDrag = { dy, pointerRootY ->
            if (draggingId == profile.id) onDrag(dy, pointerRootY)
        },
        onLongPressDragEnd = { if (draggingId == profile.id) onDragEnd() },
        onLongPressDragCancel = { if (draggingId == profile.id) onDragCancel() }
    )
}

/**
 * Lay a folder out: ungrouped servers first, then each category the panel declared with its
 * servers under it.
 *
 * Categories keep the panel's order, not the profiles' — the panel decided what to show first.
 * A declared category with no servers is skipped rather than drawn empty, and servers whose
 * category is not in the list (a group dropped between refreshes) fall back to the ungrouped run
 * instead of disappearing.
 */
private fun folderRows(
    profiles: List<ConfigProfile>,
    categories: List<SubscriptionCategory>,
    folderKey: String,
    collapsed: Set<String>
): List<FolderRow> {
    // Keys must survive a reorder: the card being dragged is rebuilt if its key changes, and
    // rebuilding it cancels the gesture. So the id alone — with a suffix only where an id actually
    // repeats, because a lazy list throws outright on a duplicate key and profile ids are storage
    // data we do not fully control (an older subscription refresh could mint two profiles sharing
    // one id). A corrupt list should look wrong, not take the screen down.
    val seen = mutableMapOf<String, Int>()
    val keys = profiles.map { p ->
        val n = (seen[p.id] ?: 0) + 1
        seen[p.id] = n
        if (n == 1) p.id else p.id + "#" + n
    }
    if (categories.isEmpty()) {
        return profiles.mapIndexed { i, p -> FolderRow.Server(p, i, keys[i]) }
    }
    val known = categories.map { it.id }.toSet()
    val rows = ArrayList<FolderRow>(profiles.size + categories.size)
    // No heading for these: naming them ("Other") would be inventing a group the panel never
    // published.
    profiles.forEachIndexed { i, p ->
        if (p.categoryId == null || p.categoryId !in known) rows.add(FolderRow.Server(p, i, keys[i]))
    }
    for (category in categories) {
        val members = profiles.indices
            .filter { profiles[it].categoryId == category.id }
            .map { FolderMember(it, keys[it], profiles[it]) }
        if (members.isEmpty()) continue
        val toggleKey = "$folderKey/${category.id}"
        rows.add(
            FolderRow.Block(
                category = category,
                members = members,
                collapsed = toggleKey in collapsed,
                toggleKey = toggleKey,
                key = "category:${category.id}"
            )
        )
    }
    return rows
}

@Composable
fun SettingsScreen(
    settings: GlobalSettings,
    supportsVpn: Boolean,
    /** Android status-bar traffic notification; hidden on desktop/iOS. */
    showTrafficNotification: Boolean = false,
    /** Local proxy username/password; hidden where the platform cannot use it (desktop). */
    showLocalSocksAuth: Boolean = true,
    onMenu: () -> Unit,
    onChange: (GlobalSettings) -> Unit
) {
    val languageOptions = listOf(
        t(S.LANGUAGE_SYSTEM) to AppLanguage.SYSTEM,
        "English" to AppLanguage.EN,
        "Русский" to AppLanguage.RU
    )
    val modeOptions = listOf(t(S.CONNECTION_MODE_PROXY), t(S.CONNECTION_MODE_VPN))
    val modeIndex = if (settings.mode == Config.Mode.VPN) 1 else 0
    val langIndex = languageOptions.indexOfFirst { it.second == settings.language }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize().background(SmuglyBg)) {
        TopBar(title = t(S.SETTINGS), onMenu = onMenu)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // Local SOCKS listen port — desktop & mobile proxy path.
            LabeledField(t(S.LOCAL_PORT)) {
                SmuglyTextField(
                    value = settings.listenPort.toString(),
                    onValueChange = {
                        onChange(settings.copy(listenPort = it.filter(Char::isDigit).toIntOrNull() ?: settings.listenPort))
                    },
                    number = true
                )
            }
            // VPN vs proxy only where system VPN exists (Android/iOS). Desktop is always proxy.
            if (supportsVpn) {
                LabeledField(t(S.CONNECTION_MODE)) {
                    PillSelector(
                        options = modeOptions,
                        selectedIndex = modeIndex,
                        onSelected = { idx ->
                            onChange(
                                settings.copy(
                                    mode = if (idx == 1) Config.Mode.VPN else Config.Mode.PROXY
                                )
                            )
                        }
                    )
                }
            }
            LabeledField(t(S.LANGUAGE)) {
                PillSelector(
                    options = languageOptions.map { it.first },
                    selectedIndex = langIndex,
                    onSelected = { idx ->
                        onChange(settings.copy(language = languageOptions[idx].second))
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            SmuglyCheckbox(settings.fileLogging, t(S.ENABLE_DEBUG_MODE)) {
                onChange(settings.copy(fileLogging = it))
            }
            // Phone status-bar traffic notification — not for desktop.
            if (showTrafficNotification) {
                SmuglyCheckbox(settings.trafficNotification, t(S.SHOW_TRAFFIC_NOTIFICATION)) {
                    onChange(settings.copy(trafficNotification = it))
                }
            }
            // Only the system-VPN platform can hand a proxy to other apps.
            if (supportsVpn) {
                SmuglyCheckbox(settings.appHttpProxy, t(S.APP_HTTP_PROXY)) {
                    onChange(settings.copy(appHttpProxy = it))
                }
            }
            // Local SOCKS auth only where the platform can actually use it — see
            // HostPlatform.supportsLocalProxyAuth().
            if (showLocalSocksAuth) {
                SmuglyCheckbox(settings.localSocksAuthEnabled, t(S.PROTECT_LOCAL_SOCKS)) {
                    onChange(settings.copy(localSocksAuthEnabled = it))
                }
                if (settings.localSocksAuthEnabled) {
                    Spacer(Modifier.height(4.dp))
                    LabeledField(t(S.SOCKS_USERNAME)) {
                        SmuglyTextField(settings.localSocksUsername, { onChange(settings.copy(localSocksUsername = it)) })
                    }
                    LabeledField(t(S.SOCKS_PASSWORD)) {
                        SmuglyTextField(
                            settings.localSocksPassword,
                            { onChange(settings.copy(localSocksPassword = it)) },
                            password = true
                        )
                    }
                }
            }
            LabeledField(t(S.DNS_RESOLVER_POOL)) {
                SmuglyTextField(
                    value = settings.dnsResolverPool,
                    onValueChange = { onChange(settings.copy(dnsResolverPool = it)) },
                    singleLine = false,
                    minLines = 5
                )
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    logText: String,
    onMenu: () -> Unit,
    onShareLog: () -> Unit,
    onCrashReport: () -> Unit,
    onRefreshLog: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(SmuglyBg)) {
        TopBar(title = t(S.DIAGNOSTICS), onMenu = onMenu)
        // Actions live on Diagnostics (as in the original Android View UI).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryButton(t(S.SHARE_LOG_BTN), onShareLog, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SecondaryButton(t(S.CRASH_REPORT_BTN), onCrashReport, Modifier.weight(1f))
        }
        // Full-width log — LazyColumn of lines (not one giant Text) so open stays smooth.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 12.dp)
                .background(SmuglyCard)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            val display = logText.ifBlank {
                if (app.smugly.Strings.current == app.smugly.AppLanguage.RU) {
                    "Лог пуст. Включите «Режим отладки» в Настройках и переподключитесь."
                } else {
                    "Log is empty. Enable debug mode in Settings and reconnect."
                }
            }
            // Cap line count for layout cost; keep the tail (most recent).
            val lines = remember(display) {
                val all = display.split('\n')
                if (all.size <= 400) all else listOf("…") + all.takeLast(399)
            }
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) {
                    listState.scrollToItem(lines.lastIndex.coerceAtLeast(0))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    count = lines.size,
                    key = { i -> i }
                ) { i ->
                    Text(
                        text = lines[i],
                        color = SmuglyTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private val DnsQueryTypeOptions = listOf(
    "TXT (16)" to 16,
    "NULL (10)" to 10,
    "HTTPS (65)" to 65
)

@Composable
fun ProfileEditorScreen(
    draft: EditorDraft,
    onBack: () -> Unit,
    onChange: (EditorDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onLocalDns: () -> Unit,
    /** Pretty-printer for a pasted Xray config; returns null when the text is not JSON. */
    formatXray: (String) -> String? = { null }
) {
    val c = draft.config
    val protocolOptions = listOf(
        t(S.PROTOCOL_SLIPSTREAM),
        t(S.PROTOCOL_S3FU),
        t(S.PROTOCOL_XRAY)
    )
    val protocolIndex = when (c.protocol) {
        Config.TunnelProtocol.SLIPSTREAM -> 0
        Config.TunnelProtocol.S3FU -> 1
        Config.TunnelProtocol.XRAY -> 2
    }
    // Protocol list uses the same overlay as the + menu — never expands layout / pushes fields.
    var protocolMenuOpen by remember { mutableStateOf(false) }
    var protocolAnchorX by remember { mutableStateOf(0) }
    var protocolAnchorY by remember { mutableStateOf(0) }
    var protocolFieldWidth by remember { mutableStateOf(0) }

    PlatformBackHandler(enabled = protocolMenuOpen) { protocolMenuOpen = false }

    val isXray = c.protocol == Config.TunnelProtocol.XRAY

    Box(Modifier.fillMaxSize().background(SmuglyBg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = if (draft.profileId == null) t(S.NEW_PROFILE_TITLE) else t(S.EDIT_PROFILE_TITLE),
                onBack = onBack,
                // Save and delete live in the bar, where the + sits on the list screen — the form
                // is a scrolling page and a button pinned under it was a second thing to reach.
                onConfirm = onSave,
                onDeleteAction = onDelete
            )
            // Xray: name/protocol stay fixed; JSON fills remaining height and scrolls only inside
            // the field. Nested verticalScroll+focus bring-into-view used to yank the page to top
            // when the user scrolled the form then tapped the JSON box.
            // Other protocols: short form in a single page scroll.
            if (isXray) {
                Column(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp)
                ) {
                    LabeledField(t(S.PROFILE_NAME)) {
                        ProfileNameField(
                            name = draft.name,
                            onNameChange = { onChange(draft.copy(name = it)) }
                        )
                    }
                    LabeledField(t(S.PROTOCOL)) {
                        DropdownField(
                            label = protocolOptions[protocolIndex],
                            open = protocolMenuOpen,
                            onClick = { protocolMenuOpen = !protocolMenuOpen },
                            onAnchor = { x, y, w ->
                                protocolAnchorX = x
                                protocolAnchorY = y
                                protocolFieldWidth = w
                            }
                        )
                    }
                }
                XrayEditor(
                    c = c,
                    onChange = { onChange(draft.copy(config = it)) },
                    formatJson = formatXray,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 8.dp)
                )
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp)
                ) {
                    LabeledField(t(S.PROFILE_NAME)) {
                        ProfileNameField(
                            name = draft.name,
                            onNameChange = { onChange(draft.copy(name = it)) }
                        )
                    }
                    LabeledField(t(S.PROTOCOL)) {
                        DropdownField(
                            label = protocolOptions[protocolIndex],
                            open = protocolMenuOpen,
                            onClick = { protocolMenuOpen = !protocolMenuOpen },
                            onAnchor = { x, y, w ->
                                protocolAnchorX = x
                                protocolAnchorY = y
                                protocolFieldWidth = w
                            }
                        )
                    }
                    when (c.protocol) {
                        Config.TunnelProtocol.SLIPSTREAM -> SlipstreamEditor(
                            c = c,
                            onChange = { onChange(draft.copy(config = it)) },
                            onLocalDns = onLocalDns
                        )
                        Config.TunnelProtocol.S3FU -> S3fuEditor(c) {
                            onChange(draft.copy(config = it))
                        }
                        Config.TunnelProtocol.XRAY -> { /* handled above */ }
                    }
                }
            }
        }

        MenuLayer(
            visible = protocolMenuOpen,
            anchorY = protocolAnchorY,
            anchorX = protocolAnchorX,
            panelWidthPx = protocolFieldWidth,
            onDismiss = { protocolMenuOpen = false }
        ) {
            protocolOptions.forEachIndexed { idx, label ->
                MenuRow(label) {
                    protocolMenuOpen = false
                    val p = when (idx) {
                        1 -> Config.TunnelProtocol.S3FU
                        2 -> Config.TunnelProtocol.XRAY
                        else -> Config.TunnelProtocol.SLIPSTREAM
                    }
                    onChange(draft.copy(config = c.copy(protocol = p)))
                }
            }
        }
    }
}

@Composable
private fun SlipstreamEditor(
    c: Config,
    onChange: (Config) -> Unit,
    onLocalDns: () -> Unit
) {
    LabeledField(t(S.DOMAIN)) {
        SmuglyTextField(c.domain, { onChange(c.copy(domain = it)) }, hint = "domain")
    }
    SectionTitle(t(S.DNS_RESOLVER))
    LabeledField(t(S.DNS_MODE)) {
        PillSelector(
            listOf(t(S.DNS_MODE_MANUAL), t(S.DNS_MODE_AUTO)),
            if (c.resolverMode == Config.ResolverMode.AUTO) 1 else 0
        ) { idx ->
            onChange(
                c.copy(
                    resolverMode = if (idx == 1) Config.ResolverMode.AUTO else Config.ResolverMode.MANUAL
                )
            )
        }
    }
    // Manual DNS: host field + inline LOCAL (same row as Android Views).
    if (c.resolverMode == Config.ResolverMode.MANUAL) {
        LabeledField(t(S.RESOLVER_HOST)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SmuglyTextField(
                    value = c.resolverHost,
                    onValueChange = { onChange(c.copy(resolverHost = it)) },
                    hint = "1.2.3.4 or 1.2.3.4, 5.6.7.8",
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                AccentLinkButton(t(S.LOCAL_BTN), onLocalDns)
            }
        }
    }
    LabeledField(t(S.RESOLVER_PORT)) {
        SmuglyTextField(
            c.resolverPort.toString(),
            { onChange(c.copy(resolverPort = it.filter(Char::isDigit).toIntOrNull() ?: c.resolverPort)) },
            number = true
        )
    }
    LabeledField(t(S.TRANSPORT)) {
        PillSelector(
            listOf("UDP", "TCP"),
            if (c.resolverTransport == Config.ResolverTransport.TCP) 1 else 0
        ) { idx ->
            onChange(
                c.copy(
                    resolverTransport = if (idx == 1) Config.ResolverTransport.TCP else Config.ResolverTransport.UDP
                )
            )
        }
    }
    val qTypeIdx = DnsQueryTypeOptions.indexOfFirst { it.second == c.dnsQueryType }.coerceAtLeast(0)
    LabeledField(t(S.DNS_QUERY_TYPE)) {
        PillSelector(DnsQueryTypeOptions.map { it.first }, qTypeIdx) { idx ->
            onChange(c.copy(dnsQueryType = DnsQueryTypeOptions[idx].second))
        }
    }
    HintText(t(S.HINT_DNS_QUERY_TYPE))
    LabeledField(t(S.DNS_PATH_MODE)) {
        PillSelector(
            listOf(t(S.PATH_MODE_RECURSIVE), t(S.PATH_MODE_AUTHORITATIVE)),
            if (c.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE) 1 else 0
        ) { idx ->
            onChange(
                c.copy(
                    resolverPathMode = if (idx == 1) {
                        Config.ResolverPathMode.AUTHORITATIVE
                    } else {
                        Config.ResolverPathMode.RECURSIVE
                    }
                )
            )
        }
    }
    SectionTitle(t(S.AUTHENTICATION))
    LabeledField(t(S.AUTH_MODE)) {
        PillSelector(
            listOf(t(S.AUTH_NO_AUTH), t(S.AUTH_LOGIN_PASSWORD)),
            if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) 1 else 0
        ) { idx ->
            onChange(
                c.copy(
                    authMode = if (idx == 1) Config.AuthMode.LOGIN_PASSWORD else Config.AuthMode.NO_AUTH
                )
            )
        }
    }
    if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) {
        LabeledField(t(S.USERNAME)) {
            SmuglyTextField(c.username, { onChange(c.copy(username = it)) })
        }
        LabeledField(t(S.PASSWORD)) {
            SmuglyTextField(c.password, { onChange(c.copy(password = it)) }, password = true)
        }
    }
    SectionTitle(t(S.ADVANCED_CLIENT_ONLY))
    HintText(t(S.HINT_ADVANCED_CLIENT_ONLY))
    LabeledField(t(S.DNS_LABEL_LENGTH)) {
        SmuglyTextField(
            c.dnsLabelLength.toString(),
            { onChange(c.copy(dnsLabelLength = it.filter(Char::isDigit).toIntOrNull() ?: c.dnsLabelLength)) },
            number = true
        )
    }
    HintText(t(S.HINT_DNS_LABEL_LENGTH))
    LabeledField(t(S.DNS_LABEL_LENGTH_JITTER)) {
        SmuglyTextField(
            c.dnsLabelLengthJitter.toString(),
            {
                onChange(
                    c.copy(dnsLabelLengthJitter = it.filter(Char::isDigit).toIntOrNull() ?: c.dnsLabelLengthJitter)
                )
            },
            number = true
        )
    }
    HintText(t(S.HINT_DNS_LABEL_LENGTH_JITTER))
    LabeledField(t(S.MAX_POLL_RATE)) {
        SmuglyTextField(
            c.maxPollQps.toString(),
            { onChange(c.copy(maxPollQps = it.filter(Char::isDigit).toIntOrNull() ?: c.maxPollQps)) },
            number = true
        )
    }
    HintText(t(S.HINT_MAX_POLL_QPS))
    LabeledField(t(S.MAX_DATA_RATE)) {
        SmuglyTextField(
            c.maxDataQps.toString(),
            { onChange(c.copy(maxDataQps = it.filter(Char::isDigit).toIntOrNull() ?: c.maxDataQps)) },
            number = true
        )
    }
    HintText(t(S.HINT_MAX_DATA_QPS))
    LabeledField(t(S.MAX_ACTIVE_CONNECTIONS)) {
        SmuglyTextField(
            c.maxActiveClients.toString(),
            {
                onChange(
                    c.copy(maxActiveClients = it.filter(Char::isDigit).toIntOrNull() ?: c.maxActiveClients)
                )
            },
            number = true
        )
    }
    HintText(t(S.HINT_MAX_ACTIVE_CLIENTS))
    SmuglyCheckbox(c.base64uEncoding, t(S.USE_BASE64U_ENCODING)) {
        onChange(c.copy(base64uEncoding = it))
    }
    HintText(t(S.HINT_BASE64U))
}

@Composable
private fun S3fuEditor(c: Config, onChange: (Config) -> Unit) {
    // No "S3 (s3-fuckup)" section header — protocol pill already names the mode.
    LabeledField(t(S.S3_ENDPOINT)) {
        SmuglyTextField(c.s3Endpoint, { onChange(c.copy(s3Endpoint = it)) }, hint = "https://…")
    }
    LabeledField(t(S.S3_BUCKET)) {
        SmuglyTextField(c.s3Bucket, { onChange(c.copy(s3Bucket = it)) }, hint = t(S.S3_BUCKET_HINT))
    }
    LabeledField(t(S.S3_ACCESS_KEY)) {
        SmuglyTextField(c.s3AccessKey, { onChange(c.copy(s3AccessKey = it)) })
    }
    LabeledField(t(S.S3_SECRET_KEY)) {
        SmuglyTextField(c.s3SecretKey, { onChange(c.copy(s3SecretKey = it)) }, password = true)
    }
    LabeledField(t(S.S3_PREFIX)) {
        SmuglyTextField(c.s3Prefix, { onChange(c.copy(s3Prefix = it)) }, hint = t(S.S3_PREFIX_HINT))
    }
    LabeledField(t(S.S3_PSK)) {
        SmuglyTextField(c.s3Psk, { onChange(c.copy(s3Psk = it)) }, hint = t(S.S3_PSK_HINT))
    }
}

/**
 * Scrollables honour this for "bring focused child into view". Returning 0 never moves the
 * viewport — required for the Xray JSON box: wheel-scroll leaves the caret at 0, then the first
 * focus runs bring-into-view for offset 0 and yanks the scroller to the top.
 */
private val NoOpBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float = 0f
}

private val NoOpBringIntoViewResponder = object : BringIntoViewResponder {
    override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
    override suspend fun bringChildIntoView(localRect: () -> Rect?) {
        // Swallow the request entirely — do not scroll on focus.
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XrayEditor(
    c: Config,
    onChange: (Config) -> Unit,
    /** Pretty-printer; returns null when the text is not JSON. */
    formatJson: (String) -> String?,
    modifier: Modifier = Modifier
) {
    var field by remember {
        mutableStateOf(TextFieldValue(c.xrayConfigJson, TextRange(0)))
    }
    LaunchedEffect(c.xrayConfigJson) {
        if (c.xrayConfigJson != field.text) {
            val sel = field.selection
            val max = c.xrayConfigJson.length
            field = TextFieldValue(
                c.xrayConfigJson,
                TextRange(sel.start.coerceIn(0, max), sel.end.coerceIn(0, max))
            )
        }
    }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(10.dp)

    // Disable focus-driven auto-scroll for everything inside this editor.
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoOpBringIntoViewSpec) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(SmuglyInput)
                .border(1.dp, SmuglyStroke, shape)
                .bringIntoViewResponder(NoOpBringIntoViewResponder)
        ) {
            // Outer scroll of full text height (not BTF internal scroller). Auto bring-into-view
            // is disabled above so wheel position is preserved on first focus/click.
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { next ->
                        val pasted = next.text.length - field.text.length > 1
                        val text = if (pasted) formatJson(next.text) ?: next.text else next.text
                        field = if (text != next.text) {
                            TextFieldValue(text, TextRange(text.length))
                        } else {
                            next
                        }
                        onChange(c.copy(xrayConfigJson = field.text))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewResponder(NoOpBringIntoViewResponder),
                    textStyle = TextStyle(
                        color = SmuglyTextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(SmuglyAccent),
                    visualTransformation = JsonSyntaxHighlightTransformation,
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (field.text.isEmpty()) {
                                Text("{}", color = SmuglyTextMuted, fontSize = 12.sp)
                            }
                            inner()
                        }
                    }
                )
            }
        }
    }
}

/**
 * How fast the list creeps while a dragged card is held at an edge, in px per frame.
 * Depth 0 = just entered the zone (crawl); 1 = finger hard against / past the edge (fast).
 */
private const val AUTO_SCROLL_MIN_PX = 6f
private const val AUTO_SCROLL_MAX_PX = 56f

/** [depth] is 0 at the inner edge of the zone and 1 at the outer edge (or beyond). */
private fun speedFor(depth: Float): Float {
    val d = depth.coerceIn(0f, 1f)
    // Mostly linear so mid-zone is clearly faster than the rim; light ease so it does not kick.
    val t = d * (0.45f + 0.55f * d)
    return AUTO_SCROLL_MIN_PX + (AUTO_SCROLL_MAX_PX - AUTO_SCROLL_MIN_PX) * t
}
