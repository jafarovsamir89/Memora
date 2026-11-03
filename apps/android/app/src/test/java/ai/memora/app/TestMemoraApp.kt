package ai.memora.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ai.memora.app.data.AppearanceStore
import ai.memora.app.data.CapturePersistence
import ai.memora.app.data.LocalProfileInput
import ai.memora.app.data.LocalProfileStore
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.protectedstore.LocalProfileRow

/** Existing app-screen tests model an already-onboarded local install unless a test overrides it. */
@Composable
internal fun TestMemoraApp(
    initialRoute: String = "home",
    initialTheme: String = "system",
    capturePersistenceOverride: CapturePersistence? = null,
    appearanceStoreOverride: AppearanceStore? = null,
    initialLanguage: String = "az",
    taskStoreOverride: EncryptedTaskStore? = null,
    profileStoreOverride: LocalProfileStore? = null,
    textTurnOverride: (suspend (String) -> ai.memora.app.voice.VoiceFirstResult)? = null,
) {
    val profileStore = androidx.compose.runtime.remember(profileStoreOverride, initialLanguage) {
        profileStoreOverride ?: InMemoryLocalProfileStore(
            LocalProfileRow("synthetic-local-profile", null, initialLanguage, "friendly", "balanced", "Asia/Baku",
                1, true, null, 1L, 1L),
        )
    }
    CompositionLocalProvider(LocalProfileStoreOverride provides profileStore) {
        MemoraApp(initialRoute, initialTheme, capturePersistenceOverride, appearanceStoreOverride,
            initialLanguage, taskStoreOverride, textTurnOverride)
    }
}

internal class InMemoryLocalProfileStore(initial: LocalProfileRow? = null) : LocalProfileStore {
    private var current = initial
    var completeCalls: Int = 0
        private set
    override suspend fun read(): LocalProfileRow? = current
    override suspend fun complete(input: LocalProfileInput): LocalProfileRow {
        completeCalls++
        val existing = current
        if (existing?.onboardingCompleted == true) return existing
        current = if (existing == null) {
            LocalProfileRow("synthetic-local-profile", input.displayName, input.language, input.toneStyle, input.responseStyle,
                input.timeZoneId, 1, true, null, 1L, 2L)
        } else existing.copy(displayName = input.displayName, language = input.language,
            toneStyle = input.toneStyle, responseStyle = input.responseStyle, timeZoneId = input.timeZoneId,
            onboardingVersion = 1, onboardingCompleted = true, updatedAtMillis = 2L)
        return requireNotNull(current)
    }
    override suspend fun updateLanguage(profileId: String, language: String): LocalProfileRow {
        val existing = requireNotNull(current)
        require(existing.localProfileId == profileId)
        return existing.copy(language = language, updatedAtMillis = existing.updatedAtMillis + 1).also { current = it }
    }
    override suspend fun updateProfile(profileId: String, input: LocalProfileInput): LocalProfileRow {
        val existing = requireNotNull(current)
        require(existing.localProfileId == profileId && existing.onboardingCompleted)
        return existing.copy(displayName = input.displayName, language = input.language,
            toneStyle = input.toneStyle, responseStyle = input.responseStyle, timeZoneId = input.timeZoneId,
            updatedAtMillis = existing.updatedAtMillis + 1).also { current = it }
    }
}
