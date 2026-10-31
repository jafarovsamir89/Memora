package ai.memora.app.data

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File

/** Retains an in-flight synthetic capture session across activity configuration changes. */
class CaptureViewModel(persistence: CapturePersistence) : ViewModel() {
    val session = CaptureSession(persistence, viewModelScope)

    override fun onCleared() {
        session.close()
    }

    class Factory(context: Context, private val persistenceOverride: CapturePersistence? = null) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == CaptureViewModel::class.java)
            val persistence = persistenceOverride ?: LocalCapturePersistence(
                EncryptedCaptureStore(File(appContext.filesDir, "captures-prototype.bin"), CaptureStoreKey::get)
            )
            return CaptureViewModel(persistence) as T
        }
    }
}
