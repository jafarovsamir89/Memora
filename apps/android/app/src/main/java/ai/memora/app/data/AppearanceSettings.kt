package ai.memora.app.data

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Only non-sensitive presentation settings; no country or currency is inferred. */
data class AppearanceSettings(val language: String = "ru", val theme: String = "system") {
    init {
        require(language in setOf("ru", "az", "en"))
        require(theme in setOf("system", "light", "dark"))
    }
}

interface AppearanceStore {
    fun read(): AppearanceSettings
    fun write(settings: AppearanceSettings)
}

class AtomicAppearanceStore(private val file: File, private val defaults: AppearanceSettings = AppearanceSettings()) : AppearanceStore {
    private val atomic = AtomicFile(file)

    override fun read(): AppearanceSettings {
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            if (File(file.path + ".new").exists()) throw IOException("Incomplete settings file")
            return defaults
        }
        val bytes = atomic.openRead().use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            while (buffer.size() <= 256) {
                val value = input.read()
                if (value < 0) break
                buffer.write(value)
            }
            buffer.toByteArray()
        }
        if (bytes.size > 256) throw IOException("Settings file too large")
        val text = bytes.toString(Charsets.UTF_8)
        val match = Regex("MEMORA_SETTINGS_1\\n(ru|az|en)\\n(system|light|dark)\\n").matchEntire(text)
            ?: throw IOException("Invalid or unsupported settings file")
        return AppearanceSettings(match.groupValues[1], match.groupValues[2])
    }

    override fun write(settings: AppearanceSettings) {
        val output = atomic.startWrite()
        try {
            output.write("MEMORA_SETTINGS_1\n${settings.language}\n${settings.theme}\n".toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        if (read() != settings) throw IOException("Settings write could not be verified")
    }
}

data class AppearanceState(val settings: AppearanceSettings, val ready: Boolean = false, val busy: Boolean = false, val error: Boolean = false)

class AppearanceViewModel(private val store: AppearanceStore, defaults: AppearanceSettings) : ViewModel() {
    private val mutable = MutableStateFlow(AppearanceState(defaults))
    val state = mutable.asStateFlow()

    init { load() }

    fun load() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = false)
        viewModelScope.launch {
            val loaded = try { withContext(Dispatchers.IO) { store.read() } } catch (_: Exception) { null }
            mutable.value = if (loaded == null) mutable.value.copy(ready = false, busy = false, error = true)
                else AppearanceState(loaded, ready = true)
        }
    }

    fun change(language: String = mutable.value.settings.language, theme: String = mutable.value.settings.theme) {
        if (!mutable.value.ready || mutable.value.busy) return
        val next = AppearanceSettings(language, theme)
        if (next == mutable.value.settings) return
        mutable.value = mutable.value.copy(busy = true, error = false)
        viewModelScope.launch {
            val saved = try { withContext(Dispatchers.IO) { store.write(next); true } } catch (_: Exception) { false }
            mutable.value = if (saved) AppearanceState(next, ready = true)
                else mutable.value.copy(ready = false, busy = false, error = true)
        }
    }

    class Factory(context: Context, private val defaults: AppearanceSettings, private val override: AppearanceStore? = null) : ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == AppearanceViewModel::class.java)
            return AppearanceViewModel(override ?: AtomicAppearanceStore(File(app.filesDir, "appearance-settings-v1"), defaults), defaults) as T
        }
    }
}
