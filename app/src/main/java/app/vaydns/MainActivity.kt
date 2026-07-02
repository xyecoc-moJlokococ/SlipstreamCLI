package app.vaydns

import android.animation.ValueAnimator
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.slipnet.tunnel.HevSocks5Tunnel
import app.slipnet.tunnel.MiniSlipstreamSocksBridge
import app.slipnet.tunnel.ResolverListConfig
import app.slipnet.tunnel.SlipstreamBridge
import app.slipnet.util.AppLog
import app.vaydns.service.TinyVpnService

class MainActivity : android.app.Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var domain: EditText
    private lateinit var resolverHost: EditText
    private lateinit var resolverHostContainer: View
    private lateinit var resolverPort: EditText
    private lateinit var resolverMode: Spinner
    private lateinit var resolverTransport: Spinner
    private lateinit var useLocalDns: Button
    private lateinit var listenPort: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var mode: Spinner
    private lateinit var auth: Spinner
    private lateinit var fileLogging: CheckBox
    private lateinit var trafficNotification: CheckBox
    private lateinit var profileName: EditText
    private lateinit var connectButton: Button
    private lateinit var connectProgress: ProgressBar
    private lateinit var connectButtonBackground: GradientDrawable
    private lateinit var bottomStatus: TextView
    private lateinit var trafficStatus: TextView
    private lateinit var status: TextView
    private var profiles: List<ConfigProfile> = emptyList()
    private var activeConfig: Config? = null
    private var editorVisible = false
    private var settingsVisible = false
    private var diagnosticsVisible = false
    private var proxyStarted = false
    private var connectButtonColor = 0
    private var connectButtonRunning = false
    @Volatile private var stopping = false
    @Volatile private var connecting = false
    private var rxBase = 0L
    private var txBase = 0L
    private var rateRxLast = 0L
    private var rateTxLast = 0L
    private var rateSampleAt = 0L
    private var lastLogAt = 0L
    private var pendingStartVpn = false
    private var startupPermissionFlowActive = false
    private var notificationRequestShown = false
    private var startupVpnRequestShown = false
    private var batteryRequestShown = false

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        configureNativeLogging()
        loadConfig()
        setContentView(buildUi())
        updateStatus()
        handleImportIntent(intent)
        handler.post(tick)
        handler.post { maybeShowNewCrashReport() }
        handler.post { runStartupPermissionFlow() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (hideKeyboardIfEditing()) return
        if (editorVisible) {
            showMainScreen()
        } else if (settingsVisible) {
            showMainScreen()
        } else if (diagnosticsVisible) {
            showMainScreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> {
                if (resultCode == RESULT_OK) {
                    continuePreflight()
                } else {
                    pendingStartVpn = false
                    connecting = false
                    updateStatus()
                    toast("VPN permission is required")
                }
            }
            REQ_VPN_STARTUP, REQ_BATTERY -> continueStartupPermissionFlow()
            REQ_BACKGROUND_SETTINGS -> markBackgroundSettingsPrompted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATIONS -> {
                if (pendingStartVpn) {
                    continuePreflight()
                } else {
                    continueStartupPermissionFlow()
                }
            }
        }
    }

    private fun buildUi(): View = buildMainUi()

    private fun buildMainUi(): View {
        editorVisible = false
        settingsVisible = false
        diagnosticsVisible = false

        val frame = FrameLayout(this).apply {
            id = R.id.main_content
            setBackgroundColor(color(R.color.slipnet_bg))
        }
        val root = screenRoot().apply {
            setPadding(dp(16), 0, dp(16), dp(82))
        }
        root.addView(mainTopBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(profileList(), compactSectionParams())
        val scroll = scrollScreen(root).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                root.setPadding(
                    dp(16),
                    dp(6) + insets.systemWindowInsetTop,
                    dp(16),
                    dp(82) + insets.systemWindowInsetBottom
                )
                insets
            }
        }
        frame.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(bottomConnectBar(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM))
        root.requestFocus()
        return frame
    }

    private fun mainTopBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_menu, "Settings").apply {
                id = R.id.global_settings_button
                setOnClickListener { showGlobalSettings() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(iconButton(R.drawable.ic_diagnostics, "Diagnostics").apply {
                setOnClickListener { showDiagnostics() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(iconButton(R.drawable.ic_add, "New profile").apply {
                id = R.id.add_profile_button
                setOnClickListener { showProfileEditor(null) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                leftMargin = dp(8)
            })
        }

    private fun profileList(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val activeId = ConfigStore.activeProfileId(this@MainActivity)
            profiles.forEach { profile ->
                addView(profileRow(profile, profile.id == activeId), fieldParams())
            }
        }

    private fun profileRow(profile: ConfigProfile, selected: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, if (selected) R.drawable.bg_profile_selected else R.drawable.bg_card)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            minimumHeight = dp(64)
            setOnClickListener { selectProfile(profile) }
            val marker = TextView(this@MainActivity).apply {
                text = if (selected) "●" else "○"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.slipnet_accent))
            }
            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = profile.name.ifBlank { "Manual" }
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(color(R.color.slipnet_text_primary))
                    setSingleLine(true)
                }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(TextView(this@MainActivity).apply {
                    text = maskDomain(profile.config.domain.ifBlank { profile.name })
                    textSize = 13f
                    setTextColor(color(R.color.slipnet_text_secondary))
                    setSingleLine(true)
                }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val more = iconButton(R.drawable.ic_more_vert, "Profile menu").apply {
                setOnClickListener { showProfileEditor(profile) }
            }
            val edit = iconButton(R.drawable.ic_edit_profile, "Edit profile").apply {
                id = R.id.edit_profile_button
                setOnClickListener { showProfileEditor(profile) }
            }
            val delete = iconButton(R.drawable.ic_delete_profile, "Delete profile").apply {
                id = R.id.delete_profile_button
                setOnClickListener { confirmDeleteProfile(profile) }
            }
            addView(marker, LinearLayout.LayoutParams(dp(34), dp(40)))
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            })
            addView(more, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(edit, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(delete, LinearLayout.LayoutParams(dp(36), dp(36)))
        }

    private fun showProfileEditor(profile: ConfigProfile?) {
        val config = profile?.config ?: activeConfig ?: ConfigStore.load(this)
        editorVisible = true
        settingsVisible = false
        setContentView(buildProfileEditorUi(profile, config))
        applyConfigToFields(config)
        updateStatus()
    }

    private fun buildProfileEditorUi(profile: ConfigProfile?, config: Config): View {
        val root = screenRoot()
        root.addView(row(
            button("BACK").apply { setOnClickListener { showMainScreen() } },
            button(if (profile == null) "CREATE PROFILE" else "SAVE PROFILE", primary = true).apply {
                id = R.id.save_config_button
                setOnClickListener { saveProfileFromEditor(profile) }
            }
        ), sectionParams())

        profileName = edit("profile name").apply {
            id = R.id.profile_name
            setText(profile?.name ?: config.domain.ifBlank { "New profile" })
        }
        domain = edit("domain").apply { id = R.id.domain_field }
        resolverHost = edit("resolver host").apply { id = R.id.resolver_host_field }
        useLocalDns = button("USE LOCAL DNS").apply {
            id = R.id.use_local_dns_button
            setOnClickListener { fillLocalDns() }
        }
        val resolverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(resolverHost, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(useLocalDns, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                leftMargin = dp(8)
            })
        }
        resolverPort = edit("resolver port", InputType.TYPE_CLASS_NUMBER).apply { id = R.id.resolver_port_field }
        resolverMode = spinner(listOf("manual dns", "auto dns")).apply {
            id = R.id.resolver_mode_spinner
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateResolverUi()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            })
        }
        resolverTransport = spinner(listOf("udp", "tcp")).apply { id = R.id.resolver_transport_spinner }
        auth = spinner(listOf("no-auth", "login/password")).apply { id = R.id.auth_spinner }
        username = edit("username").apply { id = R.id.username_field }
        password = edit("password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            id = R.id.password_field
        }

        root.addView(card().apply {
            addView(sectionTitle(if (profile == null) "New Profile" else "Profile Config"))
            addView(labeledField("Profile name", profileName), fieldParams())
            addView(labeledField("Domain", domain), fieldParams())
            addView(labeledField("DNS mode", resolverMode), fieldParams())
            resolverHostContainer = labeledField("Resolver host", resolverRow)
            addView(resolverHostContainer, fieldParams())
            addView(row(
                labeledField("Resolver port", resolverPort),
                labeledField("Transport", resolverTransport)
            ), fieldParams())
            addView(labeledField("Auth mode", auth), fieldParams())
            addView(labeledField("Username", username), fieldParams())
            addView(labeledField("Password", password), fieldParams())
            if (profile != null) {
                addView(button("DELETE PROFILE").apply {
                    id = R.id.delete_profile_button
                    setOnClickListener { confirmDeleteProfile(profile) }
                }, fieldParams())
            }
        }, sectionParams())

        root.requestFocus()
        return scrollScreen(root)
    }

    private fun screenRoot(): LinearLayout {
        window.statusBarColor = color(R.color.slipnet_bg)
        if (Build.VERSION.SDK_INT >= 26) {
            window.navigationBarColor = color(R.color.slipnet_card)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
        }
        return LinearLayout(this).apply {
            id = R.id.main_content
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
    }

    private fun scrollScreen(root: LinearLayout): ScrollView =
        ScrollView(this).apply {
            id = R.id.main_scroll
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(color(R.color.slipnet_bg))
            setOnApplyWindowInsetsListener { _, insets ->
                root.setPadding(
                    dp(20),
                    dp(20) + insets.systemWindowInsetTop,
                    dp(20),
                    dp(24) + insets.systemWindowInsetBottom
                )
                insets
            }
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            post {
                root.requestFocus()
                scrollTo(0, 0)
            }
        }

    private fun showMainScreen() {
        editorVisible = false
        settingsVisible = false
        diagnosticsVisible = false
        loadConfig()
        setContentView(buildMainUi())
        updateStatus()
    }

    private fun showDiagnostics() {
        editorVisible = false
        settingsVisible = false
        diagnosticsVisible = true
        val root = screenRoot()
        root.addView(row(
            button("BACK").apply { setOnClickListener { showMainScreen() } },
            button("SHARE LOG").apply {
                id = R.id.share_log_button
                setOnClickListener { shareLogFile() }
            }
        ), sectionParams())
        status = TextView(this).apply {
            id = R.id.status_text
            textSize = 12f
            setLineSpacing(0f, 1.05f)
            setTextIsSelectable(true)
            setTextColor(color(R.color.slipnet_text_secondary))
        }
        root.addView(card().apply {
            addView(sectionTitle("Diagnostics"))
            addView(status, fieldParams())
            addView(button("CRASH REPORT").apply {
                id = R.id.crash_report_button
                setOnClickListener { showCrashReport() }
            }, fieldParams())
        }, sectionParams())
        setContentView(scrollScreen(root))
        updateStatus()
    }

    private fun bottomConnectBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_bottom_bar)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnApplyWindowInsetsListener { view, insets ->
                val bottomInset = insets.systemWindowInsetBottom
                view.setPadding(dp(16), dp(8), dp(16), dp(8) + bottomInset)
                view.layoutParams = view.layoutParams.apply {
                    height = dp(76) + bottomInset
                }
                insets
            }
            val statusColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bottomStatus = TextView(this@MainActivity).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_accent))
                text = "Not connected"
                setSingleLine(true)
            }
            trafficStatus = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(color(R.color.slipnet_text_secondary))
                text = "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)"
                setSingleLine(true)
            }
            statusColumn.addView(bottomStatus, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            statusColumn.addView(trafficStatus, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            connectButton = Button(this@MainActivity).apply {
                id = R.id.connect_button
                text = "▶"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_button_text_primary))
                connectButtonColor = color(R.color.slipnet_accent)
                connectButtonRunning = false
                connectButtonBackground = connectButtonDrawable(connectButtonColor)
                background = connectButtonBackground
                stateListAnimator = null
                elevation = 0f
                translationZ = 0f
                setPadding(0, 0, 0, dp(2))
                setOnClickListener { toggle() }
            }
            connectProgress = ProgressBar(this@MainActivity).apply {
                id = R.id.connect_progress
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(color(R.color.slipnet_button_text_primary))
                visibility = View.GONE
                elevation = dp(8).toFloat()
                translationZ = dp(8).toFloat()
            }
            val connectFrame = FrameLayout(this@MainActivity).apply {
                addView(connectButton, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
                addView(connectProgress, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }
            addView(statusColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(connectFrame, LinearLayout.LayoutParams(dp(58), dp(58)))
        }

    private fun showGlobalSettings() {
        editorVisible = false
        settingsVisible = true
        setContentView(buildGlobalSettingsUi())
        val global = ConfigStore.loadGlobalSettings(this)
        listenPort.setText(global.listenPort.toString())
        mode.setSelection(if (global.mode == Config.Mode.VPN) 1 else 0)
        fileLogging.isChecked = global.fileLogging
        trafficNotification.isChecked = global.trafficNotification
    }

    private fun buildGlobalSettingsUi(): View {
        val root = screenRoot()
        root.addView(row(
            button("BACK").apply { setOnClickListener { showMainScreen() } },
            button("SAVE SETTINGS", primary = true).apply {
                id = R.id.save_global_settings_button
                setOnClickListener { saveGlobalSettingsFromEditor() }
            }
        ), sectionParams())
        listenPort = edit("local port", InputType.TYPE_CLASS_NUMBER).apply { id = R.id.listen_port_field }
        mode = spinner(listOf("proxy", "vpn")).apply { id = R.id.mode_spinner }
        fileLogging = debugLogCheckbox()
        trafficNotification = CheckBox(this).apply {
            text = "Show traffic notification"
            textSize = 14f
            setTextColor(color(R.color.slipnet_text_secondary))
            buttonTintList = ColorStateList.valueOf(color(R.color.slipnet_accent))
        }
        root.addView(card().apply {
            addView(sectionTitle("Settings"))
            addView(labeledField("Local port", listenPort), fieldParams())
            addView(labeledField("Connection mode", mode), fieldParams())
            addView(fileLogging, fieldParams())
            addView(trafficNotification, fieldParams())
        }, sectionParams())
        root.requestFocus()
        return scrollScreen(root)
    }

    private fun addActionsCard(root: LinearLayout) {
        connectProgress = ProgressBar(this).apply {
            id = R.id.connect_progress
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(color(R.color.slipnet_accent))
            visibility = View.GONE
        }
        connectButton = button("CONNECT", primary = true).apply {
            id = R.id.connect_button
            setOnClickListener { toggle() }
        }
        val connectFrame = FrameLayout(this).apply {
            addView(connectButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(54)))
            addView(connectProgress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                leftMargin = dp(22)
            })
        }
        val shareLog = button("SHARE LOG").apply {
            id = R.id.share_log_button
            setOnClickListener { shareLogFile() }
        }
        val crashReport = button("CRASH REPORT").apply {
            id = R.id.crash_report_button
            setOnClickListener { showCrashReport() }
        }
        val settings = iconButton(R.drawable.ic_more_vert, "Settings").apply {
            id = R.id.global_settings_button
            setOnClickListener { showGlobalSettings() }
        }
        root.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(sectionTitle("Actions"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            addView(connectFrame, fieldParams())
            addView(row(shareLog, crashReport), fieldParams())
        }, sectionParams())
    }

    private fun addDiagnosticsCard(root: LinearLayout) {
        status = TextView(this).apply {
            id = R.id.status_text
            textSize = 12f
            setLineSpacing(0f, 1.05f)
            setTextIsSelectable(true)
            setTextColor(color(R.color.slipnet_text_secondary))
        }
        root.addView(card().apply {
            addView(sectionTitle("Diagnostics"))
            addView(status, fieldParams())
        }, sectionParams())
    }

    private fun debugLogCheckbox(): CheckBox =
        CheckBox(this).apply {
            id = R.id.file_logging_checkbox
            text = "Write debug log"
            textSize = 14f
            setTextColor(color(R.color.slipnet_text_secondary))
            buttonTintList = ColorStateList.valueOf(color(R.color.slipnet_accent))
            isChecked = AppLog.isFileLoggingEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                AppLog.setFileLoggingEnabled(this@MainActivity, enabled)
                configureNativeLogging()
                toast(if (enabled) "file logging enabled" else "file logging disabled")
            }
        }

    private fun labeledField(label: String, field: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_text_muted))
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(field, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun edit(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = ""
            inputType = type
            setSingleLine(true)
            textSize = 15f
            setTextColor(color(R.color.slipnet_text_primary))
            setHintTextColor(color(R.color.slipnet_text_muted))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(48)
        }

    private fun spinner(items: List<String>): Spinner =
        Spinner(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            minimumHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            adapter = darkAdapter(items)
        }

    private fun darkAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { tintSpinnerText(it) }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { tintSpinnerText(it) }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun tintSpinnerText(view: View) {
        (view as? TextView)?.apply {
            setTextColor(color(R.color.slipnet_text_primary))
            textSize = 15f
        }
    }

    private fun button(text: String, primary: Boolean = false): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(if (primary) R.color.slipnet_button_text_primary else R.color.slipnet_text_primary))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
            )
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
        }

    private fun iconButton(iconRes: Int, description: String): ImageButton =
        ImageButton(this).apply {
            contentDescription = description
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color(R.color.slipnet_text_secondary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

    private fun connectButtonDrawable(fillColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }

    private fun updateConnectButtonColor(running: Boolean) {
        if (!::connectButtonBackground.isInitialized) return
        if (connectButtonRunning == running) return
        connectButtonRunning = running
        val target = if (running) CONNECTED_BUTTON_COLOR else color(R.color.slipnet_accent)
        ValueAnimator.ofArgb(connectButtonColor, target).apply {
            duration = 220L
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                connectButtonColor = value
                connectButtonBackground.setColor(value)
            }
            start()
        }
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.slipnet_text_primary))
        }

    private fun row(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }

    private fun fieldParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }

    private fun sectionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }

    private fun compactSectionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadConfig() {
        profiles = ConfigStore.loadProfiles(this)
        activeConfig = ConfigStore.effectiveConfig(this)
    }

    private fun applyConfigToFields(c: Config) {
        if (!editorVisible) return
        domain.setText(c.domain)
        resolverHost.setText(c.resolverHost)
        resolverPort.setText(c.resolverPort.toString())
        resolverMode.setSelection(if (c.resolverMode == Config.ResolverMode.AUTO) 1 else 0)
        resolverTransport.setSelection(if (c.resolverTransport == Config.ResolverTransport.TCP) 1 else 0)
        auth.setSelection(if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) 1 else 0)
        username.setText(c.username)
        password.setText(c.password)
        updateResolverUi()
    }

    private fun selectProfile(profile: ConfigProfile) {
        ConfigStore.setActiveProfile(this, profile.id)
        activeConfig = profile.config
        profiles = ConfigStore.loadProfiles(this)
        setContentView(buildMainUi())
        updateStatus()
    }

    private fun confirmDeleteProfile(profile: ConfigProfile) {
        if (profiles.size <= 1) {
            toast("cannot delete last profile")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage(profile.config.domain.ifBlank { profile.name })
            .setPositiveButton("Delete") { _, _ -> deleteProfile(profile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(profile: ConfigProfile) {
        ConfigStore.deleteProfile(this, profile.id)
        toast("profile deleted")
        showMainScreen()
    }

    private fun saveProfileFromEditor(profile: ConfigProfile?) {
        val config = readConfig()
        if (profile == null) {
            ConfigStore.addProfile(this, profileName.text.toString(), config)
            toast("profile created")
        } else {
            ConfigStore.saveProfile(this, profile.copy(name = profileName.text.toString(), config = config))
            toast("profile saved")
        }
        showMainScreen()
    }

    private fun saveGlobalSettingsFromEditor() {
        val settings = GlobalSettings(
            listenPort = listenPort.text.toString().toIntOrNull() ?: 1080,
            mode = if (mode.selectedItemPosition == 1) Config.Mode.VPN else Config.Mode.PROXY,
            fileLogging = fileLogging.isChecked,
            trafficNotification = trafficNotification.isChecked
        )
        ConfigStore.saveGlobalSettings(this, settings)
        configureNativeLogging()
        activeConfig = ConfigStore.effectiveConfig(this)
        toast("settings saved")
        showMainScreen()
    }

    private fun readConfig(): Config {
        if (!editorVisible) return currentConfig()
        val resolverModeValue = if (resolverMode.selectedItemPosition == 1) {
            Config.ResolverMode.AUTO
        } else {
            Config.ResolverMode.MANUAL
        }
        val host = if (resolverModeValue == Config.ResolverMode.AUTO) {
            currentAutoResolverHost().ifBlank { resolverHost.text.toString().trim() }
        } else {
            resolverHost.text.toString().trim()
        }
        val global = ConfigStore.loadGlobalSettings(this)
        return Config(
            domain = domain.text.toString().trim(),
            resolverHost = host,
            resolverPort = resolverPort.text.toString().toIntOrNull() ?: 53,
            resolverMode = resolverModeValue,
            resolverTransport = if (resolverTransport.selectedItemPosition == 1) {
                Config.ResolverTransport.TCP
            } else {
                Config.ResolverTransport.UDP
            },
            listenPort = global.listenPort,
            mode = global.mode,
            authMode = if (auth.selectedItemPosition == 1) Config.AuthMode.LOGIN_PASSWORD else Config.AuthMode.NO_AUTH,
            username = username.text.toString(),
            password = password.text.toString()
        )
    }

    private fun currentConfig(): Config =
        if (editorVisible && ::domain.isInitialized) {
            readConfig()
        } else {
            activeConfig ?: ConfigStore.effectiveConfig(this)
        }

    private fun toggle() {
        if (proxyStarted || SlipstreamBridge.isRunning() || HevSocks5Tunnel.isRunning()) {
            stopAll()
            return
        }
        val c = readConfig()
        connecting = true
        updateStatus()
        ConfigStore.save(this, c)
        if (c.mode == Config.Mode.VPN) {
            pendingStartVpn = true
            continuePreflight()
        } else {
            startProxy(c)
        }
    }

    private fun continuePreflight() {
        if (!pendingStartVpn) return
        if (requestNotificationsIfNeeded()) return
        if (requestVpnConsentIfNeeded(REQ_VPN)) return
        requestBatteryOptimizationIfNeeded()
        pendingStartVpn = false
        startVpn()
    }

    private fun startProxy(c: Config) {
        connectButton.isEnabled = false
        Thread({
            try {
                resetTrafficBase()
                SlipstreamBridge.setVpnService(null)
                SlipstreamBridge.proxyOnlyMode = true
                val choice = ResolverSelector.choose(this, c, "proxy_start")
                val bridgePort = c.listenPort
                val slipstreamPort = c.listenPort + 1
                SlipstreamBridge.startClient(
                    c.domain,
                    ResolverListConfig(choice.hosts, choice.port, true),
                    slipstreamPort,
                    choice.qnameMtu,
                    c.resolverTransport.name.lowercase()
                ).getOrThrow()
                MiniSlipstreamSocksBridge.start(
                    listenHost = "127.0.0.1",
                    listenPort = bridgePort,
                    slipstreamHost = "127.0.0.1",
                    slipstreamPort = slipstreamPort,
                    dnsHost = choice.selectedHost,
                    username = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.username else null,
                    password = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.password else null
                ).getOrThrow()
                proxyStarted = true
                AppLog.i(
                    TAG,
                    "proxy connected resolver=${choice.selectedHost}:${choice.port} source=${choice.source} " +
                        "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                        "tested=${choice.testedCount} alive=${choice.aliveCount} skipped=${choice.skippedCount}"
                )
            } catch (e: Throwable) {
                AppLog.e(TAG, "proxy start failed", e)
                handler.post { toast(e.message ?: "start failed") }
            } finally {
                handler.post {
                    connecting = false
                    connectButton.isEnabled = true
                    updateStatus()
                }
            }
        }, "proxy-start").start()
    }

    private fun startVpn() {
        try {
            resetTrafficBase()
            AppLog.i(TAG, "start vpn requested")
            val intent = Intent(this, TinyVpnService::class.java).setAction(TinyVpnService.ACTION_START)
            if (ConfigStore.loadGlobalSettings(this).trafficNotification) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            connecting = false
            AppLog.e(TAG, "failed to start vpn service", e)
            toast(e.message ?: "vpn start failed")
        }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        connectButton.isEnabled = false
        proxyStarted = false
        AppLog.i(TAG, "disconnect requested")
        startService(Intent(this, TinyVpnService::class.java).setAction(TinyVpnService.ACTION_STOP))
        Thread({
            try {
                runCatching { MiniSlipstreamSocksBridge.stop() }
                runCatching { SlipstreamBridge.stopClient() }
                runCatching { HevSocks5Tunnel.stop() }
                AppLog.i(TAG, "disconnect cleanup finished")
            } finally {
                handler.post {
                    stopping = false
                    connectButton.isEnabled = true
                    updateStatus()
                }
            }
        }, "disconnect-cleanup").start()
    }

    private fun updateStatus() {
        if (!::connectProgress.isInitialized || !::connectButton.isInitialized) return
        val config = currentConfig()
        val uid = applicationInfo.uid
        val rawRx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0
        val rawTx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
        var rx = rawRx - rxBase
        var tx = rawTx - txBase
        val hev = HevSocks5Tunnel.stats()
        val bridge = MiniSlipstreamSocksBridge.stats()
        val running = SlipstreamBridge.isRunning() || HevSocks5Tunnel.isRunning() || proxyStarted
        if (running) connecting = false
        val resolverProgress = ResolverSelector.lastProgress
        val idle = !running && !connecting && !resolverProgress.active && !stopping
        if (idle) {
            rxBase = rawRx
            txBase = rawTx
            rateRxLast = 0
            rateTxLast = 0
            rateSampleAt = 0
            rx = 0
            tx = 0
        }
        val loading = connecting || resolverProgress.active
        connectProgress.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) connectProgress.bringToFront()
        connectButton.text = when {
            loading -> ""
            running || stopping -> "■"
            else -> "▶"
        }
        updateConnectButtonColor(running)
        updateBottomStatus(running, resolverProgress, rx, tx)
        if (::status.isInitialized) {
            status.text = buildString {
                appendLine("running=$running ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()}")
                appendLine("transport=${config.resolverTransport.name.lowercase()} resolver=authoritative cc=authoritative-fast")
                appendLine("upstream=qname")
                if (config.resolverMode == Config.ResolverMode.AUTO) {
                    appendLine("resolver mode=auto")
                    appendLine(
                        "dns probe ${resolverProgress.tested}/${resolverProgress.total} phase=${resolverProgress.phase.ifBlank { "-" }} " +
                            "alive=${resolverProgress.alive} current=${resolverProgress.currentHost.ifBlank { "-" }} " +
                            "selected=${resolverProgress.selected.ifBlank { "-" }}"
                    )
                    appendLine(
                        "speed probe ${resolverProgress.speedTested}/${resolverProgress.speedTotal} " +
                        "ok=${resolverProgress.speedOk}"
                    )
                } else {
                    appendLine("resolver mode=manual host=${config.resolverHost.ifBlank { "-" }}")
                }
                appendLine("app rx=${formatBytes(rx)} tx=${formatBytes(tx)}")
                appendLine("vpn rx=${formatBytes(hev.rxBytes)} tx=${formatBytes(hev.txBytes)}")
                appendLine("bridge rx=${formatBytes(bridge.rxBytes)} tx=${formatBytes(bridge.txBytes)} ok=${bridge.connectOk}/${bridge.dnsOk} fail=${bridge.connectFail}/${bridge.dnsFail}")
                appendLine("bridge active=${bridge.activeClients} clients=${bridge.clientSockets} remotes=${bridge.remoteSockets} threads=${bridge.threads}")
                SlipstreamBridge.lastError()?.let { appendLine("lastError=$it") }
            }
        }
        val now = System.currentTimeMillis()
        if (running && now - lastLogAt > 5000) {
            lastLogAt = now
            AppLog.i(
                TAG,
                "diag running=$running ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()} " +
                    "appRx=$rx appTx=$tx vpnRx=${hev.rxBytes} vpnTx=${hev.txBytes} " +
                    "bridgeRx=${bridge.rxBytes} bridgeTx=${bridge.txBytes} " +
                    "connectOk=${bridge.connectOk} connectFail=${bridge.connectFail} dnsOk=${bridge.dnsOk} dnsFail=${bridge.dnsFail} " +
                    "bridgeActive=${bridge.activeClients} bridgeClients=${bridge.clientSockets} bridgeRemotes=${bridge.remoteSockets} bridgeThreads=${bridge.threads} " +
                    "dnsProbe=${resolverProgress.tested}/${resolverProgress.total} dnsPhase=${resolverProgress.phase} dnsAlive=${resolverProgress.alive} dnsCurrent=${resolverProgress.currentHost} dnsSelected=${resolverProgress.selected} " +
                    "speedProbe=${resolverProgress.speedTested}/${resolverProgress.speedTotal} speedOk=${resolverProgress.speedOk} " +
                    "lastError=${SlipstreamBridge.lastError() ?: "none"}"
            )
        }
    }

    private fun resetTrafficBase() {
        val uid = applicationInfo.uid
        rxBase = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0
        txBase = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
        rateRxLast = 0
        rateTxLast = 0
        rateSampleAt = 0
    }

    private fun updateBottomStatus(running: Boolean, progress: ResolverSelector.Progress, rx: Long, tx: Long) {
        if (!::bottomStatus.isInitialized || !::trafficStatus.isInitialized) return
        val now = System.currentTimeMillis()
        val elapsedMs = (now - rateSampleAt).takeIf { rateSampleAt != 0L && it > 0 } ?: 1000L
        val downRate = ((rx - rateRxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        val upRate = ((tx - rateTxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        rateRxLast = rx
        rateTxLast = tx
        rateSampleAt = now

        bottomStatus.text = when {
            stopping -> "Disconnecting"
            progress.active -> {
                val total = progress.total.takeIf { it > 0 } ?: progress.speedTotal
                val tested = if (progress.phase == "speed") progress.speedTested else progress.tested
                if (total > 0) "DNS probing $tested/$total" else "DNS probing"
            }
            connecting -> "Connecting"
            running -> "Connected"
            else -> "Not connected"
        }
        trafficStatus.text = "↓ ${formatBytes(rx)} (${formatRate(downRate)})   ↑ ${formatBytes(tx)} (${formatRate(upRate)})"
    }

    private fun fillLocalDns() {
        val hosts = ResolverSelector.localMobileResolvers(this)
            .ifEmpty { ResolverSelector.localDefaultResolvers(this) }
        val host = hosts.firstOrNull()
        if (host == null) {
            toast("no local DNS")
            AppLog.w(TAG, "local DNS fill failed: no local DNS")
            return
        }
        resolverHost.setText(host)
        AppLog.i(TAG, "local DNS filled host=$host all=${hosts.joinToString()}")
    }

    private fun updateResolverUi() {
        if (!editorVisible || !::resolverMode.isInitialized || !::resolverHost.isInitialized) return
        val manual = resolverMode.selectedItemPosition == 0
        resolverHost.isEnabled = manual
        resolverHostContainer.visibility = if (manual) View.VISIBLE else View.GONE
        useLocalDns.visibility = if (manual) View.VISIBLE else View.GONE
    }

    private fun currentAutoResolverHost(): String =
        ResolverSelector.preferredLocalResolver(this).orEmpty()

    private fun handleImportIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.lowercase() != "slipstream") return
        val imported = ConfigStore.importProfile(this, uri)
        if (imported == null) {
            toast("invalid slipstream link")
            AppLog.w(TAG, "invalid slipstream import link: $uri")
            return
        }
        loadConfig()
        setContentView(buildMainUi())
        updateStatus()
        toast("profile imported")
        AppLog.i(TAG, "imported profile id=${imported.id} name=${imported.name}")
    }

    private fun shareLogFile() {
        if (!AppLog.isFileLoggingEnabled(this)) {
            toast("file logging is disabled")
            return
        }
        val file = AppLog.file(this)
        if (!file.exists()) file.writeText("empty log\n")
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share log"))
    }

    private fun showCrashReport() {
        val file = AppLog.crashFile(this)
        markCrashReportSeen(file.length())
        val reportText = if (file.exists() && file.length() > 0) {
            file.readText().takeLast(MAX_CRASH_DIALOG_CHARS)
        } else {
            "No crash report saved yet."
        }
        val reportView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            text = reportText
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply {
            addView(reportView)
        }
        AlertDialog.Builder(this)
            .setTitle("Crash report")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Slipstream crash report", reportText))
                toast("crash report copied")
            }
            .setNeutralButton("Share") { _, _ -> shareCrashReport() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun maybeShowNewCrashReport() {
        val file = AppLog.crashFile(this)
        val size = file.length()
        if (size <= 0L) return
        val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
        if (size <= prefs.getLong(KEY_CRASH_SEEN_SIZE, 0L)) return
        showCrashReport()
    }

    private fun markCrashReportSeen(size: Long) {
        getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            .edit()
            .putLong(KEY_CRASH_SEEN_SIZE, size)
            .apply()
    }

    private fun shareCrashReport() {
        val file = AppLog.crashFile(this)
        if (!file.exists() || file.length() == 0L) file.writeText("No crash report saved yet.\n")
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share crash report"))
    }

    private fun configureNativeLogging() {
        val path = if (AppLog.isFileLoggingEnabled(this)) AppLog.file(this).absolutePath else ""
        SlipstreamBridge.setLogFilePath(path)
        HevSocks5Tunnel.setCrashLogPath(AppLog.crashFile(this).absolutePath)
    }

    private fun runStartupPermissionFlow() {
        if (startupPermissionFlowActive) return
        startupPermissionFlowActive = true
        continueStartupPermissionFlow()
    }

    private fun continueStartupPermissionFlow() {
        if (!startupPermissionFlowActive || pendingStartVpn) return
        if (requestNotificationsIfNeeded()) return
        if (requestVpnConsentIfNeeded(REQ_VPN_STARTUP)) return
        if (requestBatteryOptimizationIfNeeded()) return
        maybeShowBackgroundSettingsPrompt()
        startupPermissionFlowActive = false
    }

    private fun requestNotificationsIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_NOTIFICATIONS_PROMPTED, false)) return false
            if (notificationRequestShown) return false
            notificationRequestShown = true
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_PROMPTED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return true
        }
        return false
    }

    private fun requestVpnConsentIfNeeded(requestCode: Int): Boolean {
        val intent = VpnService.prepare(this) ?: return false
        if (requestCode == REQ_VPN_STARTUP) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_VPN_STARTUP_PROMPTED, false)) return false
            if (startupVpnRequestShown) return false
            startupVpnRequestShown = true
            prefs.edit().putBoolean(KEY_VPN_STARTUP_PROMPTED, true).apply()
        }
        startActivityForResult(intent, requestCode)
        return true
    }

    private fun requestBatteryOptimizationIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return false
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) return false
        if (batteryRequestShown) return false
        batteryRequestShown = true
        prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        return runCatching {
            startActivityForResult(intent, REQ_BATTERY)
            true
        }.getOrElse {
            AppLog.w(TAG, "battery optimization request failed: ${it.message}")
            openAppBatterySettings()
            true
        }
    }

    private fun maybeShowBackgroundSettingsPrompt() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BACKGROUND_PROMPTED, false)) return
        AlertDialog.Builder(this)
            .setTitle("Background work")
            .setMessage("Allow Slipstream CLI to keep working in the background if your Android skin shows such an option.")
            .setPositiveButton("Open settings") { _, _ ->
                markBackgroundSettingsPrompted()
                openAppBatterySettings()
            }
            .setNegativeButton("Later") { _, _ -> markBackgroundSettingsPrompted() }
            .setOnCancelListener { markBackgroundSettingsPrompted() }
            .show()
    }

    private fun openAppBatterySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in intents) {
            if (runCatching { startActivityForResult(intent, REQ_BACKGROUND_SETTINGS); true }.getOrDefault(false)) {
                return
            }
        }
    }

    private fun markBackgroundSettingsPrompted() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_PROMPTED, true)
            .apply()
    }

    private fun formatBytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB"
            v >= 1024L -> "${v / 1024L} KiB"
            else -> "$v B"
        }
    }

    private fun hideKeyboardIfEditing(): Boolean {
        val focused = currentFocus as? EditText ?: return false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
        return true
    }

    private fun formatRate(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB/s"
            v >= 1024L -> "${v / 1024L} KiB/s"
            else -> "$v B/s"
        }
    }

    private fun maskDomain(value: String): String {
        val clean = value.trim()
        if (clean.length <= 6) return clean
        val dot = clean.indexOf('.').takeIf { it > 2 } ?: (clean.length - 3)
        val prefix = clean.take(3)
        val suffix = clean.drop(dot.coerceAtMost(clean.length - 1))
        return "$prefix***$suffix"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_VPN = 100
        private const val REQ_VPN_STARTUP = 101
        private const val REQ_BATTERY = 102
        private const val REQ_BACKGROUND_SETTINGS = 103
        private const val REQ_NOTIFICATIONS = 104
        private const val PREFS = "permission_flow"
        private const val KEY_BACKGROUND_PROMPTED = "background_prompted"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"
        private const val KEY_NOTIFICATIONS_PROMPTED = "notifications_prompted"
        private const val KEY_VPN_STARTUP_PROMPTED = "vpn_startup_prompted"
        private const val MAX_CRASH_DIALOG_CHARS = 64 * 1024
        private const val CRASH_PREFS = "crash_report"
        private const val KEY_CRASH_SEEN_SIZE = "seen_size"
        private val CONNECTED_BUTTON_COLOR = android.graphics.Color.rgb(72, 132, 82)
    }
}
