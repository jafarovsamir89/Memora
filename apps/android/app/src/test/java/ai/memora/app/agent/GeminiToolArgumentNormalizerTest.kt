package ai.memora.app.agent

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GeminiToolArgumentNormalizerTest {
    @Test fun preservesIntegerDecimalAndNestedFirebaseNumbers() {
        val normalized = GeminiToolArgumentNormalizer.normalize(mapOf(
            "integer" to 100,
            "decimal" to BigDecimal("100.50"),
            "nested" to mapOf("fraction" to 0.25),
            "items" to listOf(1L, BigDecimal("2.75")),
        ))

        assertEquals(100L, normalized["integer"])
        assertEquals("100.5", normalized["decimal"])
        assertEquals(mapOf("fraction" to "0.25"), normalized["nested"])
        assertEquals(listOf(1L, "2.75"), normalized["items"])
    }

    @Test fun rejectsUnsupportedValueInsteadOfReplacingTheWholeArgumentMapWithEmptyMap() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiToolArgumentNormalizer.normalize(mapOf("amount" to 100L, "unsupported" to Any()))
        }
    }

    @Test fun normalizesKotlinxJsonValuesReturnedByFirebaseFunctionCalls() {
        val normalized = GeminiToolArgumentNormalizer.normalize(mapOf(
            "kind" to JsonPrimitive("receipt"),
            "amount" to JsonPrimitive("100.50"),
            "count" to JsonPrimitive(2),
            "metadata" to JsonObject(mapOf("enabled" to JsonPrimitive(true), "rate" to JsonPrimitive(0.125))),
            "items" to JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive(3))),
        ))

        assertEquals("receipt", normalized["kind"])
        assertEquals("100.50", normalized["amount"])
        assertEquals(2L, normalized["count"])
        assertEquals(mapOf("enabled" to true, "rate" to "0.125"), normalized["metadata"])
        assertEquals(listOf("one", 3L), normalized["items"])
    }

    @Test fun rejectsNonFiniteNumbers() {
        assertThrows(NumberFormatException::class.java) {
            GeminiToolArgumentNormalizer.normalize(mapOf("amount" to Double.NaN))
        }
    }
}
