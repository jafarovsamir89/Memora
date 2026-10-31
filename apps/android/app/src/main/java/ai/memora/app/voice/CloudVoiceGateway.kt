@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package ai.memora.app.voice

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig

interface CloudVoiceHandle {
    suspend fun begin()
    fun stop()
    suspend fun close()
    fun isClosed(): Boolean
}

interface CloudVoiceGateway {
    suspend fun connect(onTranscript: (String?, String?) -> Unit): CloudVoiceHandle
}

/** No memory, task, action, file or function tools are passed to the model. */
class FirebaseCloudVoiceGateway(private val context: Context) : CloudVoiceGateway {
    override suspend fun connect(onTranscript: (String?, String?) -> Unit): CloudVoiceHandle {
        check(FirebaseApp.getApps(context).isNotEmpty()) { "Firebase is not configured" }
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = "gemini-3.1-flash-live-preview",
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            },
            systemInstruction = content {
                text("You are Memora's test assistant. Speak naturally in the user's language, including Azerbaijani, Russian and English or mixed speech. Ask when uncertain. You have no access to personal memories, tasks or tools and must not claim to have performed actions or remembered facts.")
            },
        )
        return FirebaseCloudVoiceHandle(context, model.connect(), onTranscript)
    }
}

private class FirebaseCloudVoiceHandle(
    private val context: Context,
    private val session: LiveSession,
    private val onTranscript: (String?, String?) -> Unit,
) : CloudVoiceHandle {
    override suspend fun begin() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission not granted")
        }
        session.startAudioConversation(null, { input, output -> onTranscript(input?.text, output?.text) })
    }
    override fun stop() { session.stopAudioConversation() }
    override suspend fun close() { session.close() }
    override fun isClosed(): Boolean = session.isClosed()
}
