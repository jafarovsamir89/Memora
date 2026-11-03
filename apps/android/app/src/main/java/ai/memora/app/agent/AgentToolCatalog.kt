package ai.memora.app.agent

/** Provider-neutral JSON-compatible argument types used by every model adapter. */
internal enum class AgentToolValueType { STRING, INTEGER, BOOLEAN, STRING_ARRAY, ENUM }

internal data class AgentToolParameter(
    val name: String,
    val type: AgentToolValueType,
    val optional: Boolean = false,
    val enumValues: List<String> = emptyList(),
)

/** The result envelope stays independent of Firebase/Gemini wire types. */
internal data class AgentToolResultSchema(
    val version: Int = 1,
    val statusField: String = "status",
    val codeField: String = "code",
    val dataField: String = "result",
)

/** Canonical, versioned description of the tool contract exposed to model providers. */
internal data class AgentToolContract(
    val name: String,
    val version: Int,
    val description: String,
    val parameters: List<AgentToolParameter>,
    val result: AgentToolResultSchema = AgentToolResultSchema(),
) {
    val optionalParameters: List<String> get() = parameters.filter { it.optional }.map { it.name }

    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        require(version > 0 && description.isNotBlank())
        require(parameters.map { it.name }.distinct().size == parameters.size)
        require(parameters.all { parameter ->
            parameter.name.matches(Regex("[a-z][a-z0-9_]{0,63}")) &&
                ((parameter.type == AgentToolValueType.ENUM) == parameter.enumValues.isNotEmpty()) &&
                parameter.enumValues.distinct().size == parameter.enumValues.size
        })
        require(result.version > 0)
    }
}

/**
 * Single source of truth for model-visible function names, versions, argument schemas and result envelope.
 * This catalog intentionally contains no provider SDK classes or domain repository operations.
 */
