package ai.memora.app.agent

import ai.memora.app.ai.GeminiAgentModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DirectGeminiResponseTest {
    @Test fun generationConfigKeepsGemini36TemperatureAtDocumentedDefault() {
        val config = geminiRestGenerationConfig(GeminiAgentModel.FLASH_36)
        assertEquals(768, config.getInt("max_output_tokens"))
        assertTrue(!config.has("temperature"))
    }

    @Test fun lightweightModelsKeepTheExistingSamplingConfig() {
        listOf(GeminiAgentModel.FLASH_LITE_35, GeminiAgentModel.FLASH_LITE_31).forEach { model ->
            val config = geminiRestGenerationConfig(model)
            assertEquals(768, config.getInt("max_output_tokens"))
            assertEquals(0.1, config.getDouble("temperature"), 0.0)
        }
    }

    @Test fun recognizesCanonicalCamelCaseFunctionCallAndPreservesThoughtSignature() {
        val parsed = parseDirectGeminiAgentContent(JSONObject("""
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"save_test_marker","args":{"marker":"APPLE-42"}},"thoughtSignature":"opaque-test-signature"}
            ]}}]}
        """))

        assertEquals(1, parsed.functionCalls.size)
        assertEquals("save_test_marker", parsed.functionCalls.single().optString("name"))
        assertEquals("APPLE-42", parsed.functionCalls.single().getJSONObject("args").getString("marker"))
        assertEquals("opaque-test-signature", parsed.modelContent.getJSONArray("parts")
            .getJSONObject(0).getString("thoughtSignature"))
        assertTrue(parsed.answer.isEmpty())
    }

    @Test fun acceptsLegacySnakeCaseFunctionCallAndExtractsText() {
        val parsed = parseDirectGeminiAgentContent(JSONObject("""
            {"candidates":[{"content":{"parts":[
              {"function_call":{"name":"read_test_marker","args":{}}},
              {"text":"Salam!"}
            ]}}]}
        """))

        assertEquals("read_test_marker", parsed.functionCalls.single().optString("name"))
        assertEquals("Salam!", parsed.answer)
    }
}
