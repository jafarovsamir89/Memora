package ai.memora.app.ai

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.appcheck.AppCheckProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val DEBUG_APP_CHECK_PREFS = "memora-app-check-debug-v1"
internal const val DEBUG_APP_CHECK_TOKEN_KEY = "firebase_debug_token"

class ConsoleDebugAppCheckProviderFactory : AppCheckProviderFactory {
    override fun create(firebaseApp: FirebaseApp): AppCheckProvider = ConsoleDebugAppCheckProvider(firebaseApp)
}

private class ConsoleDebugAppCheckProvider(firebaseApp: FirebaseApp) : AppCheckProvider {
    private val app = firebaseApp
    private val context = firebaseApp.applicationContext
    private val fallback by lazy { DebugAppCheckProviderFactory.getInstance().create(firebaseApp) }

    override fun getToken(): Task<AppCheckToken> = fetchToken(limitedUse = false)

    override fun getLimitedUseToken(): Task<AppCheckToken> = fetchToken(limitedUse = true)

    private fun fetchToken(limitedUse: Boolean): Task<AppCheckToken> {
        val debugToken = context.getSharedPreferences(DEBUG_APP_CHECK_PREFS, Context.MODE_PRIVATE)
            .getString(DEBUG_APP_CHECK_TOKEN_KEY, null)?.trim().orEmpty()
        if (debugToken.isEmpty()) {
            return if (limitedUse) fallback.getLimitedUseToken() else fallback.getToken()
        }

        val result = TaskCompletionSource<AppCheckToken>()
        executor.execute {
            try {
                result.setResult(exchange(debugToken, limitedUse))
            } catch (failure: AppCheckDebugFailure) {
                result.setException(failure)
            } catch (_: Exception) {
                result.setException(AppCheckDebugFailure())
            }
        }
        return result.task
    }

    private fun exchange(debugToken: String, limitedUse: Boolean): AppCheckToken {
        val options = app.options
        val projectId = options.projectId?.takeIf(String::isNotBlank) ?: throw AppCheckDebugFailure()
        val appId = options.applicationId.takeIf(String::isNotBlank) ?: throw AppCheckDebugFailure()
        val apiKey = options.apiKey.takeIf(String::isNotBlank) ?: throw AppCheckDebugFailure()
        val url = "https://firebaseappcheck.googleapis.com/v1/projects/$projectId/apps/$appId:exchangeDebugToken"
            .toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build()
        val payload = JSONObject()
            .put("debugToken", debugToken)
            .put("limitedUse", limitedUse)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(payload).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AppCheckDebugFailure(response.code)
            val body = response.body?.string()?.let(::JSONObject) ?: throw AppCheckDebugFailure()
            val token = body.optString("token").takeIf(String::isNotBlank) ?: throw AppCheckDebugFailure()
            val ttlMillis = parseTtlMillis(body.optString("ttl")) ?: throw AppCheckDebugFailure()
            return ConsoleDebugAppCheckToken(token, System.currentTimeMillis() + ttlMillis - EXPIRY_SKEW_MILLIS)
        }
    }

    private fun parseTtlMillis(ttl: String): Long? {
        val match = TTL_PATTERN.matchEntire(ttl) ?: return null
        val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * 1_000.0).toLong()
    }

    private class ConsoleDebugAppCheckToken(
        private val value: String,
        private val expiresAtMillis: Long,
    ) : AppCheckToken() {
        override fun getToken(): String = value
        override fun getExpireTimeMillis(): Long = expiresAtMillis
    }

    private companion object {
        val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "memora-app-check-debug").apply { isDaemon = true }
        }
        val client = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val TTL_PATTERN = Regex("^([0-9]+(?:\\.[0-9]+)?)s$")
        const val EXPIRY_SKEW_MILLIS = 60_000L
    }
}

internal class AppCheckDebugFailure(val httpStatus: Int? = null) : FirebaseException(
    httpStatus?.let { "app_check_debug_http_$it" } ?: "app_check_debug_exchange_failed",
)
