package app.smugly

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import app.smugly.ui.SmuglyApp
import app.smugly.ui.theme.SmuglyBg
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box

/**
 * Multiplatform Compose UI host for Android.
 * Same screens as desktop/iOS [SmuglyApp]; VPN/proxy via [AndroidSmuglyPlatform].
 *
 * Legacy View-based [MainActivity] remains in the tree for reference / rollback
 * but is no longer the launcher.
 */
class ComposeMainActivity : ComponentActivity() {
    private lateinit var platform: AndroidSmuglyPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        AndroidStrings.init(this)
        platform = AndroidSmuglyPlatform(this)
        setContent {
            Box(Modifier.fillMaxSize().background(SmuglyBg)) {
                SmuglyApp(platform)
            }
        }
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    private fun handleImportIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val text = extractImportText(intent, data) ?: return

        // Hand the URL to Compose so it can show "Importing…" and run the same path as
        // clipboard / file import. Fetching here hid the overlay and the folder just popped in.
        if (app.smugly.subscription.SubscriptionManager.looksLikeSubscription(text)) {
            platform.offerPendingImport(text)
            return
        }

        val imported = ConfigStore.importProfile(this, data)
        if (imported != null) {
            platform.notifyDataChanged()
            android.widget.Toast.makeText(
                this,
                t(S.TOAST_PROFILE_IMPORTED),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun extractImportText(intent: Intent, data: android.net.Uri): String? {
        val fromQuery = data.getQueryParameter("url")?.trim().orEmpty()
        if (fromQuery.startsWith("http://") || fromQuery.startsWith("https://")) return fromQuery

        val raw = data.toString()
        app.smugly.subscription.SubscriptionManager.normalizeSubscriptionUrl(raw)?.let { return it }

        val host = data.host.orEmpty().lowercase()
        if (host == "add" || host == "import") {
            val encoded = data.encodedPath?.trimStart('/') ?: ""
            if (encoded.isNotEmpty()) {
                var candidate = android.net.Uri.decode(encoded)
                val query = data.encodedQuery
                if (!candidate.contains('?') && !query.isNullOrBlank()) {
                    candidate = "$candidate?$query"
                }
                app.smugly.subscription.SubscriptionManager.normalizeSubscriptionUrl(candidate)
                    ?.let { return it }
            }
        }

        val scheme = data.scheme.orEmpty().lowercase()
        if (scheme == "smugly" || scheme == "sub" || scheme == "slipstream") {
            clipboardSubscription()?.let { return it }
        }
        return raw
    }

    private fun clipboardSubscription(): String? {
        val text = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        }.getOrNull().orEmpty()
        return text.takeIf { app.smugly.subscription.SubscriptionManager.looksLikeSubscription(it) }
    }
}
