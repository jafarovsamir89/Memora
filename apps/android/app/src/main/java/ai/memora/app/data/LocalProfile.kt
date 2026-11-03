package ai.memora.app.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.DatabaseKeyProvider
import ai.memora.app.data.protectedstore.LocalProfileRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal data class LocalProfileInput(
    val displayName: String?,
    val language: String,
    val toneStyle: String,
    val responseStyle: String,
    val timeZoneId: String,
) {
    init {
        require(language in setOf("az", "ru", "en"))
        require(toneStyle in setOf("friendly", "direct"))
        require(responseStyle in setOf("concise", "balanced", "detailed"))
        require(timeZoneId in java.time.ZoneId.getAvailableZoneIds())
        require(displayName == null || displayName.length <= 60)
    }
}

internal interface LocalProfileStore {
    suspend fun read(): LocalProfileRow?
    suspend fun complete(input: LocalProfileInput): LocalProfileRow
    suspend fun updateLanguage(profileId: String, language: String): LocalProfileRow
}

internal class ProtectedLocalProfileStore(private val database: AgentDatabase) : LocalProfileStore {
    override suspend fun read(): LocalProfileRow? = database.protectedStoreDao().localProfile()

    override suspend fun complete(input: LocalProfileInput): LocalProfileRow = database.withTransaction {
        val dao = database.protectedStoreDao()
        val existing = dao.localProfile()
        if (existing?.onboardingCompleted == true) return@withTransaction existing
        val normalizedName = input.displayName?.trim()?.takeIf(String::isNotEmpty)
        val now = System.currentTimeMillis()
        if (existing == null) {
            dao.insertLocalProfile(LocalProfileRow(
                localProfileId = UUID.randomUUID().toString(), displayName = normalizedName,
                language = input.language, toneStyle = input.toneStyle,
                responseStyle = input.responseStyle, timeZoneId = input.timeZoneId,
                onboardingVersion = 1, onboardingCompleted = true, remoteAccountId = null,
                createdAtMillis = now, updatedAtMillis = now,
            ))
        } else {
            check(!existing.onboardingCompleted)
            check(dao.completeLocalProfile(existing.localProfileId, normalizedName, input.language,
                input.toneStyle, input.responseStyle, input.timeZoneId, 1, now) == 1) { "profile_completion_conflict" }
        }
        requireNotNull(dao.localProfile()).also { profile ->
            check(profile.onboardingCompleted && profile.language == input.language &&
                profile.toneStyle == input.toneStyle && profile.responseStyle == input.responseStyle &&
                profile.timeZoneId == input.timeZoneId &&
                profile.displayName == normalizedName && profile.remoteAccountId == null) { "profile_readback_mismatch" }
        }
    }

    override suspend fun updateLanguage(profileId: String, language: String): LocalProfileRow = database.withTransaction {
        require(language in setOf("az", "ru", "en"))
        val dao = database.protectedStoreDao()
        val current = requireNotNull(dao.localProfile())
        check(current.localProfileId == profileId && current.onboardingCompleted) { "profile_not_found" }
        if (current.language != language) {
            check(dao.updateLocalProfileLanguage(profileId, language, System.currentTimeMillis()) == 1) { "profile_update_conflict" }
        }
        requireNotNull(dao.localProfile()).also { check(it.localProfileId == profileId && it.language == language) }
    }
}

internal sealed interface LocalProfileState {
    data object Loading : LocalProfileState
    data object Onboarding : LocalProfileState
    data class Ready(val profile: LocalProfileRow) : LocalProfileState
    data class Failed(val code: String, val pendingInput: LocalProfileInput? = null) : LocalProfileState
}

internal enum class InstallDisposition { NEW, EXISTING }

