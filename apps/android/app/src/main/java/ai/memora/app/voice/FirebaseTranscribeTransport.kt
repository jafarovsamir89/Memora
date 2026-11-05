package ai.memora.app.voice

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig

/**
 * Transcribes a bounded in-memory WAV with a Firebase AI Logic-supported audio model.
 * Firebase AI Logic SDK supplies App Check; no custom WebSocket/proxy protocol is used.
 */
internal class FirebaseTranscribeTransport(private val context: Context) {
    suspend fun transcribe(wav: ByteArray): String {
        validateTranscriptionWav(wav)
        check(FirebaseApp.getApps(context).isNotEmpty()) { "Firebase is not configured" }
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = VoiceLanguagePolicy.AUDIO_TRANSCRIPTION_MODEL,
            generationConfig = generationConfig { temperature = 0f; maxOutputTokens = 512 },
            systemInstruction = content { text(VoiceLanguagePolicy.transcriptionInstruction()) },
        )
        val prompt = content {
            inlineData(wav, "audio/wav")
            text("Transcribe only the speech in this audio using the system instruction.")
        }
        return model.generateContent(prompt).text?.trim()?.take(20_000).orEmpty()
    }
}

/** Only the recorder's bounded mono PCM16/16kHz RIFF-WAV envelope is accepted. */
internal fun validateTranscriptionWav(wav: ByteArray) {
    require(wav.size in 46..800_044 && wav.size % 2 == 0)
    fun tag(offset: Int, value: String) = wav.copyOfRange(offset, offset + value.length)
        .contentEquals(value.toByteArray())
    val buffer = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    require(tag(0, "RIFF") && tag(8, "WAVEfmt ") && tag(36, "data"))
    require(buffer.getInt(4) == wav.size - 8 && buffer.getInt(16) == 16 && buffer.getShort(20).toInt() == 1)
    require(buffer.getInt(24) == 16_000 && buffer.getShort(22).toInt() == 1 && buffer.getShort(34).toInt() == 16)
    require(buffer.getInt(28) == 32_000 && buffer.getShort(32).toInt() == 2 && buffer.getInt(40) == wav.size - 44)
}
