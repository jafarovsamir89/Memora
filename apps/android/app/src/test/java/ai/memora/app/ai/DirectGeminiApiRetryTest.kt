package ai.memora.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectGeminiApiRetryTest {
    @Test fun retriesServiceUnavailableTwiceWithExponentialBackoff() {
        assertEquals(500L, directGemini503RetryDelayMs(503, 0))
        assertEquals(1_000L, directGemini503RetryDelayMs(503, 1))
        assertNull(directGemini503RetryDelayMs(503, 2))
    }

    @Test fun doesNotRetryOtherProviderStatusesOrInvalidAttemptIndices() {
        assertNull(directGemini503RetryDelayMs(401, 0))
        assertNull(directGemini503RetryDelayMs(429, 0))
        assertNull(directGemini503RetryDelayMs(500, 0))
        assertNull(directGemini503RetryDelayMs(503, -1))
    }
}
