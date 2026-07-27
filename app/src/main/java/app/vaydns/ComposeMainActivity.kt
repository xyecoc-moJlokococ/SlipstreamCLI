package app.vaydns

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import app.vaydns.ui.VaydnsApp
import app.vaydns.ui.theme.SlipnetBg
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box

/**
 * Multiplatform Compose UI host for Android.
 * Same screens as desktop/iOS [VaydnsApp]; VPN/proxy via [AndroidVaydnsPlatform].
 *
 * Legacy View-based [MainActivity] remains in the tree for reference / rollback
 * but is no longer the launcher.
 */
class ComposeMainActivity : ComponentActivity() {
    private lateinit var platform: AndroidVaydnsPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        AndroidStrings.init(this)
        platform = AndroidVaydnsPlatform(this)
        setContent {
            Box(Modifier.fillMaxSize().background(SlipnetBg)) {
                VaydnsApp(platform)
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
        val imported = ConfigStore.importProfile(this, data)
        if (imported != null) {
            // Profiles refresh on next composition / navigation; toast confirms.
            android.widget.Toast.makeText(
                this,
                t(S.TOAST_PROFILE_IMPORTED),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
