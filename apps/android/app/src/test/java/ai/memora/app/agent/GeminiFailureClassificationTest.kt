package ai.memora.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiFailureClassificationTest {
    @Test fun emptyResponseIsAnExplicitFailureInsteadOfInventedSuccess() {
        listOf(null, "", " \n\t").forEach { text ->
            val failure = assertThrows(AgentGatewayFailure::class.java) { requireGeminiFinalAnswer(text) }
            assertEquals("model_empty_response", failure.safeCode)
        }
    }

    @Test fun nonemptyFinalAnswerKeepsTheModelText() {
        assertEquals(AgentModelReply.Answer("Saxlanıldı."), requireGeminiFinalAnswer("  Saxlanıldı. \n"))
    }

    @Test fun quotaExceptionIsReportedAsRateLimited() {
        val result = classifyGeminiFailure(null, setOf("AgentGatewayFailure", "QuotaExceededException"), null)

        assertEquals("model_rate_limited", result.safeCode)
        assertEquals("quota_exceeded", result.category)
    }

    @Test fun httpQuotaStatusIsReportedAsRateLimited() {
        val result = classifyGeminiFailure(429, setOf("ServerException"), null)

        assertEquals("model_rate_limited", result.safeCode)
        assertEquals("quota_exceeded", result.category)
    }

    @Test fun permissionFailuresRemainDistinctFromQuota() {
        val result = classifyGeminiFailure(403, setOf("ServerException"), "permission_denied")

        assertEquals("model_permission_denied", result.safeCode)
        assertEquals("permission_or_app_check_denied", result.category)
    }

    @Test fun unknownFirebaseFailureDoesNotBecomeClarificationOrQuota() {
        val result = classifyGeminiFailure(null, setOf("ServerException"), null)

        assertEquals("model_server_failure", result.safeCode)
        assertEquals("upstream_failure", result.category)
    }
}
