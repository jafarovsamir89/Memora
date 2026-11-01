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
        if (answer.isBlank()) {
            val latest = request.previousToolResults.lastOrNull()
            if (latest?.status == ToolObservationStatus.APPLIED && latest.name in WRITE_TOOLS)
                return AgentModelReply.Answer("Hər şey hazırdır.")
            if (latest?.status == ToolObservationStatus.APPLIED)
                return AgentModelReply.Answer("Məlumatı tapdım, amma cavabı hazırlaya bilmədim.")
        }
        return if (answer.isNotBlank()) AgentModelReply.Answer(answer)
        else AgentModelReply.Clarification("Zəhmət olmasa, istəyinizi bir az dəqiqləşdirin.")
    }

    private fun declaration(tool: AgentToolDescriptor): FunctionDeclaration? = when (tool.name) {
        "search_memory" -> FunctionDeclaration(tool.name,
            "Search the user's protected local notes, people and projects for evidence needed to answer.",
            mapOf("query" to Schema.string()), listOf("query"))
        "search_money" -> FunctionDeclaration(tool.name,
            "Search the protected local financial memory for a person's receipts, debts, dates, and comments. Search before answering personal financial history; do not infer missing records.",
            mapOf("person_name" to Schema.string()), listOf("person_name"))
        "record_money_event" -> FunctionDeclaration(tool.name,
            "Record a money event the user clearly stated or asked to remember. Always call search_money for the person first so the event attaches to an existing counterparty; when no person exists, the app creates one. Use kind=receipt only when the named person gave money to the user; use kind=debt only when that person owes the user. Extract exact amount, AZN currency, date if stated, and an optional reason/comment. If direction, amount, or person is ambiguous, ask one concise question instead of calling this tool. Preserve the full original utterance as source; do not invent a reason. If no date was stated, the app records today's date in Asia/Baku.",
            mapOf("kind" to Schema.enumeration(listOf("receipt", "debt")), "person_name" to Schema.string(), "amount" to Schema.string(),
                "currency" to Schema.string(), "occurred_date" to Schema.string(), "comment" to Schema.string()),
            listOf("kind", "person_name", "amount"))
        "save_note" -> FunctionDeclaration(tool.name,
            "Save a clearly requested note or fact in the user's protected local memory.",
            mapOf("title" to Schema.string(), "body" to Schema.string(), "kind" to Schema.enumeration(listOf("note", "person", "project"))),
            listOf("title", "body", "kind"))
        "edit_memory_object" -> FunctionDeclaration(tool.name,
            "Edit an existing note, person, or project. Search first and pass only the fields the user wants changed.",
            mapOf("object_id" to Schema.string(), "title" to Schema.string(), "body" to Schema.string()), listOf("object_id"))
        "delete_memory_object" -> FunctionDeclaration(tool.name,
            "Soft-delete a note, person, or project only when the user clearly asks. The change is reversible.",
            mapOf("object_id" to Schema.string()), listOf("object_id"))
        "restore_memory_object" -> FunctionDeclaration(tool.name,
            "Restore a previously soft-deleted note, person, or project when the user asks to undo deletion.",
            mapOf("object_id" to Schema.string()), listOf("object_id"))
        "link_objects" -> FunctionDeclaration(tool.name,
            "Create a relationship between two existing local memory objects after resolving their IDs with search_memory.",
            mapOf("from_id" to Schema.string(), "to_id" to Schema.string(),
                "relation" to Schema.enumeration(listOf("related_to", "works_on", "owes", "family_of", "part_of"))),
            listOf("from_id", "to_id", "relation"))
        "list_tables" -> FunctionDeclaration(tool.name,
            "List the user's local tables, their columns, and a small bounded set of rows.", emptyMap())
        "read_table_rows" -> FunctionDeclaration(tool.name,
            "Read a bounded set of rows from one local table. Optionally filter rows by a literal substring in their cell values.",
            mapOf("table_id" to Schema.string(), "query" to Schema.string()), listOf("table_id", "query"))
        "create_table" -> FunctionDeclaration(tool.name,
            "Create a local editable text table. Provide column names as a comma-separated string.",
            mapOf("title" to Schema.string(), "columns" to Schema.string()), listOf("title", "columns"))
        "add_table_row" -> FunctionDeclaration(tool.name,
            "Add one row to an existing editable table. Values must be a JSON object keyed by column names; values are stored as text.",
            mapOf("table_id" to Schema.string(), "values_json" to Schema.string()), listOf("table_id", "values_json"))
        "update_table_row" -> FunctionDeclaration(tool.name,
            "Update only the specified cells in an existing editable table row. Search or read rows first to resolve the table and row.",
            mapOf("table_id" to Schema.string(), "row_id" to Schema.string(), "values_json" to Schema.string()),
            listOf("table_id", "row_id", "values_json"))
        "delete_table_row" -> FunctionDeclaration(tool.name,
            "Soft-delete one row from an editable table when the user clearly asks. The change can be undone.",
            mapOf("table_id" to Schema.string(), "row_id" to Schema.string()), listOf("table_id", "row_id"))
        "restore_table_row" -> FunctionDeclaration(tool.name,
            "Restore a previously soft-deleted table row when the user asks to undo deletion.",
            mapOf("table_id" to Schema.string(), "row_id" to Schema.string()), listOf("table_id", "row_id"))
        "rename_table" -> FunctionDeclaration(tool.name,
            "Rename an existing user table, preserving its rows and schema.",
            mapOf("table_id" to Schema.string(), "title" to Schema.string()), listOf("table_id", "title"))
        "delete_table" -> FunctionDeclaration(tool.name,
            "Soft-delete a user table only when the user clearly asks. Rows remain recoverable by restoring the table.",
            mapOf("table_id" to Schema.string()), listOf("table_id"))
        "restore_table" -> FunctionDeclaration(tool.name,
            "Restore a previously soft-deleted user table when the user asks to undo deletion.",
            mapOf("table_id" to Schema.string()), listOf("table_id"))
        "list_tasks" -> FunctionDeclaration(tool.name, "List the user's local tasks and their completion state.", emptyMap())
        "create_task" -> FunctionDeclaration(tool.name, "Create a local task when the user clearly asks to remember or do something.",
            mapOf("title" to Schema.string()), listOf("title"))
        "set_task_completion" -> FunctionDeclaration(tool.name,
            "Mark an existing local task complete or incomplete when the user clearly requests that change.",
            mapOf("task_id" to Schema.string(), "completed" to Schema.boolean()), listOf("task_id", "completed"))
        "list_calendar_events" -> FunctionDeclaration(tool.name,
            "List local calendar events within the requested ISO local datetime range; use a bounded range.",
            mapOf("from_local" to Schema.string(), "until_local" to Schema.string()), listOf("from_local", "until_local"))
        "create_calendar_event" -> FunctionDeclaration(tool.name,
            "Create a local calendar event only when the user clearly requested it and its date/time and timezone are known. Use ISO local date for date precision or ISO local datetime for minute precision.",
            mapOf("title" to Schema.string(), "start_local" to Schema.string(), "end_local" to Schema.string(),
                "zone_id" to Schema.string(), "precision" to Schema.enumeration(listOf("date", "minute"))),
            listOf("title", "start_local", "zone_id", "precision"))
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
        private val WRITE_TOOLS = setOf("save_note", "record_money_event", "link_objects", "edit_memory_object",
            "delete_memory_object", "restore_memory_object", "create_table", "add_table_row", "update_table_row",
            "delete_table_row", "restore_table_row", "rename_table", "delete_table", "restore_table", "create_task",
            "set_task_completion", "create_calendar_event")
        private const val SYSTEM_INSTRUCTION = """
            You are Memora, the user's personal secretary. Interpret the meaning and intent of natural speech; do not require command phrases or exact wording. Azerbaijani (az-AZ) is the default; answer in the user's language, including Russian and mixed Azerbaijani/Russian. Never substitute Turkish for Azerbaijani.
            Use declared tools to read and change the user's local data. Search relevant saved memory before answering questions or resolving a person, project, table, task, or prior context. Always search the local finance store for the named counterparty before recording a money event, so an existing person record is reused. Treat clear real-world statements about the user's people, projects, events, commitments, and money as facts to remember unless they are questions, hypothetical, future plans, negated, or clearly not about the user. Write the semantic fact with the appropriate tool, preserving the original utterance as its source. For money, distinguish money received from a debt owed; never reverse direction, invent an amount/date/reason, or convert currencies. When the user omits a date, the app uses the current date in Asia/Baku.
            Ask one concise follow-up only when essential details are ambiguous or missing. A reason/comment is optional and must not block an otherwise clear record. Treat tool results as the sole evidence that an action succeeded; never claim a write succeeded unless status=applied. Keep spoken answers short and natural. Refuse only actions unavailable through the declared tools or denied by app permissions.
        """
    }
}
