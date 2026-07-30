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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
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
import app.smugly.ui.PlatformBackHandler
import app.smugly.ui.rememberTickHaptic
import app.smugly.ui.components.AccentLinkButton
import app.smugly.ui.components.BottomConnectBar
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
    /** Create ([FolderDraft.id] null) or update a folder. */
    onSaveFolder: (FolderDraft) -> Unit = {},
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
    var folderMenuOpen by remember { mutableStateOf(false) }
    /** Folder being created or edited; kept while the dialog animates out. */
    var folderDraft by remember { mutableStateOf<FolderDraft?>(null) }
    var folderEditorOpen by remember { mutableStateOf(false) }
    var deletingFolder by remember { mutableStateOf<Subscription?>(null) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { subscriptions.size + 1 })
    val folderIndex = pagerState.currentPage
    /** Tab the user just tapped, held until the pager finishes travelling to it. */
    var pendingTab by remember { mutableStateOf<Int?>(null) }
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
        deletingFolder != null
    ) {
        onBlockDrawerGestures(
            folderEditorOpen ||
                folderMenuOpen ||
                addMenu ||
                moreFor != null ||
                deletingFolder != null
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
    val density = LocalDensity.current
    val tick = rememberTickHaptic()
    // Card height (~56 content) + bottom padding 8 ≈ slot pitch used for gap math.
    val slotPitchPx = with(density) { 72.dp.toPx() }

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
                selectedIndex = pendingTab ?: folderIndex,
                onSelect = { target ->
                    pendingTab = target
                    scope.launch {
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
                        pendingTab = null
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
                    scope.launch {
                        pagerState.scrollToPage(openNow)
                        pendingTab = null
                    }
                },
                onMenu = { index, x, y ->
                    // The Home slot has nothing to manage.
                    slots.getOrNull(index)?.let { sub ->
                        menuAnchorY = y
                        menuAnchorX = x
                        addMenu = false
                        moreFor = null
                        folderMenu = sub
                        folderMenuOpen = true
                    }
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        state = rememberLazyListState(),
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
                // The index is in the key on purpose. A lazy list throws outright on a repeated
                // key, and profile ids are storage data we do not fully control — an older
                // subscription refresh could mint two profiles sharing one id. A corrupt list
                // should look wrong, not take the whole screen down.
                itemsIndexed(pageDisplayed, key = { index, p -> "${p.id}:$index" }) { index, profile ->
                    val isDragging = profile.id == draggingId
                    val gapOffset = when {
                        draggingId == null || isDragging || dragFromIndex < 0 || gapTargetIndex < 0 -> 0f
                        // Dragging down: rows between start and target shift up.
                        gapTargetIndex > dragFromIndex &&
                            index in (dragFromIndex + 1)..gapTargetIndex -> -slotPitchPx
                        // Dragging up: rows between target and start shift down.
                        gapTargetIndex < dragFromIndex &&
                            index in gapTargetIndex until dragFromIndex -> slotPitchPx
                        else -> 0f
                    }
                    ProfileCard(
                        name = profile.name.ifBlank { t(S.PROFILE_NAME_FALLBACK) },
                        subtitle = profileSubtitle(profile),
                        selected = profile.id == activeId,
                        onClick = { if (draggingId == null) onSelect(profile) },
                        // Servers in a subscription folder are replaced wholesale on refresh, so
                        // deleting one individually would just come back — hide the button.
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
                        dragOffsetY = if (isDragging) dragOffsetY else 0f,
                        isDragging = isDragging,
                        gapOffsetY = gapOffset,
                        // A subscription group is replaced wholesale on refresh, so a hand-made
                        // order only survives if the folder is set to keep one.
                        enableReorder = pageDisplayed.size > 1 &&
                            (pageSubscription == null || pageSubscription.allowReorder),
                        onLongPressDragStart = {
                            moreFor = null
                            draggingId = profile.id
                            dragFromIndex = index
                            gapTargetIndex = index
                            dragOffsetY = 0f
                        },
                        onLongPressDrag = { dy ->
                            if (draggingId != profile.id) return@ProfileCard
                            dragOffsetY += dy
                            val shiftSlots = (dragOffsetY / slotPitchPx).toInt()
                            val target = (dragFromIndex + shiftSlots).coerceIn(0, pageDisplayed.lastIndex)
                            if (target != gapTargetIndex) {
                                gapTargetIndex = target
                                // A neighbour just moved out of the way — one light tick per slot
                                // crossed, so the new position is felt without watching the list.
                                tick()
                            }
                        },
                        onLongPressDragEnd = {
                            if (draggingId != profile.id) return@ProfileCard
                            val from = dragFromIndex
                            val to = gapTargetIndex
                            scope.launch {
                                // Ride the card down into its slot rather than teleporting it
                                // there: clearing the offset in one frame read as a snap-back.
                                Animatable(dragOffsetY).animateTo(
                                    targetValue = if (from >= 0 && to >= 0) {
                                        (to - from) * slotPitchPx
                                    } else {
                                        0f
                                    },
                                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                                ) { dragOffsetY = value }
                                if (from >= 0 && to >= 0 && from != to && from in ordered.indices) {
                                    val next = ordered.toMutableList()
                                    val item = next.removeAt(from)
                                    next.add(to.coerceIn(0, next.size), item)
                                    ordered = next
                                    onReorder(next.map { it.id })
                                }
                                draggingId = null
                                dragFromIndex = -1
                                gapTargetIndex = -1
                                dragOffsetY = 0f
                            }
                        },
                        onLongPressDragCancel = {
                            draggingId = null
                            dragFromIndex = -1
                            gapTargetIndex = -1
                            dragOffsetY = 0f
                        }
                    )
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
                MenuRow(t(S.MENU_EXPORT_PROFILE)) { moreFor = null; onExport(p) }
            }
        }
        // Folder management: the same dropdown as + / ⋮, dropped under the tab that was
        // long-pressed. Rename and delete still open their own dialogs from here.
        folderMenu?.let { sub ->
            MenuLayer(
                visible = folderMenuOpen,
                anchorY = menuAnchorY,
                anchorX = menuAnchorX,
                onDismiss = { folderMenuOpen = false }
            ) {
                MenuRow(t(S.EDIT_FOLDER)) {
                    folderMenuOpen = false
                    folderDraft = FolderDraft.of(sub)
                    folderEditorOpen = true
                }
                MenuRow(t(S.SUBSCRIPTION_DELETE)) {
                    folderMenuOpen = false
                    deletingFolder = sub
                }
            }
        }
    }
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
            HintText(t(S.HINT_DNS_RESOLVER_POOL))
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
        t(S.PROTOCOL_XRAY),
        t(S.PROTOCOL_CDNFU)
    )
    val protocolIndex = when (c.protocol) {
        Config.TunnelProtocol.SLIPSTREAM -> 0
        Config.TunnelProtocol.S3FU -> 1
        Config.TunnelProtocol.XRAY -> 2
        Config.TunnelProtocol.CDNFU -> 3
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
                onBack = onBack
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
                if (onDelete != null) {
                    SecondaryButton(
                        t(S.DELETE_PROFILE_BTN),
                        onDelete,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .padding(bottom = 8.dp)
                    )
                }
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
                        Config.TunnelProtocol.CDNFU -> CdnfuEditor(c) {
                            onChange(draft.copy(config = it))
                        }
                    }
                    if (onDelete != null) {
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton(t(S.DELETE_PROFILE_BTN), onDelete, Modifier.fillMaxWidth())
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SmuglyCard)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                PrimaryButton(
                    text = if (draft.profileId == null) t(S.CREATE_PROFILE_BTN) else t(S.SAVE_PROFILE_BTN),
                    onClick = onSave
                )
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
                        3 -> Config.TunnelProtocol.CDNFU
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
    LabeledField(t(S.S3_LOGIN)) {
        SmuglyTextField(c.s3Login, { onChange(c.copy(s3Login = it)) })
    }
    LabeledField(t(S.S3_PSK)) {
        SmuglyTextField(c.s3Psk, { onChange(c.copy(s3Psk = it)) }, hint = t(S.S3_PSK_HINT))
    }
}

@Composable
private fun CdnfuEditor(c: Config, onChange: (Config) -> Unit) {
    val mimics = listOf("image", "video", "static", "mixed")
    val mimicIndex = mimics.indexOf(c.cdnMimic.ifBlank { "mixed" }).let { if (it < 0) 3 else it }
    LabeledField(t(S.CDN_URL)) {
        SmuglyTextField(c.cdnUrl, { onChange(c.copy(cdnUrl = it)) }, hint = "https://cdn-host/")
    }
    LabeledField(t(S.CDN_PSK)) {
        SmuglyTextField(c.cdnPsk, { onChange(c.copy(cdnPsk = it)) }, hint = t(S.CDN_PSK_HINT))
    }
    LabeledField(t(S.CDN_MIMIC)) {
        PillSelector(mimics, mimicIndex) { idx -> onChange(c.copy(cdnMimic = mimics[idx])) }
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
