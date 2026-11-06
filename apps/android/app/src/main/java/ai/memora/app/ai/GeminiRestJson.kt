package ai.memora.app.ai

import org.json.JSONObject

/** Gemini REST responses use Proto JSON camelCase; accept snake_case for older fixtures/providers. */
internal fun JSONObject.optGeminiObject(camelCaseField: String, snakeCaseField: String): JSONObject? =
    optJSONObject(camelCaseField) ?: optJSONObject(snakeCaseField)

internal fun JSONObject.optGeminiString(camelCaseField: String, snakeCaseField: String): String =
    optString(camelCaseField).takeIf(String::isNotBlank) ?: optString(snakeCaseField)
