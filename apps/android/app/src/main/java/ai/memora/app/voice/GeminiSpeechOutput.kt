@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package ai.memora.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface SpeechOutput { suspend fun speak(text: String) }

/** Only the short answer is sent to Google for speech; the archive remains local. */
class GeminiSpeechOutput : SpeechOutput {
    override suspend fun speak(text: String) = withTimeout(30_000) {
        require(text.isNotBlank() && text.length <= 1000)
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.1-flash-tts-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(voice = Voice("Kore"))
            })
        val response = model.generateContent("Read the following text exactly in its original language, without adding or translating words: $text")
        val parts = response.candidates.firstOrNull()?.content?.parts?.filterIsInstance<InlineDataPart>().orEmpty()
        require(parts.isNotEmpty() && parts.all { it.mimeType.startsWith("audio/pcm") })
        val length = parts.fold(0L) { n, part -> n + part.inlineData.size }
        require(length in 2..1_500_000 && length % 2 == 0L)
        val audio = ByteArray(length.toInt())
        var position = 0
        parts.forEach { part -> part.inlineData.copyInto(audio, position); position += part.inlineData.size }
        try { playPcm(audio) } finally { audio.fill(0) }
    }

    private suspend fun playPcm(audio: ByteArray) = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(audio.size).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            check(track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING) == audio.size)
            currentCoroutineContext().ensureActive()
            track.play()
            while (track.playbackHeadPosition.toLong() < audio.size / 2L) {
                currentCoroutineContext().ensureActive()
                delay(25)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
