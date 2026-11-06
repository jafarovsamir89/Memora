package ai.memora.app.voice

import android.content.Context
import ai.memora.app.ai.DirectGeminiApi
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

/** Transcribes a bounded in-memory WAV via direct Gemini API in keyed debug builds. */
internal class GeminiTranscribeTransport(private val context: Context) {
    suspend fun transcribe(wav: ByteArray): String {
        validateTranscriptionWav(wav)
        if (DirectGeminiApi.isConfigured) return transcribeDirect(wav)
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

    private suspend fun transcribeDirect(wav: ByteArray): String {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", VoiceLanguagePolicy.transcriptionInstruction()))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                .put(JSONObject().put("text", "Transcribe only the speech in this audio using the system instruction.")))))
            .put("generation_config", JSONObject().put("temperature", 0).put("max_output_tokens", 512))
        val response = DirectGeminiApi.generate(VoiceLanguagePolicy.AUDIO_TRANSCRIPTION_MODEL, body)
        return response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
            }?.trim()?.take(20_000).orEmpty()
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
