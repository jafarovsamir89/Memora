package ai.memora.app.data

import android.content.Context
import android.content.SharedPreferences
import ai.memora.app.ai.GeminiAgentModel

/** Non-sensitive local preference for the text/tool-calling model used by the assistant. */
class GeminiAgentModelSelectionStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun selectedModel(): GeminiAgentModel = GeminiAgentModel.fromModelId(
        preferences.getString(MODEL_ID_KEY, null),
    ) ?: GeminiAgentModel.DEFAULT

    fun save(model: GeminiAgentModel): Boolean {
        return preferences.edit().putString(MODEL_ID_KEY, model.modelId).commit() && selectedModel() == model
    }

    private companion object {
        const val PREFERENCES_NAME = "assistant-model-selection-v1"
        const val MODEL_ID_KEY = "gemini-agent-model-id"
    }
}
