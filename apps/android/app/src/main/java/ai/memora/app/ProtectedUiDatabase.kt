package ai.memora.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.LocalProfileStore

/** Explicit dependency injection; the normal application always opens the protected SQLCipher store. */
internal val LocalAgentDatabaseOverride = staticCompositionLocalOf<AgentDatabase?> { null }
internal val LocalProfileStoreOverride = staticCompositionLocalOf<LocalProfileStore?> { null }

@Composable
internal fun rememberUiAgentDatabase(): AgentDatabase {
    val context = LocalContext.current
    val injected = LocalAgentDatabaseOverride.current
    return remember(context, injected) { injected ?: AgentDatabase.open(context) }
}

/** Read-only projections can expose a local unavailable state instead of crashing the containing screen. */
@Composable
internal fun rememberOptionalUiAgentDatabase(): AgentDatabase? {
    val context = LocalContext.current
    val injected = LocalAgentDatabaseOverride.current
    return remember(context, injected) { injected ?: runCatching { AgentDatabase.open(context) }.getOrNull() }
}