internal object AgentToolCatalog {
    private val definitions = listOf(
        tool("search_conversation_history",
            "Search encrypted local conversation history for relevant prior discussion. Use query terms when known or days_back for a time-only question. Returns at most eight snippets; assistant messages are not proof of personal facts.",
            optional = mapOf("query" to str, "days_back" to int, "max_results" to int)),
        tool("read_conversation_turn",
            "Read the exact retained USER message for one turn_id returned by search_conversation_history or search_memory. Use to verify the source of an episode summary before making a personal-history claim. Does not return the assistant reply.",
            required = mapOf("turn_id" to str)),
        tool("search_memory",
            "Search protected local notes, people, explicitly owner-entered exact person aliases, projects, explicit personal profile facts, episodic summaries, and relevant linked cases, ledger events, tasks, and calendar events. Results are bounded and scoped; each domain hit includes its canonical source ID/revision. An alias is identity evidence only for its exact spelling; using it for finance requires an existing owner-confirmed identity link. Use search_money for exact balances/totals and search_cases for a complete case timeline. Profile facts are user-stated or user-corrected and include source references and revisions.",
            required = mapOf("query" to str)),
        tool("save_profile_fact",
            "Save one concise, non-sensitive, stable fact or preference about the user that is explicitly stated in the current message. Use only for the user's own preferences, goals, or work/habit context; never infer, store sensitive traits, names, direct identifiers, or facts about other people. Keep the user's display name and time zone in local profile settings. The app binds the source to the exact current user message.",
            required = mapOf("category" to enumeration("preference", "goal", "context", "other"), "fact" to str)),
        tool("edit_profile_fact",
            "Correct one exact profile fact returned by search_memory after the user asks to change or correct it. Supply its current fact_id and revision. The app links the correction to the exact current user message.",
            required = mapOf("fact_id" to str, "expected_revision" to int, "fact" to str),
            optional = mapOf("category" to enumeration("preference", "goal", "context", "other"))),
        tool("delete_profile_fact",
            "Permanently forget one exact profile fact returned by search_memory when the user asks. Supply its current fact_id and revision; the fact and all its revisions and source links are removed.",
            required = mapOf("fact_id" to str, "expected_revision" to int)),
        tool("search_money",
            "Search the protected ledger by person or literal comment/source text. Supply person_name or query; kind is optional. Returns bounded events, exact person-wide AZN totals when available, and completeness metadata.",
            optional = mapOf("person_name" to str, "query" to str, "kind" to enumeration("receipt", "debt", "expense"))),
        tool("analyze_finance_period",
            "Read exact monthly receipt, expense and debt-origin totals from the protected ledger; includes prior-month comparison, current outstanding debts and explicit incomplete-coverage flags. This is not profit/accounting or full-business coverage.",
            optional = mapOf("month" to str)),
        tool("summarize_work_status",
            "Read the current protected task projection and exact case counts/statuses with a bounded recent case sample. This is a present-state snapshot, not dated productivity, revenue, or proof of business performance."),
        tool("record_money_event",
            "Record a clearly stated receipt, debt, or expense. Preserve the exact amount and original source. A missing date defaults to today in Asia/Baku; reason and date are optional and must not block a clear write. Reuse or create a person atomically.",
            required = mapOf("kind" to enumeration("receipt", "debt", "expense"), "person_name" to str, "amount" to str),
            optional = mapOf("currency" to str, "occurred_date" to str, "comment" to str)),
        tool("settle_debt",
            "Record a repayment against one exact debt ID. Search first. A cash return has one receipt and one linked allocation; reuse a receipt only when its provenance proves it is the same payment. Never block on an optional reason/date.",
            required = mapOf("obligation_id" to str, "amount" to str, "payment_event_id" to str),
            optional = mapOf("occurred_date" to str, "reason" to str)),
        tool("reverse_money_event",
            "Append a reversal for one exact financial event only when the user explicitly asks to undo it. Search first; the ledger preserves its audit history.",
            required = mapOf("event_id" to str), optional = mapOf("reason" to str)),
        tool("correct_money_event",
            "Correct the amount of one exact event after searching the ledger. Keep its identity and history, and atomically adjust an existing repayment allocation when needed. Never create a duplicate receipt.",
            required = mapOf("event_id" to str, "new_amount" to str), optional = mapOf("reason" to str)),
        tool("annotate_money_event",
            "Append a later explanation to one exact existing financial event without changing its amount or creating another transaction. Search first.",
            required = mapOf("event_id" to str)),
        tool("save_memory_episode",
            "Use this for a concise durable summary, decision, agreement, commitment, or unresolved loop discussed across earlier conversation turns. First call search_conversation_history and use only its returned user turn_id values; never cite assistant turns, current unarchived speech, or invented IDs. Preserve uncertainty and keep the original turns as the source of truth. Do not infer sensitive traits. Do not use save_note as a substitute for a summary of previous conversation.",
            version = 2, required = mapOf("title" to str, "summary" to str, "source_turn_ids" to stringArray)),
        tool("save_note",
            "Save one explicitly requested standalone note or fact from the current user message. Do not use for summaries, decisions, agreements, commitments, or unresolved loops from earlier conversation; use save_memory_episode with archived user turn IDs instead.",
            required = mapOf("title" to str, "body" to str, "kind" to enumeration("note", "person", "project"))),
        tool("delete_memory_episode",
            "Delete one exact derived conversation summary when the user asks. This leaves its source conversation turns unchanged.",
            required = mapOf("episode_id" to str)),
        tool("edit_memory_object",
            "Edit an existing note, person or project. Search first; omit fields the user did not ask to change.",
            required = mapOf("object_id" to str), optional = mapOf("title" to str, "body" to str)),
        tool("delete_memory_object",
            "Soft-delete one identified note, person or project only when the user clearly asks. Deletion is reversible.",
            required = mapOf("object_id" to str)),
        tool("restore_memory_object",
            "Restore one previously soft-deleted note, person or project when the user asks to undo deletion.",
            required = mapOf("object_id" to str)),
        tool("link_objects",
            "Link two existing local memory objects after resolving their IDs with search_memory.",
            required = mapOf("from_id" to str, "to_id" to str,
                "relation" to enumeration("related_to", "works_on", "owes", "family_of", "part_of"))),
        tool("create_case",
            "Create a durable case for an ongoing real situation such as a repair, deal, trip, or unresolved commitment. Keep it distinct from a one-off chat; preserve only supported facts and an optional next action.",
            required = mapOf("title" to str), optional = mapOf("summary" to str, "next_action" to str)),
        tool("search_cases",
            "Search ongoing cases and return their status, participants, next action, and canonical source references. Use this before answering what happened or what remains to do.",
            optional = mapOf("query" to str)),
        tool("update_case",
            "Update one exact case after searching it. Omitted fields remain unchanged. Use clear_next_action only when the user asks to remove the next action.",
            required = mapOf("case_id" to str), optional = mapOf("title" to str, "summary" to str,
                "status" to enumeration("open", "waiting", "completed", "cancelled"), "next_action" to str,
                "clear_next_action" to bool)),
        tool("add_case_participant",
            "Add one resolved canonical person object to a case. Search people first; never merge same-name finance and memory people automatically.",
            required = mapOf("case_id" to str, "person_object_id" to str), optional = mapOf("role" to str)),
        tool("link_case_source",
            "Attach an existing exact local source to a case without copying it. Supported sources are money_event, domain_object, calendar_event, task, and memory_revision. Search and resolve exact IDs first.",
            required = mapOf("case_id" to str, "source_kind" to enumeration("money_event", "domain_object", "calendar_event", "task", "memory_revision"),
                "source_id" to str), optional = mapOf("relation" to str, "source_revision_id" to str)),
        tool("list_tables", "List local tables, their columns, and a bounded set of rows."),
        tool("read_table_rows",
            "Read a bounded set of rows from one local table, optionally filtering by a literal cell substring.",
            required = mapOf("table_id" to str), optional = mapOf("query" to str)),
        tool("create_table",
            "Create a local editable typed table. Prefer columns_json objects with name, type, and optional required; supported types are TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME and MONEY. Legacy comma-separated columns create TEXT columns.",
            required = mapOf("title" to str), optional = mapOf("columns" to str, "columns_json" to str)),
        tool("add_table_row",
            "Add one row to an existing editable table. values_json must match the declared column types; MONEY uses minorUnits and currency.",
            required = mapOf("table_id" to str, "values_json" to str)),
        tool("update_table_row",
            "Update only specified cells of one exact existing row after resolving the table and row.",
            required = mapOf("table_id" to str, "row_id" to str, "values_json" to str)),
        tool("delete_table_row", "Soft-delete one identified table row when the user asks; it can be restored.",
            required = mapOf("table_id" to str, "row_id" to str)),
        tool("restore_table_row", "Restore one previously deleted row when the user asks.",
            required = mapOf("table_id" to str, "row_id" to str)),
        tool("rename_table", "Rename a local table without changing its rows or schema.",
            required = mapOf("table_id" to str, "title" to str)),
        tool("delete_table", "Soft-delete one local table when the user clearly asks; its rows remain recoverable.",
            required = mapOf("table_id" to str)),
        tool("restore_table", "Restore a previously soft-deleted local table when the user asks.",
            required = mapOf("table_id" to str)),
        tool("list_tasks", "List local tasks and their completion state."),
        tool("create_task",
            "Create a local task when asked. For a reminder require a precise local date and time; never invent one. due_local is ISO local date-time, zone_id defaults to Asia/Baku, and remind is true only when requested.",
            required = mapOf("title" to str), optional = mapOf("due_local" to str, "zone_id" to str, "remind" to bool,
                "person_label" to str, "project_label" to str)),
        tool("edit_task",
            "Edit one exact task after resolving its ID. Omitted fields remain unchanged; use clear_due to remove its due date/reminder.",
            required = mapOf("task_id" to str), optional = mapOf("title" to str, "due_local" to str, "zone_id" to str,
                "remind" to bool, "person_label" to str, "project_label" to str, "clear_due" to bool)),
        tool("set_task_completion", "Change the completion state of one identified local task when clearly requested.",
            required = mapOf("task_id" to str, "completed" to bool)),
        tool("list_calendar_events", "List local calendar events in a bounded ISO local datetime range.",
            required = mapOf("from_local" to str, "until_local" to str)),
        tool("create_calendar_event",
            "Create a local calendar event only when requested and its date/time and timezone are known. Use ISO date or local datetime according to precision.",
            required = mapOf("title" to str, "start_local" to str, "zone_id" to str),
            optional = mapOf("end_local" to str, "precision" to enumeration("date", "minute"))),
        tool("edit_calendar_event",
            "Edit one exact local calendar event after listing it. Omitted fields stay unchanged; clear_end removes an end time.",
            required = mapOf("event_id" to str), optional = mapOf("title" to str, "start_local" to str, "end_local" to str,
                "zone_id" to str, "precision" to enumeration("date", "minute"), "clear_end" to bool)),
        tool("cancel_calendar_event",
            "Soft-cancel one identified local event only when explicitly asked. Does not change external calendars.",
            required = mapOf("event_id" to str)),
    )

