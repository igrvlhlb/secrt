package io.github.igrvlhlb.secrt.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.igrvlhlb.secrt.ui.SecrtApp

// A FragmentActivity (still a ComponentActivity) because BiometricPrompt is hosted in a fragment.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps diary content out of screenshots, screen recordings and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val app = application as SecrtApplication
        app.biometrics.attach(this)
        DebugHooks.handle(intent, app.lan)
        setContent { SecrtApp(app.session, app.backgroundScope, app.autoLock, app.lan) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugHooks.handle(intent, (application as SecrtApplication).lan)
    }
}
