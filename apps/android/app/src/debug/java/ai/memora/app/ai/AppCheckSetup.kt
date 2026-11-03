package ai.memora.app.ai

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

fun configureAppCheck(context: Context) {
    if (FirebaseApp.getApps(context).isNotEmpty()) {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(ConsoleDebugAppCheckProviderFactory())
    }
}