    private val byKey = definitions.associateBy { it.name to it.version }

    init {
        check(byKey.size == definitions.size) { "duplicate_tool_contract" }
    }

    fun find(name: String, version: Int): AgentToolContract? = byKey[name to version]

    fun require(descriptor: AgentToolDescriptor): AgentToolContract =
        find(descriptor.name, descriptor.version) ?: throw IllegalStateException("missing_tool_contract")

    fun all(): List<AgentToolContract> = definitions

    private fun tool(
        name: String,
        description: String,
        version: Int = 1,
        required: Map<String, AgentToolParameter> = emptyMap(),
        optional: Map<String, AgentToolParameter> = emptyMap(),
    ): AgentToolContract {
        check(required.keys.intersect(optional.keys).isEmpty()) { "duplicate_tool_parameter" }
        return AgentToolContract(name, version, description,
            required.map { (key, parameter) -> parameter.copy(name = key, optional = false) } +
                optional.map { (key, parameter) -> parameter.copy(name = key, optional = true) })
    }

    private val str get() = AgentToolParameter("", AgentToolValueType.STRING)
    private val stringArray get() = AgentToolParameter("", AgentToolValueType.STRING_ARRAY)
    private val int get() = AgentToolParameter("", AgentToolValueType.INTEGER)
    private val bool get() = AgentToolParameter("", AgentToolValueType.BOOLEAN)
    private fun enumeration(vararg values: String) = AgentToolParameter("", AgentToolValueType.ENUM, enumValues = values.toList())
}

/** Stable result vocabulary shared by adapters; provider wire serialization is an adapter concern. */
internal data class ProviderNeutralToolResult(
    val status: ToolObservationStatus,
    val code: String,
    val data: Map<String, Any?>,
)

internal fun AgentToolObservation.toProviderNeutralResult() = ProviderNeutralToolResult(status, code, modelData)
