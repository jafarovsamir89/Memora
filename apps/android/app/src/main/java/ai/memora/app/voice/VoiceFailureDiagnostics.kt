package ai.memora.app.voice

import ai.memora.app.ai.DirectGeminiApiException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** Safe, content-free failure codes for the hold-to-talk path. Never expose exception messages. */
internal object VoiceFailureDiagnostics {
    fun code(stage: String, error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        causes.filterIsInstance<DirectGeminiApiException>().firstOrNull()?.let { failure ->
            return when (failure.httpStatus) {
                400 -> "${stage}_gemini_request_rejected"
                401 -> "${stage}_gemini_key_invalid"
                403 -> "${stage}_gemini_access_denied"
                404 -> "${stage}_gemini_model_unavailable"
                429 -> "${stage}_gemini_quota_or_rate_limit"
                in 500..599 -> "${stage}_gemini_service_error_${failure.httpStatus}"
                else -> "${stage}_gemini_http_${failure.httpStatus}"
            }
        }
        val sdkCode = causes.firstNotNullOfOrNull { cause ->
            when (cause.javaClass.simpleName) {
                "APINotConfiguredException" -> "firebase_ai_not_configured"
                "ServiceDisabledException" -> "firebase_ai_service_disabled"
                "InvalidAPIKeyException" -> "firebase_project_config_invalid"
                "QuotaExceededException" -> "quota_or_rate_limit"
                "RequestTimeoutException" -> "request_timeout"
                "ServiceConnectionHandshakeFailedException" -> "connection_handshake_failed"
                "PermissionMissingException" -> "permission_missing"
                "UnsupportedUserLocationException" -> "location_unsupported"
                else -> null
            }
        }
        if (sdkCode != null) return "${stage}_$sdkCode"
        if (causes.any { it is SocketTimeoutException || it is TimeoutException }) return "${stage}_network_timeout"
        if (causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }) {
            return "${stage}_network_unavailable"
        }

        val safeMessages = causes.mapNotNull { it.message }
        safeMessages.firstNotNullOfOrNull { message ->
            Regex("app_check_debug_http_(\\d{3})").find(message)?.groupValues?.get(1)
        }?.let { return "${stage}_app_check_$it" }

        safeMessages.firstNotNullOfOrNull { message ->
            Regex("Transcribe connection (?:failed|closed) \\((\\d{3})\\)").find(message)?.groupValues?.get(1)
        }?.let { status ->
            return when (status) {
                "401", "403" -> "${stage}_access_denied_$status"
                "404" -> "${stage}_model_or_api_unavailable_$status"
                "429" -> "${stage}_quota_or_rate_limit_$status"
                in "500".."599" -> "${stage}_google_service_error_$status"
                else -> "${stage}_connection_error_$status"
            }
        }

        if (safeMessages.any { it == "Transcribe setup rejected" }) return "${stage}_setup_rejected"
        if (safeMessages.any { it == "App Check is required" }) return "${stage}_app_check_missing"
        return "${stage}_failed"
    }
}
