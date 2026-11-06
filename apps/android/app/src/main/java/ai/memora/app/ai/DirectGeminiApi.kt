package ai.memora.app.ai

import ai.memora.app.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Direct Gemini Developer API transport, enabled only for debug builds with a local test key. */
internal object DirectGeminiApi {
    private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    private const val MAX_503_RETRIES = 2
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = BuildConfig.DEBUG && BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun generate(model: String, requestBody: JSONObject): JSONObject {
        require(isConfigured) { "gemini_test_key_missing" }
        require(model.matches(Regex("[a-z0-9.-]{1,80}"))) { "invalid_gemini_model" }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(requestBody.toString().toRequestBody(jsonType))
            .build()
        var retryIndex = 0
        while (true) {
            try {
                return execute(request)
            } catch (failure: DirectGeminiApiException) {
                val waitMs = directGemini503RetryDelayMs(failure.httpStatus, retryIndex) ?: throw failure
                delay(waitMs)
                retryIndex++
            }
        }
    }

    private suspend fun execute(request: Request): JSONObject {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { http ->
                        try {
                            val body = http.body ?: throw DirectGeminiApiException(http.code, "empty_response")
                            if ((body.contentLength() ?: 0L) > MAX_RESPONSE_BYTES) {
                                throw DirectGeminiApiException(http.code, "response_too_large")
                            }
                            val raw = body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size().toLong() + count > MAX_RESPONSE_BYTES) {
                                        throw DirectGeminiApiException(http.code, "response_too_large")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }
                            if (!http.isSuccessful) {
                                val safeStatus = runCatching {
                                    JSONObject(raw).optJSONObject("error")?.optString("status")
                                }.getOrNull()?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) }
                                throw DirectGeminiApiException(http.code, safeStatus ?: "provider_rejected")
                            }
                            val result = JSONObject(raw)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
    }
}

/** Two bounded exponential-backoff retries only for Google's temporary capacity response. */
internal fun directGemini503RetryDelayMs(httpStatus: Int, retryIndex: Int): Long? {
    if (httpStatus != 503 || retryIndex !in 0 until 2) return null
    return 500L shl retryIndex
}

internal class DirectGeminiApiException(val httpStatus: Int, val safeProviderStatus: String) :
    IOException("direct_gemini_http_$httpStatus")
