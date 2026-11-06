package ai.memora.app.data

import android.content.Context
import ai.memora.app.ai.GeminiAgentModel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeminiAgentModelSelectionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("assistant-model-selection-v1", Context.MODE_PRIVATE)

    @Test fun defaultsToExistingModelAndPersistsSelectionAcrossStoreRecreation() {
        preferences.edit().clear().commit()
        val store = GeminiAgentModelSelectionStore(context)
        assertEquals(GeminiAgentModel.DEFAULT, store.selectedModel())

        assertTrue(store.save(GeminiAgentModel.FLASH_LITE_31))
        assertEquals(GeminiAgentModel.FLASH_LITE_31, GeminiAgentModelSelectionStore(context).selectedModel())
    }

    @Test fun unknownStoredModelUsesExistingDefaultWithoutDestroyingStoredValue() {
        preferences.edit().putString("gemini-agent-model-id", "not-a-supported-model").commit()
        val store = GeminiAgentModelSelectionStore(context)
        assertEquals(GeminiAgentModel.DEFAULT, store.selectedModel())
        assertEquals("not-a-supported-model", preferences.getString("gemini-agent-model-id", null))
    }

    @Test fun catalogContainsOnlyFunctionCallingModels() {
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash"),
            GeminiAgentModel.entries.map { it.modelId },
        )
        assertTrue(GeminiAgentModel.fromModelId("gemini-3.1-flash-lite") == GeminiAgentModel.FLASH_LITE_31)
        assertFalse(GeminiAgentModel.fromModelId("gemini-3.5-transcribe-live") != null)
    }
}
