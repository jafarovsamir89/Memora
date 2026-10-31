@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package ai.memora.app.voice

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.liveAudioConversationConfig

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
            modelName = VoiceLanguagePolicy.LIVE_MODEL,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
                speechConfig = SpeechConfig(voice = Voice("Kore"))
                realtimeInputConfig = RealtimeInputConfig.builder()
                    .setAutomaticActivityDetection(ActivityDetectionConfig.builder()
                        .setStartSensitivity(ActivityDetectionConfig.Sensitivity.LOW)
                        .setEndSensitivity(ActivityDetectionConfig.Sensitivity.LOW)
                        .setPrefixPaddingMs(VoiceLanguagePolicy.PREFIX_MS)
                        .setSilenceDurationMs(VoiceLanguagePolicy.SILENCE_MS).build())
                    .setActivityHandling(RealtimeInputConfig.ActivityHandling.INTERRUPT)
                    .setTurnCoverage(RealtimeInputConfig.TurnCoverage.ONLY_ACTIVITY).build()
            },
            systemInstruction = content {
                text(VoiceLanguagePolicy.instruction())
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
        session.startAudioConversation(liveAudioConversationConfig {
            initializationHandler = { recorder, _ -> recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION) }
            transcriptHandler = { input, output -> onTranscript(input?.text, output?.text) }
        })
    }
    override fun stop() { session.stopAudioConversation() }
    override suspend fun close() { session.close() }
    override fun isClosed(): Boolean = session.isClosed()
}
