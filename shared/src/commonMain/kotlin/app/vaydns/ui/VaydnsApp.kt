package app.vaydns.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import app.vaydns.Config
import app.vaydns.ConfigProfile
import app.vaydns.S
import app.vaydns.Strings
import app.vaydns.currentHostPlatform
import app.vaydns.defaultConfig
import app.vaydns.supportsTrafficNotification
import app.vaydns.t
import app.vaydns.ui.components.AppDrawerItem
import app.vaydns.ui.components.AppToast
import app.vaydns.ui.components.ConfirmDialog
import app.vaydns.ui.components.CrashReportDialog
import app.vaydns.ui.components.DrawerPanel
import app.vaydns.ui.screens.DiagnosticsScreen
import app.vaydns.ui.screens.HomeScreen
import app.vaydns.ui.screens.ProfileEditorScreen
import app.vaydns.ui.screens.SettingsScreen
import app.vaydns.ui.theme.SlipnetBg
import app.vaydns.ui.theme.VaydnsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Shared multiplatform UI root — same screens/palette as the Android View UI,
 * including horizontal page transitions and drawer content-shift.
 */
@Composable
fun VaydnsApp(platform: VaydnsPlatform) {
    VaydnsTheme {
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
        var profiles by remember { mutableStateOf(ui.loadProfiles()) }
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
            activeId = ui.loadActiveProfileId() ?: profiles.firstOrNull()?.id
        }

        fun requestDeleteProfile(p: ConfigProfile) {
            if (profiles.size <= 1) {
                ui.toast(t(S.TOAST_CANNOT_DELETE_LAST_PROFILE))
            } else {
                pendingDelete = p
                deleteDialogVisible = true
            }
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
         * Mount the editor parked off-screen (slide is already at 1f) and slide it in on
         * the next frame. No pre-warming: the editor exists only while it is open.
         */
        fun openEditor(draft: EditorDraft) {
            dismissKeyboard()
            editor = draft
            scope.launch {
                editorSlide.snapTo(1f)
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
         * Full-screen horizontal swipe (original Android dispatchTouchEvent behaviour):
         * start from anywhere on the content, not only a left edge strip.
         * Uses horizontal touch-slop so vertical scrolls still work.
         */
        val drawerSwipeModifier = Modifier.pointerInput(editor != null, drawerWidthPx) {
            if (editor != null) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startProgress = drawerProgress.value
                val openedAtStart = startProgress > 0.5f
                var dragged = 0f
                val slopPointer = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    // Claim only when direction matches open/close intent.
                    val openDir = !openedAtStart && over > 0f
                    val closeDir = openedAtStart && over < 0f
                    if (openDir || closeDir) {
                        change.consume()
                        dragged = over
                        scope.launch {
                            drawerProgress.stop()
                            drawerProgress.snapTo(
                                (startProgress + over / drawerWidthPx).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                if (slopPointer != null && abs(dragged) > 0f) {
                    horizontalDrag(slopPointer.id) { change ->
                        val dx = change.positionChange().x
                        change.consume()
                        dragged += dx
                        val next = (startProgress + dragged / drawerWidthPx).coerceIn(0f, 1f)
                        scope.launch { drawerProgress.snapTo(next) }
                    }
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

        BoxWithConstraints(Modifier.fillMaxSize().background(SlipnetBg)) {
            val editorWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            // Main content — shifts right while the drawer is open.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = contentShiftPx }
                    .then(drawerSwipeModifier)
            ) {
                // One main tab is composed at a time. The editor is a layer on top of
                // it, so the tab stays mounted underneath only while the editor is open.
                when (screen) {
                    AppScreen.HOME -> {
                        HomeScreen(
                            profiles = profiles,
                            activeId = activeId,
                            connect = connect,
                            onMenu = { openDrawer() },
                            onAddNew = {
                                val base = profiles.firstOrNull { it.id == activeId }?.config
                                    ?: defaultConfig(mode = settings.mode)
                                openEditor(
                                    emptyDraft(
                                        base.copy(mode = settings.mode, listenPort = settings.listenPort)
                                    )
                                )
                            },
                            onImportClipboard = {
                                val text = ui.readClipboard()
                                if (text.isBlank()) {
                                    ui.toast(t(S.TOAST_CLIPBOARD_EMPTY))
                                } else {
                                    val imported = ui.importFromText(text)
                                    if (imported.isEmpty()) ui.toast(t(S.TOAST_INVALID_PROFILE_LINK))
                                    else {
                                        reloadProfiles()
                                        ui.toast(t(S.TOAST_PROFILE_IMPORTED))
                                    }
                                }
                            },
                            onImportFile = {
                                ui.pickImportFile { text ->
                                    if (text == null) return@pickImportFile
                                    val imported = ui.importFromText(text)
                                    if (imported.isEmpty()) ui.toast(t(S.TOAST_IMPORT_FILE_FAILED))
                                    else {
                                        reloadProfiles()
                                        ui.toast(t(S.TOAST_PROFILE_IMPORTED))
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
                                openEditor(EditorDraft(p.id, p.name, p.config))
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
                            onToggle = { ui.toggleConnect() }
                        )
                    }
                    AppScreen.SETTINGS -> {
                        SettingsScreen(
                            settings = settings,
                            supportsVpn = ui.supportsSystemVpn(),
                            showTrafficNotification = currentHostPlatform().supportsTrafficNotification(),
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
                                    if (Strings.current == app.vaydns.AppLanguage.RU) {
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
                            .background(SlipnetBg)
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
                                val clean = d.config.copy(
                                    domain = d.config.domain.trim(),
                                    resolverHost = d.config.resolverHost.trim(),
                                    listenPort = settings.listenPort,
                                    mode = settings.mode
                                )
                                if (d.profileId == null) {
                                    ui.addProfile(d.name, clean)
                                    ui.toast(t(S.TOAST_PROFILE_CREATED))
                                } else {
                                    ui.saveProfile(
                                        ConfigProfile(d.profileId, d.name, clean)
                                    )
                                    ui.toast(t(S.TOAST_PROFILE_SAVED))
                                }
                                reloadProfiles()
                                leaveEditor()
                            },
                            onDelete = d.profileId?.let { id ->
                                {
                                    val p = profiles.firstOrNull { it.id == id }
                                    if (p == null || profiles.size <= 1) {
                                        ui.toast(t(S.TOAST_CANNOT_DELETE_LAST_PROFILE))
                                    } else {
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
                            onFormatXray = {
                                val formatted = ui.formatXrayJson(d.config.xrayConfigJson)
                                if (formatted == null) ui.toast(t(S.TOAST_XRAY_NOT_JSON))
                                else {
                                    editor = d.copy(
                                        config = d.config.copy(xrayConfigJson = formatted)
                                    )
                                }
                            },
                            onValidateXray = {
                                val json = d.config.xrayConfigJson
                                if (json.isBlank()) {
                                    ui.toast(t(S.TOAST_XRAY_CONFIG_EMPTY))
                                } else {
                                    val err = ui.validateXrayConfig(json)
                                    if (err == null) ui.toast(t(S.TOAST_XRAY_CONFIG_OK))
                                    else ui.toast(err)
                                }
                            }
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

            // Toast: snap in, fade out.
            AppToast(visible = toastVisible, message = toastText.ifBlank { " " })

        }
    }
}
