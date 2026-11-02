package ai.memora.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import ai.memora.app.data.protectedstore.AgentDatabase

/** Explicit dependency injection; the normal application always opens the protected SQLCipher store. */
internal val LocalAgentDatabaseOverride = staticCompositionLocalOf<AgentDatabase?> { null }

@Composable
internal fun rememberUiAgentDatabase(): AgentDatabase {
    val context = LocalContext.current
    val injected = LocalAgentDatabaseOverride.current
    return remember(context, injected) { injected ?: AgentDatabase.open(context) }
}
