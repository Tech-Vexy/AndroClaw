package ai.androclaw

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import ai.androclaw.feature.auth.GoogleOAuthManager
import ai.androclaw.feature.notifications.AppLifecycleObserver
import ai.androclaw.feature.notifications.WaMessageSyncer
import ai.androclaw.ui.OpenClawApp
import org.koin.android.ext.android.inject

/**
 * Single-activity host.
 * Extends AppCompatActivity (required by Google Sign-In).
 * Registers:
 *   - GoogleOAuthManager launcher (must be before setContent)
 *   - AppLifecycleObserver for foreground WA sync
 */
class MainActivity : AppCompatActivity() {

    private val googleOAuth: GoogleOAuthManager by inject()
    private val waMessageSyncer: WaMessageSyncer by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Register Google Sign-In result launcher BEFORE setContent
        googleOAuth.registerLauncher(this)

        // Sync WA messages whenever app foregrounds
        lifecycle.addObserver(AppLifecycleObserver(waMessageSyncer))

        setContent { OpenClawApp() }
    }
}

