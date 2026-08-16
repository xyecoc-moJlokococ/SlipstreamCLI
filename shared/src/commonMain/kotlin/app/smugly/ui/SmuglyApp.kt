package app.smugly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.S
import app.smugly.Strings
import app.smugly.currentHostPlatform
import app.smugly.defaultConfig
import app.smugly.supportsLocalProxyAuth
import app.smugly.supportsTrafficNotification
import app.smugly.t
import app.smugly.ui.components.AppDrawerItem
import app.smugly.ui.components.AppToast
import app.smugly.ui.components.ConfirmDialog
import app.smugly.ui.components.CrashReportDialog
import app.smugly.ui.components.DrawerPanel
import app.smugly.ui.components.LoadingOverlay
import app.smugly.ui.screens.DiagnosticsScreen
import app.smugly.ui.screens.HomeScreen
import app.smugly.ui.screens.ProfileEditorScreen
import app.smugly.ui.screens.SettingsScreen
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Marker id used while adding a subscription that does not have an id yet. */
private const val PENDING_SUBSCRIPTION = "pending"

/**
 * Shared multiplatform UI root — same screens/palette as the Android View UI,
 * including horizontal page transitions and drawer content-shift.
 */
@Composable
fun SmuglyApp(platform: SmuglyPlatform, shortcuts: AppShortcuts? = null) {
    SmuglyTheme {
        // Alias keeps call sites short; toast is observed below (desktop snackbar).
        val ui = platform
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current

        fun dismissKeyboard() {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        /** Toast text kept while fading out so content doesn't blank mid-exit. */
        var toastText by remember { mutableStateOf("") }
        var toastVisible by remember { mutableStateOf(false) }
        /** Pending profile delete payload (kept during exit anim). */
        var pendingDelete by remember { mutableStateOf<ConfigProfile?>(null) }
        var deleteDialogVisible by remember { mutableStateOf(false) }
        /** Crash report body (kept during exit anim). */
        var crashDialogBody by remember { mutableStateOf("") }
        var crashDialogVisible by remember { mutableStateOf(false) }
        /** The visible main tab. The profile editor is a separate layer on top of it. */
        var screen by remember { mutableStateOf(AppScreen.HOME) }
        /**
         * Drawer open fraction 0..1 (driven by Animatable for drag + snap).
         * Boolean flag for "intended open" so hamburger / swipe settle correctly.
         */
        var drawerOpen by remember { mutableStateOf(false) }
        val drawerProgress = remember { Animatable(0f) }
        /**
         * Whether a swipe may open the drawer at all. Home clears it past the first folder, where
         * the same gesture means "back one folder"; every other screen leaves it on.
         */
        var swipeOpensDrawer by remember { mutableStateOf(true) }
        /**
         * Home (and similar) sets this while a modal / dropdown is up so the full-screen
         * drawer-swipe cannot steal text selection or long-presses inside dialogs.
         */
        var blockDrawerGestures by remember { mutableStateOf(false) }
        var profiles by remember { mutableStateOf(ui.loadProfiles()) }
        var subscriptions by remember { mutableStateOf(ui.loadSubscriptions()) }
        /** Id of the subscription being fetched, or null when idle — drives the spinner. */
        var refreshingSubscriptionId by remember { mutableStateOf<String?>(null) }
        /** Jump the folder pager to this id once after a create; HomeScreen clears it. */
        var focusFolderId by remember { mutableStateOf<String?>(null) }
        /** Incremented after a local profile is imported or created so Home can come into view. */
        var focusHomeEpoch by remember { mutableStateOf(0) }
        var activeId by remember {
            mutableStateOf(ui.loadActiveProfileId() ?: profiles.firstOrNull()?.id)
        }
        var settings by remember {
            // One read: loadGlobalSettings() hits disk.
            val loaded = ui.loadGlobalSettings()
            Strings.set(loaded.language)
            mutableStateOf(loaded)
        }
        var connect by remember { mutableStateOf(ConnectUiState.idle()) }
        /** Latency chips, keyed by profile id. */
        var latencies by remember { mutableStateOf(mapOf<String, LatencyUi>()) }
        /** Non-null while the profile editor is on screen (or sliding out). */
        var editor by remember { mutableStateOf<EditorDraft?>(null) }
        /** 0 = editor fully on-screen, 1 = parked off to the right. */
        val editorSlide = remember { Animatable(1f) }
        var uiTick by remember { mutableStateOf(0) }

        DisposableEffect(ui) {
            val stopConnect = ui.observeConnect { connect = it }
            val stopToast = ui.observeToast { msg ->
                toastText = msg
                toastVisible = true
            }
            onDispose {
                stopConnect()
                stopToast()
            }
        }

        // Auto-hide toast after a short hold; exit is a fade (instant appear).
        LaunchedEffect(toastText, toastVisible) {
            if (!toastVisible || toastText.isEmpty()) return@LaunchedEffect
            delay(2000)
            // Only hide if this is still the same toast.
            if (toastVisible) toastVisible = false
        }

        fun reloadProfiles() {
            profiles = ui.loadProfiles()
            subscriptions = ui.loadSubscriptions()
            activeId = ui.loadActiveProfileId() ?: profiles.firstOrNull()?.id
            // First import may seed default-open fold state (and refresh can prune gone keys);
            // pull just that field so in-memory toggles of other settings stay put.
            val disk = ui.loadGlobalSettings()
            if (disk.collapsedCategories != settings.collapsedCategories) {
                settings = settings.copy(collapsedCategories = disk.collapsedCategories)
            }
        }

        // Someone wrote to storage from outside the composition — an `install-sub` deep link
        // fetching on its own thread. Without this the new folder sat there empty, showing the
        // record as it looked *before* the fetch, until the app was restarted.
        DisposableEffect(ui) {
            val stop = ui.observeDataChanged { reloadProfiles() }
            onDispose { stop() }
        }

        // Subscriptions refresh themselves on their own interval. Checked periodically rather
        // than scheduled per subscription: the check is cheap and this also covers a device that
        // was asleep past several due times.
        LaunchedEffect(Unit) {
            while (true) {
                delay(5 * 60_000)
                val refreshed = withContext(Dispatchers.Default) {
                    runCatching { ui.refreshDueSubscriptions() }.getOrDefault(0)
                }
                if (refreshed > 0) reloadProfiles()
            }
        }

        /**
         * Run a subscription operation off the UI thread — these do network I/O.
         * [work] returns the platform's error message, or null on success.
         * [onDone] runs after a successful reload.
         */
        fun runSubscription(
            label: String,
            id: String?,
            onDone: () -> Unit = {},
            work: () -> String?
        ) {
            if (refreshingSubscriptionId != null) return
            refreshingSubscriptionId = id ?: PENDING_SUBSCRIPTION
            scope.launch {
                val error = withContext(Dispatchers.Default) { work() }
                refreshingSubscriptionId = null
                reloadProfiles()
                ui.toast(error ?: label)
                if (error == null) onDone()
            }
        }

        /**
         * Import whatever the user handed us — clipboard, file or Ctrl+V all land here.
         * A subscription URL becomes a folder, anything else a single profile; the two were
         * never worth separate menu items, since both are just text going through this.
         * [failureMessage] is what to say when the text is neither.
         */
        fun importText(text: String, failureMessage: String) {
            if (ui.looksLikeSubscription(text)) {
                runSubscription(t(S.TOAST_SUBSCRIPTION_ADDED), null) { ui.addSubscription(text) }
                return
            }
            val imported = ui.importFromText(text)
            if (imported.isEmpty()) {
                ui.toast(failureMessage)
            } else {
                reloadProfiles()
                // Own configs live in Home. Creating it (or revealing it next to a
                // URL/file folder) is what this increment is for.
                focusHomeEpoch++
                ui.toast(t(S.TOAST_PROFILE_IMPORTED))
            }
        }

        /** Import from the clipboard — menu item and Ctrl+V both land here. */
        fun importFromClipboard() {
            val text = ui.readClipboard()
            if (text.isBlank()) {
                ui.toast(t(S.TOAST_CLIPBOARD_EMPTY))
                return
            }
            importText(text, t(S.TOAST_INVALID_PROFILE_LINK))
        }

        DisposableEffect(ui) {
            val stop = ui.observePendingImport { url ->
                importText(url, t(S.TOAST_INVALID_PROFILE_LINK))
            }
            onDispose { stop() }
        }

        fun requestDeleteProfile(p: ConfigProfile) {
            // Deleting everything is allowed — the list then shows an "import a configuration"
            // hint instead of forcing a placeholder profile to exist.
            pendingDelete = p
            deleteDialogVisible = true
        }

        fun performDeleteProfile(p: ConfigProfile) {
            ui.deleteProfile(p.id)
            reloadProfiles()
            ui.toast(t(S.TOAST_PROFILE_DELETED))
            deleteDialogVisible = false
        }

        /** Drawer section: content swaps instantly; only the panel slides away. */
        fun goFromDrawer(to: AppScreen) {
            screen = to
        }

                /**
         * Mount the editor parked off-screen (slide = 1f), let it compose 1–2 frames there so the
         * first layout / JIT is not paid during the slide, then animate in. Without the wait the
         * editor "pops" with a hitch — AppCDS alone does not fully eliminate first-composition cost.
         */
        fun openEditor(draft: EditorDraft) {
            dismissKeyboard()
            editor = draft
            scope.launch {
                editorSlide.snapTo(1f)
                withFrameNanos { }
                withFrameNanos { }
                editorSlide.animateTo(
                    0f,
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                )
            }
        }

        fun leaveEditor() {
            dismissKeyboard()
            scope.launch {
                editorSlide.animateTo(
                    1f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                )
                editor = null
            }
        }

        @Suppress("UNUSED_VARIABLE")
        val _lang = uiTick

        if (screen == AppScreen.DIAGNOSTICS && !settings.fileLogging) {
            screen = AppScreen.HOME
        }

        // Match Android: content shifts 34dp right while the drawer is open.
        val density = LocalDensity.current
        val drawerWidthDp = 280.dp
        val drawerWidthPx = with(density) { drawerWidthDp.toPx() }
        val contentShiftMaxPx = with(density) { 34.dp.toPx() }
        val progress = drawerProgress.value
        val drawerTx = (progress - 1f) * drawerWidthPx
        val contentShiftPx = progress * contentShiftMaxPx
        val scrimAlpha = progress * 0.45f

        fun animateDrawerTo(open: Boolean, durationMs: Int = 240) {
            if (open) dismissKeyboard()
            drawerOpen = open
            scope.launch {
                // Stop any in-flight drag/settle, then tween to the target.
                drawerProgress.stop()
                drawerProgress.animateTo(
                    if (open) 1f else 0f,
                    animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
                )
            }
        }

        fun openDrawer() = animateDrawerTo(true, 260)
        fun closeDrawer(durationMs: Int = 240) = animateDrawerTo(false, durationMs)
        /**
         * Item pick:
         * 1) flip tab visibility (keep-alive — no heavy first compose if already visited);
         * 2) slide drawer closed on its own layer.
         */
        fun selectFromDrawer(item: AppDrawerItem) {
            dismissKeyboard()
            val target = when (item) {
                AppDrawerItem.HOME -> AppScreen.HOME
                AppDrawerItem.DIAGNOSTICS ->
                    if (settings.fileLogging) AppScreen.DIAGNOSTICS else null
                AppDrawerItem.SETTINGS -> AppScreen.SETTINGS
            } ?: return
            goFromDrawer(target)
            closeDrawer(durationMs = 260)
        }

        // System Back / Esc: drawer → close; editor → home; settings/diag → home.
        PlatformBackHandler(
            enabled = drawerOpen || drawerProgress.value > 0.05f ||
                editor != null ||
                screen == AppScreen.SETTINGS ||
                screen == AppScreen.DIAGNOSTICS
        ) {
            when {
                drawerOpen || drawerProgress.value > 0.05f -> closeDrawer()
                editor != null -> leaveEditor()
                screen == AppScreen.SETTINGS || screen == AppScreen.DIAGNOSTICS ->
                    goFromDrawer(AppScreen.HOME)
            }
        }

        /**
         * Drawer swipe, from anywhere on the content — but only while nothing else wants that
         * gesture. On the first folder the pager has nowhere to go rightwards, so the drag is
         * unambiguously the drawer's; past it a rightward swipe means "back one folder" and the
         * drawer stands down entirely (the hamburger still opens it). Closing works from anywhere
         * regardless, since the drawer itself is under the finger by then. Off while the profile
         * editor is up.
         */
        // Opening is edge-only: a full-content Initial-pass drag stole horizontal text selection
        // (long-press + drag in a TextField) and made the drawer shoot out as a "side menu".
        val drawerEdgePx = with(density) { 28.dp.toPx() }
        val drawerSwipeModifier = Modifier.pointerInput(
            editor != null,
            drawerWidthPx,
            swipeOpensDrawer,
            blockDrawerGestures,
            drawerEdgePx
        ) {
            if (editor != null || blockDrawerGestures) return@pointerInput
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // Everything here runs on the **Initial** pass. Pointer events reach the innermost
                // handler first on the Main pass, so a Main-pass gesture never saw the drag at
                // all on Home - the folder pager and the profile list had already taken it. The
                // Initial pass is the one that runs outside-in, which is the only way an edge
                // swipe can win against a scrollable child.
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val startProgress = drawerProgress.value
                val openedAtStart = startProgress > 0.5f
                if (!openedAtStart && !swipeOpensDrawer) return@awaitEachGesture
                // Closed drawer: only the left edge may start an open-swipe. Closing still works
                // from anywhere (the drawer is under the finger by then).
                if (!openedAtStart && down.position.x > drawerEdgePx) return@awaitEachGesture
                var dragged = 0f
                var claimed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!claimed) {
                        val total = change.position - down.position
                        // Give a vertical scroll away immediately; wait out the slop otherwise.
                        if (abs(total.y) > slop && abs(total.y) > abs(total.x)) break
                        if (abs(total.x) <= slop) continue
                        val opening = !openedAtStart && total.x > 0f
                        val closing = openedAtStart && total.x < 0f
                        if (!opening && !closing) break
                        claimed = true
                        dragged = total.x
                    } else {
                        dragged += change.positionChange().x
                    }
                    change.consume()
                    scope.launch {
                        drawerProgress.stop()
                        drawerProgress.snapTo(
                            (startProgress + dragged / drawerWidthPx).coerceIn(0f, 1f)
                        )
                    }
                }
                if (claimed) {
                    // Symmetric settle thresholds like original MainActivity.
                    val p = drawerProgress.value
                    val shouldOpen = p > 0.2f
                    if (shouldOpen) dismissKeyboard()
                    drawerOpen = shouldOpen
                    scope.launch {
                        drawerProgress.animateTo(
                            if (shouldOpen) 1f else 0f,
                            animationSpec = tween(240, easing = FastOutSlowInEasing)
                        )
                    }
                }
            }
        }

        // Diagnostics log state lives outside KeepAlive so it only loads once when first visited.
        var diagLogText by remember { mutableStateOf("") }
        var diagLogLoading by remember { mutableStateOf(true) }
        fun capLog(raw: String): String {
            val max = 14_000
            return if (raw.length <= max) raw else "…\n" + raw.takeLast(max)
        }
        // Read the log only while Diagnostics is actually open — nothing to do at startup.
        LaunchedEffect(screen == AppScreen.DIAGNOSTICS) {
            if (screen != AppScreen.DIAGNOSTICS) return@LaunchedEffect
            diagLogText = withContext(Dispatchers.Default) { capLog(ui.readDebugLog()) }
            diagLogLoading = false
            while (true) {
                delay(8_000)
                val next = withContext(Dispatchers.Default) { capLog(ui.readDebugLog()) }
                if (next != diagLogText) diagLogText = next
            }
        }

        // Expose clipboard import to the host window (Ctrl+V) while this UI is on screen.
        DisposableEffect(shortcuts) {
            shortcuts?.importFromClipboard = {
                // Ignore while the editor is open: there, Ctrl+V belongs to the text fields.
                if (editor == null) importFromClipboard()
            }
            onDispose { shortcuts?.importFromClipboard = null }
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(SmuglyBg)) {
            val editorWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            // Main content — shifts right while the drawer is open.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = contentShiftPx }
                    .then(drawerSwipeModifier)
            ) {
                // One main tab at a time. Keep-alive was tried twice for cold-tab freezes and
                // both times broke hit-testing: full-size invisible layers stole clicks, and
                // zIndex(1) on the active tab buried the profile editor under Home so
                // "New profile" appeared to do nothing. Tab warmth comes from AppCDS
                // (WarmupCds headless pass) instead.
                when (screen) {
                    AppScreen.HOME -> {
                        HomeScreen(
                            profiles = profiles,
                            activeId = activeId,
                            connect = connect,
                            onMenu = { openDrawer() },
                            onAddNew = {
                                // Blank profile — do not clone the active VPN (that pasted the last
                                // Xray JSON / domain into "New profile" and looked like a bug).
                                openEditor(
                                    emptyDraft(
                                        defaultConfig(mode = settings.mode).copy(
                                            listenPort = settings.listenPort
                                        )
                                    )
                                )
                            },
                            onImportClipboard = { importFromClipboard() },
                            onImportFile = {
                                // A file holding a subscription link imports the subscription,
                                // exactly as pasting that link would.
                                ui.pickImportFile { text ->
                                    if (text != null) {
                                        importText(text, t(S.TOAST_IMPORT_FILE_FAILED))
                                    }
                                }
                            },
                            onSelect = { p ->
                                if (p.id != activeId) {
                                    ui.selectProfile(p.id)
                                    activeId = p.id
                                }
                            },
                            onEdit = { p ->
                                // Stored Xray configs are often one long line (that is how a panel
                                // ships them); pretty-print on the way into the editor so it is
                                // readable without the user asking.
                                val config = if (p.config.protocol == Config.TunnelProtocol.XRAY) {
                                    ui.formatXrayJson(p.config.xrayConfigJson)
                                        ?.let { p.config.copy(xrayConfigJson = it) }
                                        ?: p.config
                                } else {
                                    p.config
                                }
                                openEditor(EditorDraft(p.id, p.name, config))
                            },
                            onDelete = { p -> requestDeleteProfile(p) },
                            onExport = { p ->
                                ui.writeClipboard(ui.exportProfileLink(p))
                                ui.toast(t(S.TOAST_PROFILE_LINK_COPIED))
                            },
                            onReorder = { orderedIds ->
                                ui.reorderProfiles(orderedIds)
                                reloadProfiles()
                            },
                            onToggle = { ui.toggleConnect() },
                            subscriptions = subscriptions,
                            refreshingSubscriptionId = refreshingSubscriptionId,
                            onRefreshSubscription = { id ->
                                runSubscription(t(S.TOAST_SUBSCRIPTION_UPDATED), id) {
                                    ui.refreshSubscription(id)
                                }
                            },
                            onDeleteSubscription = { id ->
                                ui.deleteSubscription(id)
                                reloadProfiles()
                            },
                            onRenameSubscription = { id, name ->
                                ui.renameSubscription(id, name)
                                reloadProfiles()
                            },
                            onFirstFolder = { swipeOpensDrawer = it },
                            onBlockDrawerGestures = { blockDrawerGestures = it },
                            homeFolderIndex = settings.homeFolderIndex,
                            initialFolderId = settings.lastFolderId,
                            focusFolderId = focusFolderId,
                            onFocusFolderConsumed = { focusFolderId = null },
                            focusHomeEpoch = focusHomeEpoch,
                            onFolderOpened = { id ->
                                if (id != settings.lastFolderId) {
                                    val next = settings.copy(lastFolderId = id)
                                    settings = next
                                    ui.saveGlobalSettings(next)
                                }
                            },
                            latencies = latencies,
                            onExportFolder = { list, folderName ->
                                if (list.isEmpty()) {
                                    ui.toast(t(S.TOAST_NOTHING_TO_EXPORT))
                                } else {
                                    // One link per line: exactly what "Import from file" reads
                                    // back, so an export can be re-imported without conversion.
                                    val safe = folderName.map { if (it.isLetterOrDigit()) it else '_' }
                                        .joinToString("")
                                        .trim('_')
                                        .ifBlank { "profiles" }
                                    ui.exportTextFile(
                                        "$safe.txt",
                                        list.joinToString(LINE_BREAK) { ui.exportProfileLink(it) }
                                    )
                                }
                            },
                            onMeasureLatency = { p ->
                                latencies = latencies + (p.id to LatencyUi(measuring = true))
                                ui.measureLatency(p) { result ->
                                    // The probe answers on its own thread; this scope belongs to
                                    // the composition, so launching on it lands the state update
                                    // back on whatever UI thread this platform uses.
                                    scope.launch {
                                        latencies = latencies + (p.id to result.fold(
                                            onSuccess = { LatencyUi(ms = it) },
                                            onFailure = { LatencyUi(failed = true) }
                                        ))
                                    }
                                }
                            },
                            onSaveFolder = { folder ->
                                // Networked only when a subscription URL is present; empty folders
                                // are a pure local write (no "subscription added" toast, no import overlay).
                                val hasUrl = folder.url.isNotBlank()
                                val toastOk = if (hasUrl) t(S.TOAST_SUBSCRIPTION_ADDED) else t(S.TOAST_FOLDER_SAVED)
                                val creating = folder.id == null
                                val knownIds = subscriptions.map { it.id }.toSet()
                                fun persist(): String? = ui.saveSubscription(
                                    id = folder.id,
                                    name = folder.name.trim(),
                                    url = folder.url.trim(),
                                    enabled = folder.enabled && hasUrl,
                                    updateIntervalMinutes = if (hasUrl && folder.autoUpdate) {
                                        app.smugly.subscription.Subscription
                                            .DEFAULT_UPDATE_INTERVAL_MINUTES
                                    } else {
                                        0
                                    },
                                    allowReorder = folder.allowReorder,
                                    showInfo = folder.showInfo
                                )
                                fun openCreatedFolder() {
                                    if (!creating) return
                                    focusFolderId = subscriptions.firstOrNull { it.id !in knownIds }?.id
                                }
                                if (creating && !hasUrl) {
                                    scope.launch {
                                        val error = withContext(Dispatchers.Default) { persist() }
                                        reloadProfiles()
                                        ui.toast(error ?: toastOk)
                                        if (error == null) openCreatedFolder()
                                    }
                                } else {
                                    runSubscription(toastOk, folder.id, onDone = { openCreatedFolder() }) {
                                        persist()
                                    }
                                }
                            },
                            collapsedCategories = settings.collapsedCategories,
                            onCollapsedCategoriesChange = { folded ->
                                // Keys of folders that no longer exist would accumulate forever;
                                // a deleted subscription takes its groups with it.
                                val known = subscriptions.map { it.id }.toSet()
                                val kept = folded.filterTo(mutableSetOf()) {
                                    it.substringBefore('/') in known
                                }
                                val next = settings.copy(collapsedCategories = kept)
                                settings = next
                                ui.saveGlobalSettings(next)
                            },
                            onReorderFolders = { ids, homeIndex ->
                                ui.reorderSubscriptions(ids)
                                val next = settings.copy(homeFolderIndex = homeIndex)
                                settings = next
                                ui.saveGlobalSettings(next)
                                reloadProfiles()
                            }
                        )
                    }
                    AppScreen.SETTINGS -> {
                        SettingsScreen(
                            settings = settings,
                            supportsVpn = ui.supportsSystemVpn(),
                            showTrafficNotification = currentHostPlatform().supportsTrafficNotification(),
                            showLocalSocksAuth = currentHostPlatform().supportsLocalProxyAuth(),
                            onMenu = { openDrawer() },
                            onChange = { next ->
                                val fixed = if (ui.supportsSystemVpn()) next
                                else next.copy(
                                    mode = Config.Mode.PROXY,
                                    trafficNotification = false
                                )
                                settings = fixed
                                ui.saveGlobalSettings(fixed)
                                Strings.set(fixed.language)
                                uiTick++
                            }
                        )
                    }
                    AppScreen.DIAGNOSTICS -> {
                        DiagnosticsScreen(
                            logText = when {
                                diagLogLoading && diagLogText.isEmpty() ->
                                    if (Strings.current == app.smugly.AppLanguage.RU) {
                                        "Загрузка лога…"
                                    } else {
                                        "Loading log…"
                                    }
                                else -> diagLogText
                            },
                            onMenu = { openDrawer() },
                            onShareLog = { ui.shareLog() },
                            onCrashReport = {
                                crashDialogBody = ui.readCrashReportText()
                                    .ifBlank { t(S.NO_CRASH_REPORT) }
                                crashDialogVisible = true
                            },
                            onRefreshLog = {
                                scope.launch {
                                    diagLogText = withContext(Dispatchers.Default) {
                                        capLog(ui.readDebugLog())
                                    }
                                }
                            }
                        )
                    }
                }

                // Profile editor: its own layer, slid in/out with an Animatable (same
                // idea as the drawer). Mounted only while open.
                val d = editor
                if (d != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = editorSlide.value * editorWidthPx
                            }
                            // Full-size opaque hit target so taps never reach the tab below.
                            .background(SmuglyBg)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                    ) {
                        ProfileEditorScreen(
                            draft = d,
                            onBack = { leaveEditor() },
                            onChange = { editor = it },
                            onSave = {
                                var clean = d.config.copy(
                                    domain = d.config.domain.trim(),
                                    resolverHost = d.config.resolverHost.trim(),
                                    listenPort = settings.listenPort,
                                    mode = settings.mode
                                )
                                // Saving an Xray profile is where the config gets checked, now
                                // that there is no "Check" button to forget to press. A bad
                                // config never reaches storage, and a good one is stored tidy.
                                if (clean.protocol == Config.TunnelProtocol.XRAY) {
                                    val json = clean.xrayConfigJson
                                    if (json.isBlank()) {
                                        ui.toast(t(S.TOAST_XRAY_CONFIG_EMPTY))
                                        return@ProfileEditorScreen
                                    }
                                    val error = ui.validateXrayConfig(json)
                                    if (error != null) {
                                        ui.toast(error)
                                        return@ProfileEditorScreen
                                    }
                                    ui.formatXrayJson(json)?.let {
                                        clean = clean.copy(xrayConfigJson = it)
                                    }
                                }
                                if (d.profileId == null) {
                                    ui.addProfile(d.name, clean)
                                    ui.toast(t(S.TOAST_PROFILE_CREATED))
                                    focusHomeEpoch++
                                } else {
                                    // Edit the stored profile rather than rebuilding one: the
                                    // constructor defaults every field the editor does not carry
                                    // (folder, category) to null, so saving an edited subscription
                                    // server used to orphan it into Home.
                                    val stored = profiles.firstOrNull { it.id == d.profileId }
                                    ui.saveProfile(
                                        stored?.copy(name = d.name, config = clean)
                                            ?: ConfigProfile(
                                                id = d.profileId,
                                                name = d.name,
                                                config = clean
                                            )
                                    )
                                    ui.toast(t(S.TOAST_PROFILE_SAVED))
                                }
                                reloadProfiles()
                                leaveEditor()
                            },
                            onDelete = d.profileId?.let { id ->
                                {
                                    profiles.firstOrNull { it.id == id }?.let { p ->
                                        pendingDelete = p
                                        deleteDialogVisible = true
                                    }
                                }
                            },
                            onLocalDns = {
                                val local = ui.localDnsResolver()
                                if (local.isNullOrBlank()) ui.toast(t(S.TOAST_NO_LOCAL_DNS))
                                else {
                                    editor = d.copy(
                                        config = d.config.copy(resolverHost = local)
                                    )
                                }
                            },
                            formatXray = { ui.formatXrayJson(it) }
                        )
                    }
                }
            }

            // Drawer always composed (parked off-left when closed) — no first-open hitch.
            if (scrimAlpha > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = scrimAlpha
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .background(Color.Black)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = progress > 0.5f
                        ) { closeDrawer() }
                )
            }
            DrawerPanel(
                selected = when (screen) {
                    AppScreen.SETTINGS -> AppDrawerItem.SETTINGS
                    AppScreen.DIAGNOSTICS -> AppDrawerItem.DIAGNOSTICS
                    else -> AppDrawerItem.HOME
                },
                showDiagnostics = settings.fileLogging,
                onSelect = { item -> selectFromDrawer(item) },
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidthDp)
                    .graphicsLayer {
                        translationX = drawerTx
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
            )

            // Confirm delete — always composed so exit anim can finish (like popup menu).
            val toDelete = pendingDelete
            ConfirmDialog(
                visible = deleteDialogVisible && toDelete != null,
                title = t(S.DELETE_PROFILE_TITLE),
                message = toDelete?.let { it.config.domain.ifBlank { it.name } }.orEmpty(),
                confirmLabel = t(S.DELETE_BTN),
                cancelLabel = t(S.CANCEL_BTN),
                onConfirm = {
                    val p = pendingDelete ?: return@ConfirmDialog
                    val wasEditing = editor?.profileId == p.id
                    performDeleteProfile(p)
                    if (wasEditing) leaveEditor()
                },
                onDismiss = { deleteDialogVisible = false }
            )

            // Crash report — same menu-style enter/exit.
            CrashReportDialog(
                visible = crashDialogVisible,
                title = t(S.CRASH_REPORT_TITLE),
                body = crashDialogBody.ifBlank { " " },
                copyLabel = t(S.COPY_BTN),
                closeLabel = t(S.CLOSE_BTN),
                onCopy = {
                    ui.writeClipboard(crashDialogBody)
                    ui.toast(t(S.TOAST_CRASH_REPORT_COPIED))
                },
                onDismiss = { crashDialogVisible = false }
            )

            // A brand-new subscription has no folder tab yet, so there is nowhere to spin an
            // icon — the whole screen waits instead. A refresh of an existing one already spins
            // its own card and must not block the UI.
            LoadingOverlay(
                visible = refreshingSubscriptionId == PENDING_SUBSCRIPTION,
                message = t(S.SUBSCRIPTION_IMPORTING)
            )

            // Toast: snap in, fade out.
            AppToast(visible = toastVisible, message = toastText.ifBlank { " " })

        }
    }
}

/** Separator for exported profile lists — one link per line. */
private const val LINE_BREAK = "\n"