/** Install lineage only; the marker contains no profile values or user data. */
internal class LocalInstallMarker(
    private val context: Context,
    markerFile: File = File(context.noBackupFilesDir, "memora-local-install-state-v1"),
) {
    private val atomic = AtomicFile(markerFile)

    fun inspect(): InstallDisposition {
        val value = try {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            val initial = if (hasLegacyState()) "existing" else "new"
            write(initial)
            initial
        }
        return when (value) {
            "new" -> InstallDisposition.NEW
            "existing", "complete" -> InstallDisposition.EXISTING
            else -> error("invalid_install_marker")
        }
    }

    fun complete() = write("complete")

    private fun hasLegacyState(): Boolean {
        val files = listOf("captures-prototype.bin", "tasks-prototype.bin", "voice-memory.bin", "appearance-settings-v1")
        if (files.any { File(context.filesDir, it).isFile }) return true
        if (context.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME).isFile) return true
        return File(context.applicationInfo.dataDir, "shared_prefs/voice-first-consent.xml").isFile
    }

    private fun write(value: String) {
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}

internal class LocalProfileViewModel(
    private val appContext: Context,
    private val storeOverride: LocalProfileStore?,
    private val initialLanguage: String,
) : ViewModel() {
    private val mutable = MutableStateFlow<LocalProfileState>(LocalProfileState.Loading)
    val state = mutable.asStateFlow()
    private var store: LocalProfileStore? = null
    private val installMarker = if (storeOverride == null) LocalInstallMarker(appContext) else null
    private val storageDispatcher = if (storeOverride == null) Dispatchers.IO else Dispatchers.Unconfined

    init { load() }

    fun load() {
        mutable.value = LocalProfileState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(storageDispatcher) {
                    // Inspect before opening Room: a fresh open creates an empty DB file, which
                    // must not be mistaken for pre-existing user data on first launch.
                    val installDisposition = installMarker?.inspect()
                    val localStore = storeOverride ?: ProtectedLocalProfileStore(AgentDatabase.open(appContext))
                    store = localStore
                    val existing = localStore.read()
                    if (existing != null) {
                        installMarker?.complete()
                        LocalProfileState.Ready(existing)
                    } else if (storeOverride != null || installDisposition == InstallDisposition.NEW) {
                        LocalProfileState.Onboarding
                    } else {
                        val legacyProfile = localStore.complete(LocalProfileInput(null, initialLanguage,
                            "friendly", "balanced", "Asia/Baku"))
                        installMarker!!.complete()
                        LocalProfileState.Ready(legacyProfile)
                    }
                }
                mutable.value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MemoraProfile", "profile_startup_failed code=profile_storage_unavailable type=${error.javaClass.simpleName.take(64)}")
                mutable.value = LocalProfileState.Failed("profile_storage_unavailable")
            }
        }
    }

    fun complete(input: LocalProfileInput) {
        val mayComplete = mutable.value == LocalProfileState.Onboarding ||
            (mutable.value as? LocalProfileState.Failed)?.pendingInput != null
        if (!mayComplete) return
        mutable.value = LocalProfileState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(storageDispatcher) {
                    val saved = requireNotNull(store).complete(input)
                    installMarker?.complete()
                    LocalProfileState.Ready(saved)
                }
                mutable.value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MemoraProfile", "profile_save_failed code=profile_save_failed type=${error.javaClass.simpleName.take(64)}")
                mutable.value = LocalProfileState.Failed("profile_save_failed", input)
            }
        }
    }

    fun retry() {
        val failed = mutable.value as? LocalProfileState.Failed ?: return
        val input = failed.pendingInput
        if (input == null) load() else complete(input)
    }

    fun syncLanguage(language: String) {
        val ready = mutable.value as? LocalProfileState.Ready ?: return
        if (ready.profile.language == language || language !in setOf("az", "ru", "en")) return
        viewModelScope.launch {
            runCatching { withContext(storageDispatcher) { requireNotNull(store).updateLanguage(ready.profile.localProfileId, language) } }
                .onSuccess { mutable.value = LocalProfileState.Ready(it) }
        }
    }

    class Factory(context: Context, private val initialLanguage: String, private val override: LocalProfileStore?) : ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LocalProfileViewModel::class.java)
            return LocalProfileViewModel(app, override, initialLanguage) as T
        }
    }
}
