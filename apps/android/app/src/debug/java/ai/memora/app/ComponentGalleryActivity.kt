package ai.memora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ai.memora.app.ui.ComponentGalleryScreen

/** Debug-only screen for deterministic Compose screenshots; it never opens application storage. */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            ComponentGalleryScreen(
                component = intent.getStringExtra(EXTRA_COMPONENT) ?: "unknown",
                theme = intent.getStringExtra(EXTRA_THEME) ?: "light",
            )
        }
    }

    companion object {
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_THEME = "theme"
    }
}
