package app.vaydns

import android.animation.ValueAnimator
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateDecelerateInterpolator
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
    private lateinit var resolverMode: LinearLayout
    private lateinit var resolverTransport: LinearLayout
    private lateinit var resolverTransportContainer: View
    private lateinit var resolverPathMode: LinearLayout
    private lateinit var useLocalDns: Button
    private lateinit var listenPort: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var mode: Spinner
    private lateinit var auth: LinearLayout
    private lateinit var dnsLabelLengthField: EditText
    private lateinit var maxPollQpsField: EditText
    private lateinit var fileLogging: CheckBox
    private lateinit var trafficNotification: CheckBox
    private lateinit var localSocksAuth: CheckBox
    private lateinit var localSocksUsername: EditText
    private lateinit var localSocksPassword: EditText
    private lateinit var profileName: EditText
    // The config the editor was opened with (profile's own config, or active/default for a new
    // profile). Used to preserve fields that have no editor UI (e.g. dnsQueryType) across saves,
    // so editing unrelated fields doesn't silently reset them to their defaults.
    private var editingBaseConfig: Config? = null
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
    private var drawerOpen = false
    private var currentSectionTitle = "Home"
    private var drawerEdge: View? = null
    private var drawerScrim: View? = null
    private var drawerPanel: View? = null
    private var drawerWidth = 0
    private var drawerTouchStartX = 0f
    private var drawerTouchStartY = 0f
    private var drawerTouchStartTranslation = 0f
    private var drawerDragging = false
    private var drawerGlobalSwipeCandidate = false
    private var drawerGlobalSwipeActive = false
    private var closeDrawerAfterBuild = false
    private var suppressGlobalSettingsSave = false
    private var screenAnimating = false
    private val screenTransitionInterpolator = AccelerateDecelerateInterpolator()
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
    private var connectStartedAt = 0L
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleGlobalDrawerSwipe(event)) return true
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (hideKeyboardIfEditing()) return
        if (drawerOpen) {
            closeDrawer()
            return
        }
        if (editorVisible) {
            showMainScreen(ScreenTransition.BACK)
        } else if (settingsVisible) {
            showMainScreen(ScreenTransition.BACK)
        } else if (diagnosticsVisible) {
            showMainScreen(ScreenTransition.BACK)
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

    private enum class ScreenTransition {
        FORWARD,
        BACK,
        NONE
    }

    private fun navigateTo(view: View, transition: ScreenTransition = ScreenTransition.FORWARD) {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val old = if (content.childCount > 0) content.getChildAt(content.childCount - 1) else null
        if (old == null || transition == ScreenTransition.NONE) {
            setContentView(view)
            requestInsets(findViewById(android.R.id.content))
            return
        }
        old.animate().cancel()
        view.animate().cancel()
        if (screenAnimating && content.childCount > 1) {
            for (i in content.childCount - 2 downTo 0) {
                content.removeViewAt(i)
            }
        }
        screenAnimating = true
        val distance = (content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        val direction = if (transition == ScreenTransition.BACK) -1f else 1f
        view.translationX = distance * direction
        view.alpha = 1f
        content.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        requestInsets(content)
        old.animate()
            .translationX(-distance * direction)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(screenTransitionInterpolator)
            .start()
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(230L)
            .setInterpolator(screenTransitionInterpolator)
            .withEndAction {
                content.removeView(old)
                old.translationX = 0f
                old.alpha = 1f
                screenAnimating = false
            }
            .start()
    }

    private fun requestInsets(view: View) {
        if (Build.VERSION.SDK_INT >= 20) {
            view.requestApplyInsets()
        }
        view.post {
            if (Build.VERSION.SDK_INT >= 20) {
                view.requestApplyInsets()
            }
        }
    }

    private fun buildMainUi(): View {
        persistGlobalSettingsIfVisible()
        editorVisible = false
        settingsVisible = false
        diagnosticsVisible = false
        currentSectionTitle = "Home"

        val frame = FrameLayout(this).apply {
            id = R.id.main_content
            setBackgroundColor(color(R.color.slipnet_bg))
        }
        val root = screenRoot().apply {
            setPadding(dp(10), 0, dp(10), dp(82))
        }
        root.addView(topBar("Home", showAdd = true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(profileList(), compactSectionParams())
        val scroll = scrollScreen(root).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                root.setPadding(
                    dp(10),
                    dp(4) + insets.systemWindowInsetTop,
                    dp(10),
                    dp(82) + insets.systemWindowInsetBottom
                )
                insets
            }
        }
        frame.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(bottomConnectBar(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM))
        frame.addView(connectButtonOverlay(), FrameLayout.LayoutParams(dp(82), dp(82), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(20)
            bottomMargin = dp(38)
        })
        addDrawer(frame, "Home")
        root.requestFocus()
        return frame
    }

    private fun topBar(title: String, showAdd: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 60dp (up from 52dp) for a bigger tap target; iconButton uses ScaleType.CENTER so the
            // drawn icon stays the same visual size, only the invisible touch area grows.
            // Static background (like the back button in topBarBack): no press-color block fill.
            addView(iconButton(R.drawable.ic_menu, "Menu").apply {
                id = R.id.global_settings_button
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button_static)
                stateListAnimator = null
                isHapticFeedbackEnabled = false
                setOnClickListener { openDrawer() }
            }, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_text_primary))
                setSingleLine(true)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(6)
            })
            if (showAdd) {
                addView(iconButton(R.drawable.ic_add, "New profile").apply {
                    id = R.id.add_profile_button
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button_static)
                    stateListAnimator = null
                    isHapticFeedbackEnabled = false
                    setOnClickListener { showProfileEditor(null) }
                }, LinearLayout.LayoutParams(dp(60), dp(60)))
            }
        }

    private fun topBarBack(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_arrow_back, "Back").apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button_static)
                stateListAnimator = null
                isHapticFeedbackEnabled = false
                setOnClickListener { showMainScreen(ScreenTransition.BACK) }
            }, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_text_primary))
                setSingleLine(true)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(6)
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

    private fun addDrawer(frame: FrameLayout, selected: String) {
        drawerOpen = false
        val diagnosticsEnabled = ConfigStore.loadGlobalSettings(this).fileLogging
        val width = (resources.displayMetrics.widthPixels * 0.82f).toInt().coerceAtMost(dp(320))
        drawerWidth = width
        val edge = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(135, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(color(R.color.slipnet_card))
            // dp(58) top padding used to compensate for content drawing under a transparent
            // status bar in forced edge-to-edge mode; now that the window fits system windows
            // again (see AppTheme's windowOptOutEdgeToEdgeEnforcement), the status bar area is
            // already excluded from this layout, so that offset would double-count.
            setPadding(dp(18), dp(18), dp(18), dp(18))
            translationX = -width.toFloat()
            addView(TextView(this@MainActivity).apply {
                text = "SlipstreamCLI"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_text_primary))
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(drawerItem("Home", selected == "Home") { showMainScreen() }, drawerItemParams(first = true))
            if (diagnosticsEnabled) {
                addView(drawerItem("Diagnostics", selected == "Diagnostics") { showDiagnostics() }, drawerItemParams())
            }
            addView(drawerItem("Settings", selected == "Settings") { showGlobalSettings() }, drawerItemParams())
        }
        drawerScrim = scrim
        drawerPanel = panel
        drawerEdge = edge
        installDrawerEdgeSwipe(edge)
        installDrawerPanelDrag(panel)
        frame.addView(edge, FrameLayout.LayoutParams(dp(28), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        frame.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(panel, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        if (closeDrawerAfterBuild) {
            closeDrawerAfterBuild = false
        }
    }

    override fun onPause() {
        persistGlobalSettingsIfVisible()
        super.onPause()
    }

    private fun drawerItem(text: String, selected: Boolean, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            background = drawerItemBackground(selected)
            installDrawerItemTouch(this, selected, action)
            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 18f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(color(R.color.slipnet_text_primary))
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

    private fun installDrawerItemTouch(item: View, selected: Boolean, action: () -> Unit) {
        var itemDragging = false
        var downY = 0f
        item.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!drawerOpen) return@setOnTouchListener false
                    drawerDragging = false
                    itemDragging = false
                    drawerTouchStartX = event.rawX
                    downY = event.rawY
                    drawerTouchStartTranslation = drawerPanel?.translationX ?: 0f
                    drawerPanel?.animate()?.cancel()
                    drawerScrim?.animate()?.cancel()
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - drawerTouchStartX
                    val dy = kotlin.math.abs(event.rawY - downY)
                    if (!itemDragging && kotlin.math.abs(dx) > dp(8) && kotlin.math.abs(dx) > dy) {
                        itemDragging = true
                        drawerDragging = true
                        view.isPressed = false
                    }
                    if (!itemDragging) return@setOnTouchListener true
                    val panel = drawerPanel ?: return@setOnTouchListener true
                    val width = drawerWidth.takeIf { it > 0 } ?: panel.width
                    val tx = (drawerTouchStartTranslation + dx).coerceIn(-width.toFloat(), 0f)
                    panel.translationX = tx
                    val progress = 1f - (-tx / width.toFloat())
                    drawerScrim?.alpha = progress
                    setDrawerContentShift(drawerContentShift() * progress)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (itemDragging || drawerDragging) {
                        itemDragging = false
                        drawerDragging = false
                        val panel = drawerPanel ?: return@setOnTouchListener true
                        val width = drawerWidth.takeIf { it > 0 } ?: panel.width
                        if (shouldStayOpenWhileClosing(panel.translationX, width)) openDrawer() else closeDrawer()
                    } else {
                        persistGlobalSettingsIfVisible()
                        if (selected) {
                            closeDrawer()
                        } else {
                            markDrawerItemSelected(view)
                            val overlay = promoteDrawerToOverlay()
                            closeDrawerAfterBuild = true
                            action()
                            animateCurrentScreenBackFromDrawer()
                            overlay?.close()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (itemDragging || drawerDragging) {
                        itemDragging = false
                        drawerDragging = false
                        openDrawer()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun markDrawerItemSelected(item: View) {
        val parent = item.parent as? ViewGroup
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is LinearLayout && child !== item) {
                    child.background = drawerItemBackground(false)
                    (child.getChildAt(0) as? TextView)?.typeface = Typeface.DEFAULT
                }
            }
        }
        item.background = drawerItemBackground(true)
        ((item as? ViewGroup)?.getChildAt(0) as? TextView)?.typeface = Typeface.DEFAULT_BOLD
    }

    private data class DrawerOverlay(
        val parent: ViewGroup,
        val scrim: View,
        val panel: View,
        val width: Int
    ) {
        fun close() {
            parent.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            parent.addView(panel, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
            scrim.animate().cancel()
            panel.animate().cancel()
            scrim.animate().alpha(0f).setDuration(110L).withEndAction {
                parent.removeView(scrim)
            }.start()
            panel.animate().translationX(-panel.width.toFloat()).setDuration(130L).withEndAction {
                parent.removeView(panel)
            }.start()
        }
    }

    private fun promoteDrawerToOverlay(): DrawerOverlay? {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return null
        val scrim = drawerScrim ?: return null
        val panel = drawerPanel ?: return null
        drawerEdge?.let { edge ->
            (edge.parent as? ViewGroup)?.removeView(edge)
        }
        (scrim.parent as? ViewGroup)?.removeView(scrim)
        (panel.parent as? ViewGroup)?.removeView(panel)
        scrim.visibility = View.VISIBLE
        scrim.alpha = 1f
        panel.translationX = 0f
        drawerOpen = false
        drawerEdge = null
        drawerScrim = null
        drawerPanel = null
        return DrawerOverlay(content, scrim, panel, drawerWidth.takeIf { it > 0 } ?: panel.width)
    }

    private fun drawerItemBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(color(if (selected) R.color.slipnet_card_soft else R.color.slipnet_card))
        }

    private fun drawerItemParams(first: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(if (first) 24 else 6)
        }

    private fun openDrawer() {
        val scrim = drawerScrim ?: return
        val panel = drawerPanel ?: return
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(130L).start()
        panel.animate().translationX(0f).setDuration(150L).start()
        animateDrawerContentShift(drawerContentShift(), 150L)
    }

    private fun closeDrawer() {
        val scrim = drawerScrim ?: return
        val panel = drawerPanel ?: return
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(110L).withEndAction {
            scrim.visibility = View.GONE
        }.start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(130L).start()
        animateDrawerContentShift(0f, 130L)
    }

    private fun drawerContentShift(): Float = dp(34).toFloat()

    // Used when the drawer starts CLOSED (edge/global swipe-to-open gestures): opens once the
    // panel has been dragged in by more than 20% of its width.
    private fun shouldOpenDrawer(translationX: Float, width: Int): Boolean =
        translationX > -width * 0.8f

    // Used when the drawer starts OPEN (drag-to-close gestures): stays open only while still
    // shown by more than 80% (i.e. closes once dragged out by just 20%), so the open and close
    // triggers feel symmetric instead of requiring an 80% drag to close.
    private fun shouldStayOpenWhileClosing(translationX: Float, width: Int): Boolean =
        translationX > -width * 0.2f

    private fun setDrawerContentShift(value: Float) {
        val frame = (drawerPanel?.parent as? ViewGroup) ?: return
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child !== drawerEdge && child !== drawerScrim && child !== drawerPanel) {
                child.animate().cancel()
                child.translationX = value
            }
        }
    }

    private fun animateDrawerContentShift(value: Float, duration: Long) {
        val frame = (drawerPanel?.parent as? ViewGroup) ?: return
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child !== drawerEdge && child !== drawerScrim && child !== drawerPanel) {
                child.animate().translationX(value).setDuration(duration).start()
            }
        }
    }

    private fun animateCurrentScreenBackFromDrawer() {
        val frame = drawerPanel?.parent as? ViewGroup ?: return
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child !== drawerEdge && child !== drawerScrim && child !== drawerPanel) {
                child.animate().cancel()
                child.translationX = drawerContentShift()
                child.animate().translationX(0f).setDuration(130L).start()
            }
        }
    }

    private fun handleGlobalDrawerSwipe(event: MotionEvent): Boolean {
        if (!isDrawerAllowedOnCurrentScreen() || drawerOpen || drawerPanel == null || drawerScrim == null || screenAnimating) {
            drawerGlobalSwipeCandidate = false
            drawerGlobalSwipeActive = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerGlobalSwipeCandidate = true
                drawerGlobalSwipeActive = false
                drawerTouchStartX = event.rawX
                drawerTouchStartY = event.rawY
                drawerTouchStartTranslation = -(drawerWidth.takeIf { it > 0 } ?: dp(320)).toFloat()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawerGlobalSwipeCandidate) return false
                val dx = event.rawX - drawerTouchStartX
                val dy = kotlin.math.abs(event.rawY - drawerTouchStartY)
                if (!drawerGlobalSwipeActive) {
                    if (dx <= dp(14) || kotlin.math.abs(dx) <= dy * 1.2f) return false
                    drawerGlobalSwipeActive = true
                    drawerDragging = true
                    drawerScrim?.visibility = View.VISIBLE
                    drawerPanel?.animate()?.cancel()
                    drawerScrim?.animate()?.cancel()
                    drawerPanel?.translationX = drawerTouchStartTranslation
                    drawerScrim?.alpha = 0f
                    setDrawerContentShift(0f)
                }
                val width = drawerWidth.takeIf { it > 0 } ?: (drawerPanel?.width ?: dp(320))
                val tx = (drawerTouchStartTranslation + dx).coerceIn(-width.toFloat(), 0f)
                drawerPanel?.translationX = tx
                val progress = 1f - (-tx / width.toFloat())
                drawerScrim?.alpha = progress
                setDrawerContentShift(drawerContentShift() * progress)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!drawerGlobalSwipeActive) {
                    drawerGlobalSwipeCandidate = false
                    return false
                }
                drawerGlobalSwipeCandidate = false
                drawerGlobalSwipeActive = false
                drawerDragging = false
                val width = drawerWidth.takeIf { it > 0 } ?: (drawerPanel?.width ?: dp(320))
                if (shouldOpenDrawer(drawerPanel?.translationX ?: -width.toFloat(), width)) {
                    openDrawer()
                } else {
                    closeDrawer()
                }
                return true
            }
        }
        return false
    }

    private fun isDrawerAllowedOnCurrentScreen(): Boolean =
        !editorVisible && (currentSectionTitle == "Home" || currentSectionTitle == "Diagnostics" || currentSectionTitle == "Settings")

    private fun installDrawerEdgeSwipe(edge: View) {
        edge.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (drawerOpen) return@setOnTouchListener false
                    drawerDragging = true
                    drawerTouchStartX = event.rawX
                    drawerTouchStartTranslation = -(drawerWidth.takeIf { it > 0 } ?: dp(320)).toFloat()
                    drawerScrim?.visibility = View.VISIBLE
                    drawerPanel?.animate()?.cancel()
                    drawerScrim?.animate()?.cancel()
                    drawerPanel?.translationX = drawerTouchStartTranslation
                    drawerScrim?.alpha = 0f
                    setDrawerContentShift(0f)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!drawerDragging) return@setOnTouchListener false
                    val width = drawerWidth.takeIf { it > 0 } ?: (drawerPanel?.width ?: dp(320))
                    val tx = (drawerTouchStartTranslation + event.rawX - drawerTouchStartX).coerceIn(-width.toFloat(), 0f)
                    drawerPanel?.translationX = tx
                    val progress = 1f - (-tx / width.toFloat())
                    drawerScrim?.alpha = progress
                    setDrawerContentShift(drawerContentShift() * progress)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!drawerDragging) return@setOnTouchListener false
                    drawerDragging = false
                    val width = drawerWidth.takeIf { it > 0 } ?: (drawerPanel?.width ?: dp(320))
                    if (shouldOpenDrawer(drawerPanel?.translationX ?: -width.toFloat(), width)) {
                        openDrawer()
                    } else {
                        closeDrawer()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun installDrawerPanelDrag(panel: View) {
        panel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!drawerOpen) return@setOnTouchListener false
                    drawerDragging = true
                    drawerTouchStartX = event.rawX
                    drawerTouchStartTranslation = panel.translationX
                    panel.animate().cancel()
                    drawerScrim?.animate()?.cancel()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!drawerDragging || !drawerOpen) return@setOnTouchListener false
                    val width = drawerWidth.takeIf { it > 0 } ?: panel.width
                    val tx = (drawerTouchStartTranslation + event.rawX - drawerTouchStartX).coerceIn(-width.toFloat(), 0f)
                    panel.translationX = tx
                    val progress = 1f - (-tx / width.toFloat())
                    drawerScrim?.alpha = progress
                    setDrawerContentShift(drawerContentShift() * progress)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!drawerDragging || !drawerOpen) return@setOnTouchListener false
                    drawerDragging = false
                    val width = drawerWidth.takeIf { it > 0 } ?: panel.width
                    if (shouldStayOpenWhileClosing(panel.translationX, width)) openDrawer() else closeDrawer()
                    true
                }
                else -> false
            }
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
            // Bigger than the old 36dp (below Android's 48dp touch-target guideline); the icon stays
            // visually the same size (iconButton uses ScaleType.CENTER, so growing the box only grows
            // the invisible tap area, not the drawn icon).
            addView(edit, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(delete, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        }

    private fun showProfileEditor(profile: ConfigProfile?) {
        val config = profile?.config ?: activeConfig ?: ConfigStore.load(this)
        editingBaseConfig = config
        editorVisible = true
        settingsVisible = false
        diagnosticsVisible = false
        currentSectionTitle = if (profile == null) "New Profile" else "Edit Profile"
        navigateTo(buildProfileEditorUi(profile, config), ScreenTransition.FORWARD)
        applyConfigToFields(config)
        updateStatus()
    }

    private fun buildProfileEditorUi(profile: ConfigProfile?, config: Config): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.slipnet_bg))
        }
        val root = screenRoot()
        root.setPadding(dp(10), 0, dp(10), dp(82))
        root.addView(
            topBarBack(if (profile == null) "New Profile" else "Edit Profile"),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
        )

        profileName = edit("profile name").apply {
            id = R.id.profile_name
            setText(profile?.name ?: config.domain.ifBlank { "New profile" })
        }
        domain = edit("domain").apply { id = R.id.domain_field }
        resolverHost = edit("resolver host").apply { id = R.id.resolver_host_field }
        // Ghost/link style (transparent, accent-colored text) instead of a full secondary button --
        // a compact inline "quick-fill" action next to the resolver field.
        useLocalDns = button("LOCAL").apply {
            id = R.id.use_local_dns_button
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button_static)
            setTextColor(color(R.color.slipnet_accent))
            stateListAnimator = null
            isHapticFeedbackEnabled = false
            setOnClickListener { fillLocalDns() }
        }
        val resolverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(resolverHost, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(useLocalDns, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                leftMargin = dp(4)
            })
        }
        resolverPort = edit("resolver port", InputType.TYPE_CLASS_NUMBER).apply { id = R.id.resolver_port_field }
        resolverMode = pillSelector(listOf("manual dns", "auto dns")) { updateResolverUi() }.apply {
            id = R.id.resolver_mode_spinner
        }
        resolverTransport = pillSelector(listOf("udp", "tcp")).apply { id = R.id.resolver_transport_spinner }
        resolverPathMode = pillSelector(listOf("recursive", "authoritative")).apply { id = R.id.resolver_path_mode_spinner }
        auth = pillSelector(listOf("no-auth", "login/password")).apply { id = R.id.auth_spinner }
        username = edit("username").apply { id = R.id.username_field }
        password = edit("password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            id = R.id.password_field
        }
        dnsLabelLengthField = edit("dns label length", InputType.TYPE_CLASS_NUMBER).apply {
            id = R.id.dns_label_length_field
        }
        maxPollQpsField = edit("max poll qps", InputType.TYPE_CLASS_NUMBER).apply {
            id = R.id.max_poll_qps_field
        }

        root.addView(labeledField("Profile name", profileName), fieldParams())
        root.addView(labeledField("Domain", domain), fieldParams())

        root.addView(sectionTitle("DNS Resolver"), sectionParams())
        root.addView(labeledField("DNS mode", resolverMode), fieldParams())
        resolverHostContainer = labeledField("Resolver host", resolverRow)
        root.addView(resolverHostContainer, fieldParams())
        resolverTransportContainer = labeledField("Transport", resolverTransport)
        root.addView(
            row(labeledField("Resolver port", resolverPort), resolverTransportContainer),
            fieldParams()
        )
        root.addView(labeledField("DNS path mode", resolverPathMode), fieldParams())

        root.addView(sectionTitle("Authentication"), sectionParams())
        root.addView(labeledField("Auth mode", auth), fieldParams())
        root.addView(labeledField("Username", username), fieldParams())
        root.addView(labeledField("Password", password), fieldParams())

        root.addView(sectionTitle("Advanced (client-only)"), sectionParams())
        root.addView(
            hintText("These only shape this device's own traffic; the server does not need to match them."),
            fieldParams()
        )
        root.addView(labeledField("DNS label length", dnsLabelLengthField), fieldParams())
        root.addView(
            hintText("1-63, default 57. Length of each DNS label in the encoded query."),
            compactSectionParams()
        )
        root.addView(labeledField("Max poll rate (queries/sec)", maxPollQpsField), fieldParams())
        root.addView(
            hintText("0 = unlimited (default). Caps how many DNS queries/sec this device sends."),
            compactSectionParams()
        )

        if (profile != null) {
            root.addView(button("DELETE PROFILE").apply {
                id = R.id.delete_profile_button
                setOnClickListener { confirmDeleteProfile(profile) }
            }, sectionParams())
        }

        root.requestFocus()
        val scroll = scrollScreen(root).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                val bottomInset = insets.systemWindowInsetBottom
                root.setPadding(
                    dp(10),
                    dp(4) + insets.systemWindowInsetTop,
                    dp(10),
                    dp(82) + bottomInset
                )
                insets
            }
        }
        frame.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(profileEditorActionBar(profile), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(70), Gravity.BOTTOM))
        addDrawer(frame, "Home")
        return frame
    }

    private fun profileEditorActionBar(profile: ConfigProfile?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_bottom_bar)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnApplyWindowInsetsListener { view, insets ->
                val bottomInset = insets.systemWindowInsetBottom
                view.setPadding(dp(16), dp(8), dp(16), dp(8) + bottomInset)
                view.layoutParams = view.layoutParams.apply {
                    height = dp(70) + bottomInset
                }
                insets
            }
            addView(button(if (profile == null) "CREATE PROFILE" else "SAVE PROFILE", primary = true).apply {
                id = R.id.save_config_button
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary_static)
                stateListAnimator = null
                isHapticFeedbackEnabled = false
                setOnClickListener { saveProfileFromEditor(profile) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
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
            isVerticalScrollBarEnabled = false
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

    private fun compactScrollScreen(root: LinearLayout, bottomPadding: Int): ScrollView =
        scrollScreen(root).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                root.setPadding(
                    dp(10),
                    dp(4) + insets.systemWindowInsetTop,
                    dp(10),
                    bottomPadding + insets.systemWindowInsetBottom
                )
                insets
            }
        }

    private fun showMainScreen(transition: ScreenTransition = ScreenTransition.FORWARD) {
        editorVisible = false
        settingsVisible = false
        diagnosticsVisible = false
        loadConfig()
        val actualTransition = if (closeDrawerAfterBuild) ScreenTransition.NONE else transition
        navigateTo(buildMainUi(), actualTransition)
        updateStatus()
    }

    private fun showDiagnostics(transition: ScreenTransition = ScreenTransition.FORWARD) {
        if (!ConfigStore.loadGlobalSettings(this).fileLogging) {
            showMainScreen(ScreenTransition.NONE)
            return
        }
        editorVisible = false
        settingsVisible = false
        diagnosticsVisible = true
        currentSectionTitle = "Diagnostics"
        val actualTransition = if (closeDrawerAfterBuild) ScreenTransition.NONE else transition
        navigateTo(buildDiagnosticsUi(), actualTransition)
        updateStatus()
    }

    private fun buildDiagnosticsUi(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.slipnet_bg))
        }
        val root = screenRoot()
        root.setPadding(dp(10), 0, dp(10), dp(24))
        root.addView(topBar("Diagnostics", showAdd = false), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        status = TextView(this).apply {
            id = R.id.status_text
            textSize = 12f
            setLineSpacing(0f, 1.05f)
            setTextIsSelectable(true)
            setTextColor(color(R.color.slipnet_text_secondary))
        }
        root.addView(card().apply {
            addView(status, fieldParams())
            addView(row(
                button("SHARE LOG").apply {
                    id = R.id.share_log_button
                    setOnClickListener { shareLogFile() }
                },
                button("CRASH REPORT").apply {
                    id = R.id.crash_report_button
                    setOnClickListener { showCrashReport() }
                }
            ), fieldParams())
        }, sectionParams())
        frame.addView(compactScrollScreen(root, dp(24)), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addDrawer(frame, "Diagnostics")
        return frame
    }

    private fun bottomConnectBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_bottom_bar)
            setPadding(dp(16), dp(8), dp(112), dp(8))
            setOnApplyWindowInsetsListener { view, insets ->
                val bottomInset = insets.systemWindowInsetBottom
                view.setPadding(dp(16), dp(8), dp(112), dp(8) + bottomInset)
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
            addView(statusColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun connectButtonOverlay(): FrameLayout =
        FrameLayout(this).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.bottomMargin = dp(38) + insets.systemWindowInsetBottom
                    view.layoutParams = params
                }
                insets
            }
            connectButton = Button(this@MainActivity).apply {
                id = R.id.connect_button
                val initialRunning = isTunnelRunning()
                val initialLoading = ResolverSelector.lastProgress.active || connecting
                text = if (initialRunning) "■" else "▶"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.slipnet_button_text_primary))
                connectButtonColor = if (initialRunning && !initialLoading) CONNECTED_BUTTON_COLOR else color(R.color.slipnet_accent)
                connectButtonRunning = initialRunning && !initialLoading
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
            addView(connectButton, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
            addView(connectProgress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }

    private fun showGlobalSettings(transition: ScreenTransition = ScreenTransition.FORWARD) {
        editorVisible = false
        settingsVisible = true
        diagnosticsVisible = false
        currentSectionTitle = "Settings"
        suppressGlobalSettingsSave = true
        val actualTransition = if (closeDrawerAfterBuild) ScreenTransition.NONE else transition
        navigateTo(buildGlobalSettingsUi(), actualTransition)
        val global = ConfigStore.loadGlobalSettings(this)
        listenPort.setText(global.listenPort.toString())
        mode.setSelection(if (global.mode == Config.Mode.VPN) 1 else 0)
        fileLogging.isChecked = global.fileLogging
        trafficNotification.isChecked = global.trafficNotification
        localSocksAuth.isChecked = global.localSocksAuthEnabled
        localSocksUsername.setText(global.localSocksUsername)
        localSocksPassword.setText(global.localSocksPassword)
        updateLocalSocksAuthUi()
        suppressGlobalSettingsSave = false
        installGlobalSettingsAutoSave()
    }

    private fun buildGlobalSettingsUi(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.slipnet_bg))
        }
        val root = screenRoot()
        root.setPadding(dp(10), 0, dp(10), dp(24))
        root.addView(topBar("Settings", showAdd = false), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        listenPort = edit("local port", InputType.TYPE_CLASS_NUMBER).apply { id = R.id.listen_port_field }
        mode = spinner(listOf("proxy", "vpn")).apply { id = R.id.mode_spinner }
        fileLogging = debugLogCheckbox()
        trafficNotification = CheckBox(this).apply {
            text = "Show traffic notification"
            textSize = 14f
            setTextColor(color(R.color.slipnet_text_secondary))
            buttonTintList = ColorStateList.valueOf(color(R.color.slipnet_accent))
        }
        localSocksAuth = CheckBox(this).apply {
            text = "Protect local SOCKS"
            textSize = 14f
            setTextColor(color(R.color.slipnet_text_secondary))
            buttonTintList = ColorStateList.valueOf(color(R.color.slipnet_accent))
        }
        localSocksUsername = edit("socks username").apply { id = R.id.local_socks_username_field }
        localSocksPassword = edit("socks password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).apply {
            id = R.id.local_socks_password_field
        }
        root.addView(card().apply {
            addView(labeledField("Local port", listenPort), fieldParams())
            addView(labeledField("Connection mode", mode), fieldParams())
            addView(fileLogging, fieldParams())
            addView(trafficNotification, fieldParams())
            addView(localSocksAuth, fieldParams())
            addView(labeledField("SOCKS username", localSocksUsername), fieldParams())
            addView(labeledField("SOCKS password", localSocksPassword), fieldParams())
        }, sectionParams())
        root.requestFocus()
        frame.addView(compactScrollScreen(root, dp(24)), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addDrawer(frame, "Settings")
        return frame
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
            addView(status, fieldParams())
        }, sectionParams())
    }

    private fun debugLogCheckbox(): CheckBox =
        CheckBox(this).apply {
            id = R.id.file_logging_checkbox
            text = "Enable debug mode"
            textSize = 14f
            setTextColor(color(R.color.slipnet_text_secondary))
            buttonTintList = ColorStateList.valueOf(color(R.color.slipnet_accent))
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

    private fun backButton(): Button =
        button("BACK").apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_secondary_static)
            stateListAnimator = null
            isHapticFeedbackEnabled = false
        }

    private fun iconButton(iconRes: Int, description: String): ImageButton =
        ImageButton(this).apply {
            contentDescription = description
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color(R.color.slipnet_text_secondary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
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

    // Small gray description line placed under a field/section, matching e.g. "Server's Noise
    // protocol public key in hex format" style hint text.
    private fun hintText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(color(R.color.slipnet_text_muted))
        }

    private fun pillBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            if (selected) {
                setColor(color(R.color.slipnet_accent))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), color(R.color.slipnet_stroke))
            }
        }

    // Segmented "pill" selector replacing Spinner for short enum-like choices (transport, auth
    // mode, etc.), matching the reference design's UDP/TCP/DoT/DoH style row. Selection state is
    // kept in the row's own `tag` (an Int index) and read/written via the extension functions
    // below, mirroring Spinner's selectedItemPosition/setSelection so call sites stay simple.
    private fun pillSelector(options: List<String>, onChange: ((Int) -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = 0
            options.forEachIndexed { index, label ->
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    isClickable = true
                    isFocusable = true
                    background = pillBackground(index == 0)
                    setTextColor(color(if (index == 0) R.color.slipnet_button_text_primary else R.color.slipnet_text_primary))
                    setOnClickListener {
                        val row = parent as LinearLayout
                        row.setPillSelectedIndex(row.indexOfChild(this))
                        onChange?.invoke(row.pillSelectedIndex())
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                })
            }
        }

    private fun LinearLayout.pillSelectedIndex(): Int = (tag as? Int) ?: 0

    private fun LinearLayout.setPillSelectedIndex(index: Int) {
        if (index < 0 || index >= childCount) return
        tag = index
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TextView ?: continue
            child.background = pillBackground(i == index)
            child.setTextColor(color(if (i == index) R.color.slipnet_button_text_primary else R.color.slipnet_text_primary))
        }
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
        resolverMode.setPillSelectedIndex(if (c.resolverMode == Config.ResolverMode.AUTO) 1 else 0)
        resolverTransport.setPillSelectedIndex(if (c.resolverTransport == Config.ResolverTransport.TCP) 1 else 0)
        resolverPathMode.setPillSelectedIndex(if (c.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE) 1 else 0)
        auth.setPillSelectedIndex(if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) 1 else 0)
        username.setText(c.username)
        password.setText(c.password)
        dnsLabelLengthField.setText(c.dnsLabelLength.toString())
        maxPollQpsField.setText(c.maxPollQps.toString())
        updateResolverUi()
    }

    private fun selectProfile(profile: ConfigProfile) {
        ConfigStore.setActiveProfile(this, profile.id)
        activeConfig = profile.config
        profiles = ConfigStore.loadProfiles(this)
        navigateTo(buildMainUi(), ScreenTransition.NONE)
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
        showMainScreen(ScreenTransition.NONE)
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
        showMainScreen(ScreenTransition.BACK)
    }

    private fun installGlobalSettingsAutoSave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                persistGlobalSettingsFromEditor()
            }
        }
        listenPort.addTextChangedListener(watcher)
        localSocksUsername.addTextChangedListener(watcher)
        localSocksPassword.addTextChangedListener(watcher)
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                persistGlobalSettingsFromEditor()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        fileLogging.setOnCheckedChangeListener { _, _ ->
            persistGlobalSettingsFromEditor()
            refreshDrawerForDebugMode()
        }
        trafficNotification.setOnCheckedChangeListener { _, _ -> persistGlobalSettingsFromEditor() }
        localSocksAuth.setOnCheckedChangeListener { _, _ ->
            updateLocalSocksAuthUi()
            persistGlobalSettingsFromEditor()
        }
    }

    private fun persistGlobalSettingsIfVisible() {
        if (!settingsVisible || !::listenPort.isInitialized) return
        persistGlobalSettingsFromEditor()
    }

    private fun persistGlobalSettingsFromEditor() {
        if (suppressGlobalSettingsSave) return
        val settings = GlobalSettings(
            listenPort = listenPort.text.toString().toIntOrNull() ?: 1080,
            mode = if (mode.selectedItemPosition == 1) Config.Mode.VPN else Config.Mode.PROXY,
            fileLogging = fileLogging.isChecked,
            trafficNotification = trafficNotification.isChecked,
            localSocksAuthEnabled = localSocksAuth.isChecked,
            localSocksUsername = localSocksUsername.text.toString().trim().ifBlank { "slipstream" },
            localSocksPassword = localSocksPassword.text.toString().trim()
        )
        ConfigStore.saveGlobalSettings(this, settings)
        configureNativeLogging()
        activeConfig = ConfigStore.effectiveConfig(this)
    }

    private fun refreshDrawerForDebugMode() {
        if (diagnosticsVisible && !fileLogging.isChecked) {
            showMainScreen(ScreenTransition.BACK)
            return
        }
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = if (content.childCount > 0) content.getChildAt(content.childCount - 1) else null
        val frame = root as? FrameLayout ?: return
        drawerEdge?.let { frame.removeView(it) }
        drawerScrim?.let { frame.removeView(it) }
        drawerPanel?.let { frame.removeView(it) }
        drawerEdge = null
        drawerScrim = null
        drawerPanel = null
        addDrawer(frame, currentSectionTitle)
    }

    private fun readConfig(): Config {
        if (!editorVisible) return currentConfig()
        val resolverModeValue = if (resolverMode.pillSelectedIndex() == 1) {
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
            resolverTransport = if (resolverTransport.pillSelectedIndex() == 1) {
                Config.ResolverTransport.TCP
            } else {
                Config.ResolverTransport.UDP
            },
            resolverPathMode = if (resolverPathMode.pillSelectedIndex() == 1) {
                Config.ResolverPathMode.AUTHORITATIVE
            } else {
                Config.ResolverPathMode.RECURSIVE
            },
            listenPort = global.listenPort,
            mode = global.mode,
            authMode = if (auth.pillSelectedIndex() == 1) Config.AuthMode.LOGIN_PASSWORD else Config.AuthMode.NO_AUTH,
            username = username.text.toString(),
            password = password.text.toString(),
            // No editor UI for dnsQueryType (it requires a matching server setting) -- preserve
            // whatever the profile already had instead of silently resetting it to the default.
            dnsQueryType = editingBaseConfig?.dnsQueryType ?: 16,
            dnsLabelLength = dnsLabelLengthField.text.toString().toIntOrNull()?.coerceIn(1, 63) ?: 57,
            maxPollQps = maxPollQpsField.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        )
    }

    private fun currentConfig(): Config =
        if (editorVisible && ::domain.isInitialized) {
            readConfig()
        } else {
            activeConfig ?: ConfigStore.effectiveConfig(this)
        }

    private fun isTunnelRunning(): Boolean =
        SlipstreamBridge.isRunning() || HevSocks5Tunnel.isRunning() || proxyStarted

    private fun localSocksCredentials(): Pair<String?, String?> {
        val global = ConfigStore.loadGlobalSettings(this)
        return if (global.localSocksAuthEnabled) {
            global.localSocksUsername to global.localSocksPassword
        } else {
            null to null
        }
    }

    private fun toggle() {
        if (
            proxyStarted ||
            connecting ||
            pendingStartVpn ||
            ResolverSelector.lastProgress.active ||
            SlipstreamBridge.isRunning() ||
            HevSocks5Tunnel.isRunning()
        ) {
            stopAll()
            return
        }
        val c = readConfig()
        connecting = true
        connectStartedAt = System.currentTimeMillis()
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
                val localSocks = localSocksCredentials()
                SlipstreamBridge.startClient(
                    c.domain,
                    ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(c)),
                    slipstreamPort,
                    choice.qnameMtu,
                    choice.transport.name.lowercase()
                ).getOrThrow()
                ResolverSelector.lastConnectedTransport = choice.transport
                MiniSlipstreamSocksBridge.start(
                    listenHost = "127.0.0.1",
                    listenPort = bridgePort,
                    slipstreamHost = "127.0.0.1",
                    slipstreamPort = slipstreamPort,
                    dnsHost = choice.selectedHost,
                    username = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.username else null,
                    password = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.password else null,
                    localUsername = localSocks.first,
                    localPassword = localSocks.second
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
            connectStartedAt = 0L
            AppLog.e(TAG, "failed to start vpn service", e)
            toast(e.message ?: "vpn start failed")
        }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        pendingStartVpn = false
        connecting = false
        connectStartedAt = 0L
        ResolverSelector.cancelActiveProbes("disconnect")
        ResolverSelector.lastConnectedTransport = null
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
        val hev = HevSocks5Tunnel.stats()
        val bridge = MiniSlipstreamSocksBridge.stats()
        val displayRx = if (HevSocks5Tunnel.isRunning()) hev.rxBytes else bridge.rxBytes
        val displayTx = if (HevSocks5Tunnel.isRunning()) hev.txBytes else bridge.txBytes
        var rx = rawRx - rxBase
        var tx = rawTx - txBase
        val running = isTunnelRunning()
        val resolverProgress = ResolverSelector.lastProgress
        if (running) {
            connecting = false
            connectStartedAt = 0L
        } else if (
            connecting &&
            !pendingStartVpn &&
            !resolverProgress.active &&
            !stopping &&
            connectStartedAt != 0L &&
            System.currentTimeMillis() - connectStartedAt > START_CONNECTING_GRACE_MS
        ) {
            AppLog.w(TAG, "clearing stale connecting state after cancelled/failed start")
            connecting = false
            connectStartedAt = 0L
        }
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
        updateConnectButtonColor(running && !loading)
        updateBottomStatus(running, resolverProgress, if (idle) 0 else displayRx, if (idle) 0 else displayTx)
        if (::status.isInitialized) {
            val diag = TinyVpnService.liveDiag
            status.text = buildString {
                appendLine("running=$running ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()}")
                appendLine("transport=${(ResolverSelector.lastConnectedTransport ?: config.resolverTransport).name.lowercase()} qtype=${SlipstreamBridge.dnsQueryType} resolver=${config.resolverPathMode.name.lowercase()} cc=authoritative-fast")
                appendLine("upstream=qname qnameMtu=${diag.qnameMtu.takeIf { it > 0 } ?: "max"}")
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
                if (running) {
                    appendLine(
                        "active resolver=${diag.resolverHost.ifBlank { "-" }}:${diag.resolverPort} " +
                            "tested=${diag.dnsTested} alive=${diag.dnsAlive} skipped=${diag.dnsSkipped}"
                    )
                    appendLine("recovering=${diag.recovering} recoveries=${diag.recoveries}")
                    appendLine(
                        "lastProgressMs=${diag.lastProgressMs} readyFalseMs=${diag.readyFalseMs} " +
                            "slowResponseMs=${diag.slowResponseMs} lowBandwidthMs=${diag.lowBandwidthMs}"
                    )
                    appendLine("network=${diag.networkSignature.ifBlank { "-" }}")
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

    private fun isAuthoritativeResolverPath(config: Config): Boolean =
        config.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE

    private fun updateBottomStatus(running: Boolean, progress: ResolverSelector.Progress, rx: Long, tx: Long) {
        if (!::bottomStatus.isInitialized || !::trafficStatus.isInitialized) return
        val now = System.currentTimeMillis()
        val firstSample = rateSampleAt == 0L
        val elapsedMs = (now - rateSampleAt).takeIf { !firstSample && it > 0 } ?: 1000L
        val downRate = if (firstSample) 0L else ((rx - rateRxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        val upRate = if (firstSample) 0L else ((tx - rateTxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        rateRxLast = rx
        rateTxLast = tx
        rateSampleAt = now

        bottomStatus.text = when {
            stopping -> "Disconnecting"
            progress.active -> {
                if (progress.phase == "speed") {
                    val total = progress.speedTotal.takeIf { it > 0 } ?: progress.total
                    if (total > 0) "Speed probing ${progress.speedTested}/$total" else "Speed probing"
                } else {
                    if (progress.total > 0) "DNS probing ${progress.tested}/${progress.total}" else "DNS probing"
                }
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
        if (!editorVisible || !::resolverMode.isInitialized || !::resolverHost.isInitialized || !::resolverTransportContainer.isInitialized) return
        val manual = resolverMode.pillSelectedIndex() == 0
        resolverHost.isEnabled = manual
        resolverHostContainer.visibility = if (manual) View.VISIBLE else View.GONE
        useLocalDns.visibility = if (manual) View.VISIBLE else View.GONE
        resolverTransportContainer.visibility = if (manual) View.VISIBLE else View.GONE
    }

    private fun updateLocalSocksAuthUi() {
        if (!::localSocksAuth.isInitialized || !::localSocksUsername.isInitialized || !::localSocksPassword.isInitialized) return
        val enabled = localSocksAuth.isChecked
        localSocksUsername.isEnabled = enabled
        localSocksPassword.isEnabled = enabled
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
        navigateTo(buildMainUi(), ScreenTransition.FORWARD)
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
            isVerticalScrollBarEnabled = false
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
        private const val START_CONNECTING_GRACE_MS = 2500L
        private const val CRASH_PREFS = "crash_report"
        private const val KEY_CRASH_SEEN_SIZE = "seen_size"
        private val CONNECTED_BUTTON_COLOR = android.graphics.Color.rgb(72, 132, 82)
    }
}
