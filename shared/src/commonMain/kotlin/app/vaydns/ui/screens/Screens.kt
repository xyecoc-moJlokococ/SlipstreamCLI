package app.vaydns.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import app.vaydns.ui.PlatformBackHandler
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
import app.vaydns.ui.components.SlipnetTextField
import app.vaydns.ui.components.TopBar
import app.vaydns.ui.maskDomain
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
    onToggle: () -> Unit
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

    // Local order while dragging; resync from [profiles] when idle.
    var ordered by remember { mutableStateOf(profiles) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragFromIndex by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var gapTargetIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(profiles, draggingId) {
        if (draggingId == null) ordered = profiles
    }

    // The menus are plain overlays, not focusable Popup windows, so back has to be
    // handled here — otherwise it would fall through and close the app.
    PlatformBackHandler(enabled = addMenu || moreFor != null) {
        addMenu = false
        moreFor = null
    }

    val density = LocalDensity.current
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
                    moreFor = null
                    addMenu = true
                },
                onAddAnchor = { addAnchor[0] = it }
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .verticalScroll(rememberScrollState(), enabled = draggingId == null)
                    // Space for bar (56) + half button (~33) sitting above the bar.
                    .padding(bottom = 96.dp)
            ) {
                ordered.forEachIndexed { index, profile ->
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
                    val sub = profileSubtitle(profile)
                    ProfileCard(
                        name = profile.name.ifBlank { t(S.PROFILE_NAME_FALLBACK) },
                        subtitle = if (sub.isBlank()) "" else maskDomain(sub),
                        selected = profile.id == activeId,
                        onClick = { if (draggingId == null) onSelect(profile) },
                        onDelete = { onDelete(profile) },
                        onMoreClick = {
                            menuAnchorY = cardAnchors[profile.id] ?: 0
                            addMenu = false
                            menuProfile = profile
                            moreFor = profile
                        },
                        onMoreAnchor = { y -> cardAnchors[profile.id] = y },
                        dragOffsetY = if (isDragging) dragOffsetY else 0f,
                        isDragging = isDragging,
                        gapOffsetY = gapOffset,
                        enableReorder = ordered.size > 1,
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
                            val target = (dragFromIndex + shiftSlots).coerceIn(0, ordered.lastIndex)
                            if (target != gapTargetIndex) gapTargetIndex = target
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
        BottomConnectBar(
            status = connect.statusText.ifBlank { t(S.STATUS_NOT_CONNECTED) },
            traffic = connect.trafficText,
            running = connect.running,
            loading = connect.connecting,
            onToggle = onToggle,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Menus last so they paint over the list and the bottom bar.
        MenuLayer(
            visible = addMenu,
            anchorY = menuAnchorY,
            onDismiss = { addMenu = false }
        ) {
            MenuRow(t(S.MENU_NEW_PROFILE)) { addMenu = false; onAddNew() }
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
    }
}

@Composable
fun SettingsScreen(
    settings: GlobalSettings,
    supportsVpn: Boolean,
    /** Android status-bar traffic notification; hidden on desktop/iOS. */
    showTrafficNotification: Boolean = false,
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
            // Local SOCKS auth is meaningful for desktop proxy clients.
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
