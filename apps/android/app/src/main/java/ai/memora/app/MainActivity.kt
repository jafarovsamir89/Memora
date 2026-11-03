package ai.memora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.memora.app.ai.configureAppCheck
import ai.memora.app.reminders.AttentionNotifications

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this)
        enableEdgeToEdge()
        AttentionNotifications.reconcileFromApp(this)
        setContent { MemoraApp() }
    }
}
