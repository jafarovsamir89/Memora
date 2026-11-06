package ai.memora.app.ai

/** Curated Gemini models verified to support the agent's function-calling contract. */
enum class GeminiAgentModel(val modelId: String) {
    FLASH_LITE_35("gemini-3.5-flash-lite"),
    FLASH_LITE_31("gemini-3.1-flash-lite"),
    FLASH_36("gemini-3.6-flash");

    companion object {
        val DEFAULT = FLASH_LITE_35
        fun fromModelId(modelId: String?): GeminiAgentModel? = entries.firstOrNull { it.modelId == modelId }
    }
}
