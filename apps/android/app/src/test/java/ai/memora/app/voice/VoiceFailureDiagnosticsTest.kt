package ai.memora.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class VoiceFailureDiagnosticsTest {
    @Test fun reportsSafeTransportStatusWithoutProviderMessage() {
        val error = IllegalStateException("Transcribe connection failed (403)")
        assertEquals("stt_access_denied_403", VoiceFailureDiagnostics.code("stt", error))
    }

    @Test fun mapsAppCheckFailureAndDoesNotLeakTokenLikeText() {
        val error = IllegalStateException("app_check_debug_http_403 token=do-not-display")
        assertEquals("stt_app_check_403", VoiceFailureDiagnostics.code("stt", error))
    }

    @Test fun classifiesNetworkAndTimeoutFailures() {
        assertEquals("stt_network_unavailable", VoiceFailureDiagnostics.code("stt", UnknownHostException("private host")))
        assertEquals("assistant_network_timeout", VoiceFailureDiagnostics.code("assistant", IOException("unrelated").also {
            it.initCause(java.net.SocketTimeoutException("private detail"))
        }))
    }

    @Test fun usesGenericSafeCodeForUnknownProviderExceptions() {
        assertEquals("stt_failed", VoiceFailureDiagnostics.code("stt", IllegalStateException("private utterance and key")))
    }
}
