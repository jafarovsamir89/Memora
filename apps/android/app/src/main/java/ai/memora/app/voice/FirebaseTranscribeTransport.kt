package ai.memora.app.voice

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Narrow preview adapter for Firebase's Google AI WebSocket proxy. SDK 18.1.0 cannot express
 * Transcribe languageCodes/mode. Retains App Check and Android app restrictions; no Gemini secret,
 * reflection, direct provider endpoint, request logging, automatic fallback or personal archive.
 * Protocol: Google Live Transcribe docs and firebase-ai 18.1.0 APIController/LiveClientSetupMessage.
 */
internal class FirebaseTranscribeTransport(private val context: Context) {
    suspend fun transcribe(wav: ByteArray): String = withTimeout(30_000) {
        val rate = TranscribeProtocol.sampleRate(wav)
        val app = FirebaseApp.getInstance()
        val project = requireNotNull(app.options.projectId)
        val token = withTimeout(10_000) { FirebaseAppCheck.getInstance(app).getAppCheckToken(false).await().token }
        check(token.isNotBlank()) { "App Check is required" }
        val url = HttpUrl.Builder().scheme("https").host("firebasevertexai.googleapis.com")
            .addPathSegments("ws/google.firebase.vertexai.v1beta.GenerativeService/BidiGenerateContent")
            .addQueryParameter("key", app.options.apiKey).build()
        val request = Request.Builder().url(url)
            .header("x-goog-api-key", app.options.apiKey)
            .header("x-goog-api-client", "gl-kotlin/2.1.20 fire/18.1.0 memora/0.5")
            .header("X-Android-Package", context.packageName)
            .header("X-Android-Cert", signingCertificate())
            .header("X-Firebase-AppCheck", token)
            .apply { if (app.isDataCollectionDefaultEnabled) {
                header("X-Firebase-AppId", app.options.applicationId)
                header("X-Firebase-AppVersion", "0.5.0-az-memory")
            } }.build()
        val messages = Channel<String>(8)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(TranscribeProtocol.setup(project))) {
                    messages.close(IllegalStateException("Transcribe setup failed")); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 200_000 || messages.trySend(text).isFailure) {
                    messages.close(IllegalStateException("Transcribe message limit")); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 200_000) {
                    messages.close(IllegalStateException("Transcribe message limit")); webSocket.cancel()
                } else onMessage(webSocket, bytes.utf8())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // A provider close reason can contain request data; never propagate it.
                messages.close(IllegalStateException("Transcribe connection closed ($code)"))
                webSocket.close(code, null)
            }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                // Do not attach transport exceptions: their URL may include the Firebase client key.
                messages.close(IllegalStateException("Transcribe connection failed (${response?.code ?: 0})"))
            }
        })
        try {
            check(JSONObject(messages.receive()).has("setupComplete")) { "Transcribe setup rejected" }
            check(socket.send(TranscribeProtocol.activity(start = true)))
            var offset = 44
            val chunkSize = rate / 10 * 2 // 100ms mono PCM16
            while (offset < wav.size) {
                val end = minOf(offset + chunkSize, wav.size)
                check(socket.send(TranscribeProtocol.audio(wav, offset, end, rate)))
                offset = end
            }
            check(socket.send(TranscribeProtocol.activity(start = false)))
            // With manual VAD the entire bounded note is one activity; ignore speculative interim text.
            var transcript: String? = null
            while (transcript == null) transcript = TranscribeProtocol.finalTranscript(messages.receive())
            transcript
        } finally {
            messages.cancel()
            socket.cancel() // immediate release on success, timeout, cancellation or malformed response
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificate(): String {
        val manager = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            val info = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            requireNotNull(info.signingInfo).apkContentsSigners.first()
        } else manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
        return MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    private companion object {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}

internal object TranscribeProtocol {
    fun setup(project: String): String = JSONObject().put("setup", JSONObject()
        .put("model", "projects/$project/models/${VoiceLanguagePolicy.TRANSCRIBE_MODEL}")
        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
        .put("inputAudioTranscription", JSONObject().put("languageCodes", JSONArray().put("az-AZ").put("ru-RU"))
            .put("mode", "VERBATIM"))
        .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true))))
        .toString()

    fun activity(start: Boolean): String = JSONObject().put("realtimeInput", JSONObject()
        .put(if (start) "activityStart" else "activityEnd", JSONObject())).toString()

    fun audio(wav: ByteArray, start: Int, end: Int, rate: Int): String {
        val bytes = wav.copyOfRange(start, end)
        val encoded = try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        return JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", encoded).put("mimeType", "audio/pcm;rate=$rate"))).toString()
    }

    fun finalTranscript(message: String): String? {
        val json = JSONObject(message)
        check(!json.has("error")) { "Transcribe service rejected the request" }
        val text = json.optJSONObject("serverContent")?.optJSONObject("inputTranscription")?.optString("text")
            ?.trim()?.takeIf { it.isNotEmpty() }
        require(text == null || text.length <= 20_000) { "Transcript limit" }
        return text
    }

    /** Only our fixed PCM WAV envelope is accepted, never arbitrary user files or compressed media. */
    fun sampleRate(wav: ByteArray): Int {
        require(wav.size in 46..800_044 && wav.size % 2 == 0)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int, value: String) = wav.copyOfRange(offset, offset + value.length).contentEquals(value.toByteArray())
        require(tag(0, "RIFF") && tag(8, "WAVEfmt ") && tag(36, "data"))
        require(buffer.getInt(4) == wav.size - 8 && buffer.getInt(16) == 16 && buffer.getShort(20).toInt() == 1)
        val rate = buffer.getInt(24)
        require(rate in setOf(16_000, 24_000) && buffer.getShort(22).toInt() == 1 && buffer.getShort(34).toInt() == 16)
        require(buffer.getInt(28) == rate * 2 && buffer.getShort(32).toInt() == 2 && buffer.getInt(40) == wav.size - 44)
        return rate
    }
}
