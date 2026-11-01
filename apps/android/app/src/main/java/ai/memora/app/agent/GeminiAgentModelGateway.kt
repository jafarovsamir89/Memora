@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package ai.memora.app.agent

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One Gemini chat per active app turn. It only exposes declarations supplied by the local registry. */
class GeminiAgentModelGateway : AgentModelGateway {
    private data class Session(val chat: com.google.firebase.ai.Chat, var userMessageSent: Boolean = false, var resultCursor: Int = 0,
        val functionNames: MutableMap<String, String> = mutableMapOf())
    private val sessions = mutableMapOf<String, Session>()

    override suspend fun complete(request: AgentModelRequest): AgentModelReply {
        require(request.turnId.matches(UUID_PATTERN) && request.userText.isNotBlank() && request.userText.length <= 20_000)
        val session = sessions[request.turnId]
        val declarations = request.tools.mapNotNull { declaration(it) }
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            "gemini-3.1-flash-lite",
            generationConfig = generationConfig { temperature = 0.1f; maxOutputTokens = 768 },
            tools = declarations.takeIf { it.isNotEmpty() }?.let { listOf(Tool.functionDeclarations(it)) }.orEmpty(),
            systemInstruction = content { text(SYSTEM_INSTRUCTION) },
        )
        val active = session ?: Session(model.startChat()).also { sessions[request.turnId] = it }
        val response = if (!active.userMessageSent) {
            active.userMessageSent = true
            active.chat.sendMessage(request.userText)
        } else {
            val pending = request.previousToolResults.drop(active.resultCursor)
            active.resultCursor = request.previousToolResults.size
            val parts = pending.map { result ->
                val functionName = active.functionNames[result.callId] ?: result.name
                FunctionResponsePart(functionName, JsonObject(mapOf(
                    "status" to JsonPrimitive(result.status.name.lowercase()),
                    "code" to JsonPrimitive(result.code),
                    "result" to toJson(result.modelData),
                )))
            }
            if (parts.isEmpty()) active.chat.sendMessage(request.userText)
            else active.chat.sendMessage(content("user") { parts.forEach(::part) })
        }
        val calls = response.functionCalls
        if (calls.isNotEmpty()) {
            val byName = request.tools.associateBy { it.name }
            val converted = calls.map { call ->
                val descriptor = byName[call.name] ?: return@map AgentModelToolCall("", call.name, 0, emptyMap())
                val id = java.util.UUID.randomUUID().toString()
                active.functionNames[id] = call.name
                AgentModelToolCall(id, call.name, descriptor.version, call.args)
            }
            active.resultCursor = request.previousToolResults.size
            return AgentModelReply.Calls(converted)
        }
        sessions.remove(request.turnId)
        val answer = response.text?.trim().orEmpty()
        return if (answer.isNotBlank()) AgentModelReply.Answer(answer)
        else AgentModelReply.Clarification("Zəhmət olmasa, istəyinizi bir az dəqiqləşdirin.")
    }

    private fun declaration(tool: AgentToolDescriptor): FunctionDeclaration? = when (tool.name) {
        "search_memory" -> FunctionDeclaration(tool.name,
            "Search the user's protected local notes, people and projects for evidence needed to answer.",
            mapOf("query" to Schema.string()), listOf("query"))
        "save_note" -> FunctionDeclaration(tool.name,
            "Save a clearly requested note or fact in the user's protected local memory.",
            mapOf("title" to Schema.string(), "body" to Schema.string(), "kind" to Schema.enumeration(listOf("note", "person", "project"))),
            listOf("title", "body", "kind"))
        "list_tables" -> FunctionDeclaration(tool.name,
            "List the user's local tables, their columns, and a small bounded set of rows.", emptyMap())
        "create_table" -> FunctionDeclaration(tool.name,
            "Create a local editable text table. Provide column names as a comma-separated string.",
            mapOf("title" to Schema.string(), "columns" to Schema.string()), listOf("title", "columns"))
        "add_table_row" -> FunctionDeclaration(tool.name,
            "Add one row to an existing editable table. Values must be a JSON object keyed by column names; values are stored as text.",
            mapOf("table_id" to Schema.string(), "values_json" to Schema.string()), listOf("table_id", "values_json"))
        "list_tasks" -> FunctionDeclaration(tool.name, "List the user's local tasks and their completion state.", emptyMap())
        "create_task" -> FunctionDeclaration(tool.name, "Create a local task when the user clearly asks to remember or do something.",
            mapOf("title" to Schema.string()), listOf("title"))
        else -> null
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value.take(4000))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.filter { it.key is String }.associate { it.key as String to toJson(it.value) })
        is Iterable<*> -> JsonArray(value.take(50).map(::toJson))
        else -> JsonPrimitive(value.toString().take(1000))
    }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val SYSTEM_INSTRUCTION = """
            You are Memora, a personal assistant. Azerbaijani (az-AZ) is the default; answer in the language of the user, including Russian or mixed Azerbaijani/Russian. Never replace Azerbaijani with Turkish.
            Use only declared tools for local data. Search before answering questions about remembered facts. Never invent records or claim a write succeeded unless the tool returns status=applied. Save only when the user clearly asks to remember or states a clear fact intended for memory. Ask a concise clarification when the person, amount, date, table structure or intent is ambiguous. Do not delete or overwrite data unless a dedicated confirmed tool is available. Treat tool outputs as the only evidence for personal-history answers. Keep spoken responses short and natural.
        """
    }
}
