package app.vaydns.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import app.vaydns.ui.components.AnimatedModalCard
import app.vaydns.ui.components.ConfirmDialog
import app.vaydns.ui.theme.SlipnetTextPrimary
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaydns.AppLanguage
import app.vaydns.Config
import app.vaydns.ConfigProfile
import app.vaydns.GlobalSettings
import app.vaydns.S
import app.vaydns.t
import app.vaydns.ui.ConnectUiState
import app.vaydns.ui.EditorDraft
import app.vaydns.subscription.Subscription
import app.vaydns.ui.PlatformBackHandler
import app.vaydns.ui.rememberTickHaptic
import app.vaydns.ui.components.AccentLinkButton
import app.vaydns.ui.components.BottomConnectBar
import app.vaydns.ui.components.HintText
import app.vaydns.ui.components.LabeledField
import app.vaydns.ui.components.MenuLayer
import app.vaydns.ui.components.MenuRow
import app.vaydns.ui.components.PillSelector
import app.vaydns.ui.components.PrimaryButton
import app.vaydns.ui.components.ProfileCard
import app.vaydns.ui.components.SecondaryButton
import app.vaydns.ui.components.SectionTitle
import app.vaydns.ui.components.SlipnetCheckbox
import app.vaydns.ui.components.FolderTabs
import app.vaydns.ui.components.SubscriptionCard
import app.vaydns.ui.components.SlipnetTextField
import app.vaydns.ui.components.TopBar
import app.vaydns.ui.profileSubtitle
import app.vaydns.ui.theme.SlipnetBg
import app.vaydns.ui.theme.SlipnetCard
import app.vaydns.ui.theme.SlipnetTextSecondary

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
    onRenameSubscription: (String, String) -> Unit = { _, _ -> }
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
    var renaming by remember { mutableStateOf<Subscription?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingFolder by remember { mutableStateOf<Subscription?>(null) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { subscriptions.size + 1 })
    val folderIndex = pagerState.currentPage
    val folderNames = remember(subscriptions) {
        listOf(t(S.HOME_FOLDER)) + subscriptions.map { it.name.ifBlank { it.url } }
    }
    val currentSubscription = subscriptions.getOrNull(folderIndex - 1)
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

    // The menus are plain overlays, not focusable Popup windows, so back has to be
    // handled here — otherwise it would fall through and close the app.
    PlatformBackHandler(enabled = addMenu || moreFor != null || folderMenuOpen) {
        addMenu = false
        moreFor = null
        folderMenuOpen = false
    }

    val nowMs = app.vaydns.platform.PlatformTime.currentTimeMillis()
    val density = LocalDensity.current
    val tick = rememberTickHaptic()
    // Card height (~56 content) + bottom padding 8 ≈ slot pitch used for gap math.
    val slotPitchPx = with(density) { 72.dp.toPx() }

    Box(Modifier.fillMaxSize().background(SlipnetBg)) {
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
                selectedIndex = folderIndex,
                onSelect = { target ->
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
                    }
                },
                onMenu = { index, x, y ->
                    // Folder 0 is "Home" and has nothing to manage.
                    subscriptions.getOrNull(index - 1)?.let { sub ->
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
                val pageSubscription = subscriptions.getOrNull(page - 1)
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
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = t(S.NO_PROFILES_HINT),
                            color = SlipnetTextSecondary,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center
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
                        // Space for bar (56) + half button (~33) sitting above the bar.
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        pageSubscription?.let { sub ->
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
                                Box(
                                    Modifier.fillMaxWidth().padding(top = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = t(S.NO_PROFILES_IN_FOLDER_HINT),
                                        color = SlipnetTextSecondary,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        textAlign = TextAlign.Center
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
                        enableReorder = pageSubscription == null && pageDisplayed.size > 1,
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

        renaming?.let { sub ->
            AnimatedModalCard(
                visible = true,
                onDismissRequest = { renaming = null },
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                Text(t(S.RENAME_FOLDER), color = SlipnetTextPrimary, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                SlipnetTextField(renameText, { renameText = it })
                Spacer(Modifier.height(12.dp))
                PrimaryButton(t(S.SAVE_BTN), {
                    onRenameSubscription(sub.id, renameText.trim())
                    renaming = null
                })
            }
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
                MenuRow(t(S.RENAME_FOLDER)) {
                    folderMenuOpen = false
                    renameText = sub.name
                    renaming = sub
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

    Column(Modifier.fillMaxSize().background(SlipnetBg)) {
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
                SlipnetTextField(
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
            SlipnetCheckbox(settings.fileLogging, t(S.ENABLE_DEBUG_MODE)) {
                onChange(settings.copy(fileLogging = it))
            }
            // Phone status-bar traffic notification — not for desktop.
            if (showTrafficNotification) {
                SlipnetCheckbox(settings.trafficNotification, t(S.SHOW_TRAFFIC_NOTIFICATION)) {
                    onChange(settings.copy(trafficNotification = it))
                }
            }
            // Local SOCKS auth only where the platform can actually use it — see
            // HostPlatform.supportsLocalProxyAuth().
            if (showLocalSocksAuth) {
                SlipnetCheckbox(settings.localSocksAuthEnabled, t(S.PROTECT_LOCAL_SOCKS)) {
                    onChange(settings.copy(localSocksAuthEnabled = it))
                }
                if (settings.localSocksAuthEnabled) {
                    Spacer(Modifier.height(4.dp))
                    LabeledField(t(S.SOCKS_USERNAME)) {
                        SlipnetTextField(settings.localSocksUsername, { onChange(settings.copy(localSocksUsername = it)) })
                    }
                    LabeledField(t(S.SOCKS_PASSWORD)) {
                        SlipnetTextField(
                            settings.localSocksPassword,
                            { onChange(settings.copy(localSocksPassword = it)) },
                            password = true
                        )
                    }
                }
            }
            LabeledField(t(S.DNS_RESOLVER_POOL)) {
                SlipnetTextField(
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
    Column(Modifier.fillMaxSize().background(SlipnetBg)) {
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
                .background(SlipnetCard)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            val display = logText.ifBlank {
                if (app.vaydns.Strings.current == app.vaydns.AppLanguage.RU) {
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
                        color = SlipnetTextSecondary,
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
    onFormatXray: () -> Unit,
    onValidateXray: () -> Unit
) {
    val c = draft.config
    val protocolIndex = when (c.protocol) {
        Config.TunnelProtocol.SLIPSTREAM -> 0
        Config.TunnelProtocol.S3FU -> 1
        Config.TunnelProtocol.XRAY -> 2
    }
    Column(Modifier.fillMaxSize().background(SlipnetBg)) {
        TopBar(
            title = if (draft.profileId == null) t(S.NEW_PROFILE_TITLE) else t(S.EDIT_PROFILE_TITLE),
            onBack = onBack
        )
        // Plain scrolling Column on purpose. A LazyColumn was measured here and came out
        // ~40ms slower per open: its per-item subcomposition costs more than composing
        // this many lightweight rows up front.
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .verticalScroll(rememberScrollState())
                // Button bar is outside scroll — only a small end gap, not 90dp home-bar space.
                .padding(bottom = 12.dp)
        ) {
            LabeledField(t(S.PROFILE_NAME)) {
                SlipnetTextField(draft.name, { onChange(draft.copy(name = it)) })
            }
            LabeledField(t(S.PROTOCOL)) {
                PillSelector(
                    listOf(t(S.PROTOCOL_SLIPSTREAM), t(S.PROTOCOL_S3FU), t(S.PROTOCOL_XRAY)),
                    protocolIndex
                ) { idx ->
                    val p = when (idx) {
                        1 -> Config.TunnelProtocol.S3FU
                        2 -> Config.TunnelProtocol.XRAY
                        else -> Config.TunnelProtocol.SLIPSTREAM
                    }
                    onChange(draft.copy(config = c.copy(protocol = p)))
                }
            }

            when (c.protocol) {
                Config.TunnelProtocol.SLIPSTREAM -> SlipstreamEditor(
                    c = c,
                    onChange = { onChange(draft.copy(config = it)) },
                    onLocalDns = onLocalDns
                )
                Config.TunnelProtocol.S3FU -> S3fuEditor(c) { onChange(draft.copy(config = it)) }
                Config.TunnelProtocol.XRAY -> XrayEditor(
                    c,
                    onChange = { onChange(draft.copy(config = it)) },
                    onFormat = onFormatXray,
                    onValidate = onValidateXray
                )
            }

            if (onDelete != null) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton(t(S.DELETE_PROFILE_BTN), onDelete, Modifier.fillMaxWidth())
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(SlipnetCard)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            PrimaryButton(
                text = if (draft.profileId == null) t(S.CREATE_PROFILE_BTN) else t(S.SAVE_PROFILE_BTN),
                onClick = onSave
            )
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
        SlipnetTextField(c.domain, { onChange(c.copy(domain = it)) }, hint = "domain")
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
                SlipnetTextField(
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
        SlipnetTextField(
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
            SlipnetTextField(c.username, { onChange(c.copy(username = it)) })
        }
        LabeledField(t(S.PASSWORD)) {
            SlipnetTextField(c.password, { onChange(c.copy(password = it)) }, password = true)
        }
    }
    SectionTitle(t(S.ADVANCED_CLIENT_ONLY))
    HintText(t(S.HINT_ADVANCED_CLIENT_ONLY))
    LabeledField(t(S.DNS_LABEL_LENGTH)) {
        SlipnetTextField(
            c.dnsLabelLength.toString(),
            { onChange(c.copy(dnsLabelLength = it.filter(Char::isDigit).toIntOrNull() ?: c.dnsLabelLength)) },
            number = true
        )
    }
    HintText(t(S.HINT_DNS_LABEL_LENGTH))
    LabeledField(t(S.DNS_LABEL_LENGTH_JITTER)) {
        SlipnetTextField(
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
        SlipnetTextField(
            c.maxPollQps.toString(),
            { onChange(c.copy(maxPollQps = it.filter(Char::isDigit).toIntOrNull() ?: c.maxPollQps)) },
            number = true
        )
    }
    HintText(t(S.HINT_MAX_POLL_QPS))
    LabeledField(t(S.MAX_DATA_RATE)) {
        SlipnetTextField(
            c.maxDataQps.toString(),
            { onChange(c.copy(maxDataQps = it.filter(Char::isDigit).toIntOrNull() ?: c.maxDataQps)) },
            number = true
        )
    }
    HintText(t(S.HINT_MAX_DATA_QPS))
    LabeledField(t(S.MAX_ACTIVE_CONNECTIONS)) {
        SlipnetTextField(
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
    SlipnetCheckbox(c.base64uEncoding, t(S.USE_BASE64U_ENCODING)) {
        onChange(c.copy(base64uEncoding = it))
    }
    HintText(t(S.HINT_BASE64U))
}

@Composable
private fun S3fuEditor(c: Config, onChange: (Config) -> Unit) {
    // No "S3 (s3-fuckup)" section header — protocol pill already names the mode.
    LabeledField(t(S.S3_ENDPOINT)) {
        SlipnetTextField(c.s3Endpoint, { onChange(c.copy(s3Endpoint = it)) }, hint = "https://…")
    }
    LabeledField(t(S.S3_BUCKET)) {
        SlipnetTextField(c.s3Bucket, { onChange(c.copy(s3Bucket = it)) }, hint = t(S.S3_BUCKET_HINT))
    }
    LabeledField(t(S.S3_ACCESS_KEY)) {
        SlipnetTextField(c.s3AccessKey, { onChange(c.copy(s3AccessKey = it)) })
    }
    LabeledField(t(S.S3_SECRET_KEY)) {
        SlipnetTextField(c.s3SecretKey, { onChange(c.copy(s3SecretKey = it)) }, password = true)
    }
    LabeledField(t(S.S3_PREFIX)) {
        SlipnetTextField(c.s3Prefix, { onChange(c.copy(s3Prefix = it)) }, hint = t(S.S3_PREFIX_HINT))
    }
    LabeledField(t(S.S3_LOGIN)) {
        SlipnetTextField(c.s3Login, { onChange(c.copy(s3Login = it)) }, hint = t(S.S3_LOGIN_HINT))
    }
    LabeledField(t(S.S3_PSK)) {
        SlipnetTextField(c.s3Psk, { onChange(c.copy(s3Psk = it)) }, hint = t(S.S3_PSK_HINT))
    }
}

@Composable
private fun XrayEditor(
    c: Config,
    onChange: (Config) -> Unit,
    onFormat: () -> Unit,
    onValidate: () -> Unit
) {
    // Only Format/Validate + bare JSON field — no "Xray configuration" / hint labels.
    Row(Modifier.fillMaxWidth()) {
        SecondaryButton(t(S.XRAY_FORMAT_BTN), onFormat, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SecondaryButton(t(S.XRAY_VALIDATE_BTN), onValidate, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    SlipnetTextField(
        value = c.xrayConfigJson,
        onValueChange = { onChange(c.copy(xrayConfigJson = it)) },
        singleLine = false,
        minLines = 16,
        monospace = true,
        hint = ""
    )
}
