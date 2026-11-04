package ai.memora.app.agent

import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.FinanceAnalytics
import ai.memora.app.data.protectedstore.TableColumnRow
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.data.protectedstore.FinanceAliasResolution
import ai.memora.app.domain.TaskEvent
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskDecision
import ai.memora.app.domain.TaskRules
import ai.memora.app.domain.VoiceMoneyRules
import java.security.MessageDigest
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/** First production tool slice: bounded evidence search and explicit local memory writes. */
internal class LocalMemoryTools(private val repository: ProtectedDomainRepository, private val memory: ProtectedMemoryRepository,
    private val financeEnabled: Boolean = true, private val onTaskChanged: (Task) -> String = { "not_scheduled" },
    private val conversations: ProtectedConversationRepository? = null,
    private val profileMemory: ProtectedProfileMemoryRepository? = null,
    private val personAliases: PersonAliasRepository? = null,
    private val userTimeZoneId: String? = null,
    private val searchClock: Clock = Clock.systemUTC()) {
    private val userTimeZone: ZoneId by lazy {
        runCatching { userTimeZoneId?.let(ZoneId::of) ?: ZoneId.of("UTC") }.getOrDefault(ZoneId.of("UTC"))
    }
    fun handlers(): List<AgentToolHandler> = listOf(
        SearchMemory(), SaveProfileFact(), EditProfileFact(), DeleteProfileFact(), SaveNote(), SaveMemoryEpisode(), DeleteMemoryEpisode(), EditMemoryObject(), DeleteMemoryObject(), RestoreMemoryObject(), LinkObjects(), ListTables(), ReadTableRows(), CreateTable(), AddTableRow(),
        UpdateTableRow(), DeleteTableRow(), RestoreTableRow(), RenameTable(), DeleteTable(), RestoreTable(),
        SearchConversationHistory(), ReadConversationTurn(), ListTasks(), SummarizeWorkStatus(), CreateTask(), EditTask(), SetTaskCompletion(), ListCalendarEvents(), CreateCalendarEvent(),
        EditCalendarEvent(), CancelCalendarEvent(),
        SearchMoney(), AnalyzeFinancePeriod(), RecordMoneyEvent(), SettleDebt(), ReverseMoneyEvent(), CorrectMoneyEvent(), AnnotateMoneyEvent(),
        CreateCase(), SearchCases(), UpdateCase(), AddCaseParticipant(), LinkCaseSource(),
    )

    private inner class SearchMemory : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_memory", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)

        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val query = call.arguments["query"] as? String ?: return rejected()
            if (query.isBlank() || query.length > 200) return rejected()
            val terms = memorySearchTerms(query)
            if (terms.isEmpty()) return ToolCommitResult(ToolObservationStatus.APPLIED, "evidence_found", mapOf(
                "count" to 0, "match_mode" to "ranked_lexical", "evidence" to emptyList<Any>(), "episodes" to emptyList<Any>(),
                "conversation_turns" to emptyList<Any>(),
                "profile_facts" to emptyList<Any>(), "person_aliases" to emptyList<Any>(), "activity" to emptyList<Any>(), "activity_truncated" to false,
            ))
            val hits = repository.search(turn.scopeId, query, limit = 8)
            val episodes = conversations?.searchEpisodes(turn.scopeId, query, limit = 5).orEmpty()
            val conversationTurns = conversations?.search(turn.scopeId, query, limit = 5).orEmpty()
            val profileFacts = profileMemory?.list(turn.scopeId, query, limit = 8).orEmpty()
            val aliasHits = personAliases?.exactActiveMatches(turn.scopeId, query).orEmpty().mapNotNull { alias ->
                repository.domainObject(turn.scopeId, alias.domainPersonId)?.takeIf { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
                    ?.let { mapOf("person_id" to it.objectId, "canonical_name" to it.title, "alias" to alias.aliasText,
                        "revision" to alias.version, "source_turn_id" to alias.sourceTurnId) }
            }
            val activity = searchRelatedActivity(turn.scopeId, terms, query)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "evidence_found", mapOf(
                "count" to (hits.size + episodes.size + conversationTurns.size + profileFacts.size + aliasHits.size + activity.items.size),
                "match_mode" to "ranked_lexical",
                "truncated" to (hits.size == 8 || episodes.size == 5 || conversationTurns.size == 5 ||
                    profileFacts.size == 8 || activity.truncated),
                "evidence" to hits.map { hit -> mapOf("id" to hit.objectId, "kind" to hit.kind.name.lowercase(),
                    "title" to hit.title, "text" to hit.evidenceText.take(500),
                    "text_truncated" to (hit.evidenceText.length > 500), "revision" to hit.evidenceRevisionId) },
                "episodes" to episodes.map { episode -> mapOf("id" to episode.episodeId, "kind" to "episode_summary",
                    "title" to episode.title, "summary" to episode.summary, "source_turn_ids" to episode.sourceTurnIds,
                    "revision" to episode.revision, "derived_not_canonical" to true) },
                "conversation_turns" to conversationTurns.map { turnEvidence -> mapOf("turn_id" to turnEvidence.turnId,
                    "role" to turnEvidence.role, "created_at_epoch_ms" to turnEvidence.createdAtMillis,
                    "snippet" to turnEvidence.snippet.take(500), "text_truncated" to (turnEvidence.snippet.length > 500),
                    "source" to "retained_conversation_archive") },
                "profile_facts" to profileFacts.map { fact -> mapOf("id" to fact.factId, "kind" to "profile_fact",
                    "category" to fact.category, "fact" to fact.fact, "revision" to fact.revision,
                    "source_turn_ids" to fact.sourceTurnIds, "provenance" to fact.revisions.lastOrNull()?.provenance,
                    "explicit_user_memory" to true) },
                "person_aliases" to aliasHits,
                "activity" to activity.items,
                "activity_truncated" to activity.truncated,
                "activity_limit" to MAX_UNIFIED_ACTIVITY_HITS,
            ))
        }

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) =
            ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class SaveProfileFact : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("save_profile_fact", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = profileMemory != null)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val category = call.arguments["category"] as? String ?: return ToolPreparation.Rejected("invalid_profile_category")
            val fact = call.arguments["fact"] as? String ?: return ToolPreparation.Rejected("invalid_profile_fact")
            if (category !in ProtectedProfileMemoryRepository.CATEGORIES || fact.isBlank() || fact.trim().length > 600)
                return ToolPreparation.Rejected("invalid_profile_fact")
            if (turn.text.isBlank() || turn.text.length > 20_000) return ToolPreparation.Rejected("profile_source_unavailable")
            if (profileMemory == null) return ToolPreparation.Rejected("profile_memory_unavailable")
            val digest = sha256("profile_fact_save\u0000${turn.scopeId}\u0000${turn.turnId}\u0000$category\u0000$fact")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                SaveProfileFactPayload(turn.scopeId, category, fact.trim(), turn.turnId, turn.text)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? SaveProfileFactPayload ?: return rejected()
            return try {
                val saved = profileMemory?.saveUserStated(payload.scope, payload.category, payload.fact,
                    payload.sourceTurnId, payload.sourceText) ?: return ToolCommitResult(ToolObservationStatus.FAILED, "profile_memory_unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "profile_fact_saved", mapOf("fact_id" to saved.factId,
                    "category" to saved.category, "fact" to saved.fact, "revision" to saved.revision,
                    "source_turn_ids" to saved.sourceTurnIds))
            } catch (error: Exception) {
                when (error.message) {
                    "profile_fact_limit_reached" -> ToolCommitResult(ToolObservationStatus.REJECTED, "profile_fact_limit_reached")
                    "profile_fact_source_turn_conflict" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "profile_fact_source_conflict")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "profile_fact_save_failed")
                }
            }
        }
    }

    private inner class EditProfileFact : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_profile_fact", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = profileMemory != null)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["fact_id"] as? String ?: return ToolPreparation.Rejected("invalid_profile_fact_id")
            val expected = exactPositiveLong(call.arguments["expected_revision"]) ?: return ToolPreparation.Rejected("invalid_profile_revision")
            val fact = call.arguments["fact"] as? String ?: return ToolPreparation.Rejected("invalid_profile_fact")
            val store = profileMemory ?: return ToolPreparation.Rejected("profile_memory_unavailable")
            val current = store.get(turn.scopeId, id) ?: return ToolPreparation.Conflict("profile_fact_not_found")
            if (current.revision != expected) return ToolPreparation.Conflict("profile_fact_revision_conflict")
            val category = call.arguments["category"] as? String ?: current.category
            if (category !in ProtectedProfileMemoryRepository.CATEGORIES || fact.isBlank() || fact.trim().length > 600)
                return ToolPreparation.Rejected("invalid_profile_fact")
            val digest = sha256("profile_fact_edit\u0000${turn.scopeId}\u0000$id\u0000$expected\u0000$category\u0000$fact")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to expected), false,
                EditProfileFactPayload(turn.scopeId, id, expected, category, fact.trim(), turn.turnId, turn.text)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? EditProfileFactPayload ?: return rejected()
            return try {
                val saved = profileMemory?.correctFromUser(payload.scope, payload.id, payload.expectedRevision,
                    payload.category, payload.fact, payload.sourceTurnId, payload.sourceText)
                    ?: return ToolCommitResult(ToolObservationStatus.FAILED, "profile_memory_unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "profile_fact_updated", mapOf("fact_id" to saved.factId,
                    "category" to saved.category, "fact" to saved.fact, "revision" to saved.revision,
                    "source_turn_ids" to saved.sourceTurnIds))
            } catch (error: Exception) {
                when (error.message) {
                    "profile_fact_not_found" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "profile_fact_not_found")
                    "profile_fact_revision_conflict" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "profile_fact_revision_conflict")
                    "profile_fact_duplicate" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "profile_fact_duplicate")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "profile_fact_update_failed")
                }
            }
        }
    }

    private inner class DeleteProfileFact : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_profile_fact", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = profileMemory != null)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["fact_id"] as? String ?: return ToolPreparation.Rejected("invalid_profile_fact_id")
            val expected = exactPositiveLong(call.arguments["expected_revision"]) ?: return ToolPreparation.Rejected("invalid_profile_revision")
            val store = profileMemory ?: return ToolPreparation.Rejected("profile_memory_unavailable")
            val current = store.get(turn.scopeId, id) ?: return ToolPreparation.Conflict("profile_fact_not_found")
            if (current.revision != expected) return ToolPreparation.Conflict("profile_fact_revision_conflict")
            val digest = sha256("profile_fact_delete\u0000${turn.scopeId}\u0000$id\u0000$expected")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to expected), false,
                DeleteProfileFactPayload(turn.scopeId, id, expected)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteProfileFactPayload ?: return rejected()
            return try {
                if (profileMemory?.delete(payload.scope, payload.id, payload.expectedRevision) == true)
                    ToolCommitResult(ToolObservationStatus.APPLIED, "profile_fact_forgotten", mapOf("fact_id" to payload.id))
                else ToolCommitResult(ToolObservationStatus.CONFLICT, "profile_fact_revision_conflict")
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "profile_fact_delete_failed") }
        }
    }

    private inner class SaveMemoryEpisode : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("save_memory_episode", 2, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = conversations != null)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_episode_title")
            val summary = call.arguments["summary"] as? String ?: return ToolPreparation.Rejected("invalid_episode_summary")
            val rawSources = call.arguments["source_turn_ids"] as? List<*> ?: return ToolPreparation.Rejected("invalid_episode_sources")
            if (title.isBlank() || title.length > 160 || summary.isBlank() || summary.length > 1_200 || rawSources.size !in 1..8)
                return ToolPreparation.Rejected("invalid_episode")
            val sources = rawSources.map { source ->
                (source as? String)?.takeIf { it.isNotBlank() && it.length <= 100 }
                    ?: return ToolPreparation.Rejected("invalid_episode_sources")
            }
            if (sources.distinct().size != sources.size) return ToolPreparation.Rejected("invalid_episode_sources")
            val repository = conversations ?: return ToolPreparation.Rejected("episode_memory_unavailable")
            if (!repository.episodeSourcesAvailable(turn.scopeId, sources))
                return ToolPreparation.Rejected("episode_source_unavailable")
            val digest = sha256("episode_save\u0000${turn.scopeId}\u0000${sources.sorted().joinToString("\u0000")}\u0000$title\u0000$summary")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                SaveEpisodePayload(turn.scopeId, title.trim(), summary.trim(), sources)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? SaveEpisodePayload ?: return rejected()
            return try {
                val episode = conversations?.saveEpisode(payload.scope, payload.title, payload.summary, payload.sources)
                    ?: return ToolCommitResult(ToolObservationStatus.FAILED, "episode_memory_unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "episode_saved", mapOf("episode_id" to episode.episodeId,
                    "revision" to episode.revision, "source_turn_ids" to episode.sourceTurnIds))
            } catch (error: Exception) {
                when (error.message) {
                    "episode_source_unavailable" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "episode_source_unavailable")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "episode_save_failed")
                }
            }
        }
    }

    private inner class DeleteMemoryEpisode : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_memory_episode", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = conversations != null)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["episode_id"] as? String ?: return ToolPreparation.Rejected("invalid_episode_id")
            if (id.isBlank() || id.length > 100) return ToolPreparation.Rejected("invalid_episode_id")
            val repository = conversations ?: return ToolPreparation.Rejected("episode_memory_unavailable")
            val episode = repository.episode(turn.scopeId, id) ?: return ToolPreparation.Conflict("episode_not_found")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("episode_delete\u0000${turn.scopeId}\u0000${episode.episodeId}\u0000${episode.revision}"),
                emptyMap(), false, DeleteEpisodePayload(turn.scopeId, episode.episodeId)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteEpisodePayload ?: return rejected()
            return try {
                if (conversations?.deleteEpisode(payload.scope, payload.episodeId) == true)
                    ToolCommitResult(ToolObservationStatus.APPLIED, "episode_deleted", mapOf("episode_id" to payload.episodeId))
                else ToolCommitResult(ToolObservationStatus.CONFLICT, "episode_not_found")
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "episode_delete_failed") }
        }
    }

    private inner class SaveNote : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("save_note", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val body = call.arguments["body"] as? String ?: return ToolPreparation.Rejected("invalid_body")
            val kind = when (call.arguments["kind"] as? String) {
                "note" -> DomainKind.NOTE
                "person" -> DomainKind.PERSON
                "project" -> DomainKind.PROJECT
                else -> return ToolPreparation.Rejected("invalid_kind")
            }
            if (title.isBlank() || title.length > 200 || body.isBlank() || body.length > 4000)
                return ToolPreparation.Rejected("invalid_note")
            val objectId = commandId
            val digest = sha256("${kind.name}\u0000$title\u0000$body\u0000$turn.scopeId")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                SavePayload(turn.scopeId, objectId, kind, title.trim(), body.trim())))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? SavePayload ?: return rejected()
            return try {
                val saved = repository.createObject(payload.scope, payload.objectId, payload.kind,
                    payload.title, payload.body, null, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "saved", mapOf("id" to saved.objectId,
                    "kind" to saved.kind.lowercase(), "title" to saved.title, "version" to saved.version))
            } catch (_: Exception) {
                ToolCommitResult(ToolObservationStatus.FAILED, "save_failed")
            }
        }
    }

    private inner class EditMemoryObject : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_memory_object", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["object_id"] as? String ?: return ToolPreparation.Rejected("invalid_object_id")
            val current = repository.domainObject(turn.scopeId, id)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("object_not_found")
            val title = (call.arguments["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: current.title
            val body = (call.arguments["body"] as? String) ?: current.body.orEmpty()
            if (title.length > 200 || body.length > 100_000 || (title == current.title && body == current.body.orEmpty()))
                return ToolPreparation.Rejected("empty_or_invalid_edit")
            val digest = sha256("object_edit\u0000${turn.scopeId}\u0000$id\u0000${current.version}\u0000$title\u0000$body")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to current.version), false,
                EditObjectPayload(turn.scopeId, id, current.version, title, body, current.status)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? EditObjectPayload ?: return rejected()
            return try {
                val updated = repository.updateObject(payload.scope, payload.id, payload.version, payload.title,
                    payload.body, payload.status, null, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "memory_object_updated", mapOf("id" to updated.objectId,
                    "title" to updated.title, "version" to updated.version))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "memory_update_conflict") }
        }
    }

    private inner class DeleteMemoryObject : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_memory_object", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["object_id"] as? String ?: return ToolPreparation.Rejected("invalid_object_id")
            val current = repository.domainObject(turn.scopeId, id)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("object_not_found")
            val preview = runCatching { repository.previewDelete(turn.scopeId, id, current.version) }.getOrNull()
                ?: return ToolPreparation.Rejected("object_delete_conflict")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("object_delete\u0000${turn.scopeId}\u0000$id\u0000${current.version}"),
                mapOf(id to current.version), false, DeleteObjectPayload(turn.scopeId, preview)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteObjectPayload ?: return rejected()
            return try {
                repository.delete(payload.scope, payload.preview, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "memory_object_deleted", mapOf(
                    "object_id" to payload.preview.objectId, "undo_available" to true,
                    "linked_objects" to payload.preview.linkedIds))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "memory_delete_conflict") }
        }
    }

    private inner class RestoreMemoryObject : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("restore_memory_object", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["object_id"] as? String ?: return ToolPreparation.Rejected("invalid_object_id")
            val current = repository.domainObject(turn.scopeId, id) ?: return ToolPreparation.Rejected("object_not_found")
            if (current.deletedAt == null) return ToolPreparation.Rejected("object_not_deleted")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("object_restore\u0000${turn.scopeId}\u0000$id\u0000${current.version}"),
                mapOf(id to current.version), false, RestoreObjectPayload(turn.scopeId, id, current.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? RestoreObjectPayload ?: return rejected()
            return try {
                val restored = repository.restore(payload.scope, payload.id, payload.version, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "memory_object_restored", mapOf("id" to restored.objectId, "title" to restored.title))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "memory_restore_conflict") }
        }
    }

    private inner class ListTables : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_tables", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val tables = repository.tables(turn.scopeId)
            val selected = tables.take(8)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tables_loaded", mapOf(
                "table_count" to tables.size, "truncated" to (tables.size > selected.size),
                "tables" to selected.map { item -> mapOf("id" to item.table.tableId, "title" to item.table.title.take(120),
                    "schema_version" to item.table.schemaVersion,
                    "row_count" to if (item.table.mode == "linked_view") item.linkedRows.size else item.rows.size,
                    "mode" to item.table.mode,
                    "schema_truncated" to (item.columns.size > 8),
                    "columns" to item.columns.take(8).map { mapOf("id" to it.columnId, "name" to it.name.take(80), "type" to it.valueType) }) }
            ))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class ReadTableRows : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("read_table_rows", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val tableId = call.arguments["table_id"] as? String ?: return rejected()
            val query = call.arguments["query"] as? String ?: ""
            if (tableId.isBlank() || query.length > 200) return rejected()
            val table = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolCommitResult(ToolObservationStatus.REJECTED, "table_not_found")
            val linkedView = table.table.mode == "linked_view"
            val rows: List<Map<String, Any?>>
            val matchingCount: Int
            if (linkedView) {
                val matching = table.linkedRows.filter { source -> query.isBlank() ||
                    listOf(source.objectId, source.title, source.body.orEmpty(), source.status).any { it.contains(query, ignoreCase = true) } }
                matchingCount = matching.size
                rows = matching.take(10).map { source -> mapOf("id" to source.objectId,
                    "version" to source.version, "source_kind" to source.kind,
                    "values" to mapOf("source_id" to source.objectId, "title" to source.title, "body" to source.body,
                        "status" to source.status, "version" to source.version), "source_id" to source.objectId) }
            } else {
                val matching = table.rows.filter { query.isBlank() || it.valuesJson.contains(query, ignoreCase = true) }
                matchingCount = matching.size
                rows = matching.take(10).map { row -> mapOf("id" to row.rowId, "version" to row.version,
                    "values" to row.valuesJson.take(700), "values_truncated" to (row.valuesJson.length > 700)) }
            }
            return ToolCommitResult(ToolObservationStatus.APPLIED, "table_rows_loaded", mapOf(
                "table_id" to tableId, "title" to table.table.title.take(120),
                "mode" to table.table.mode,
                "columns" to table.columns.take(12).map { mapOf("id" to it.columnId, "name" to it.name.take(80), "type" to it.valueType) },
                "rows" to rows, "returned" to rows.size, "matching_row_count" to matchingCount,
                "truncated" to (matchingCount > rows.size),
            ))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class LinkObjects : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("link_objects", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val from = call.arguments["from_id"] as? String ?: return ToolPreparation.Rejected("invalid_from_id")
            val to = call.arguments["to_id"] as? String ?: return ToolPreparation.Rejected("invalid_to_id")
            val relation = call.arguments["relation"] as? String ?: return ToolPreparation.Rejected("invalid_relation")
            if (from.isBlank() || to.isBlank() || relation !in setOf("related_to", "works_on", "owes", "family_of", "part_of"))
                return ToolPreparation.Rejected("invalid_link")
            val digest = sha256("link\u0000${turn.scopeId}\u0000$from\u0000$to\u0000$relation")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                LinkPayload(turn.scopeId, commandId, from, to, relation)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? LinkPayload ?: return rejected()
            return try {
                repository.link(payload.scope, payload.id, payload.from, payload.to, payload.relation)
                ToolCommitResult(ToolObservationStatus.APPLIED, "objects_linked", mapOf("relation" to payload.relation))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "link_failed") }
        }
    }

    private val cases by lazy { repository.caseRepository() }

    /**
     * Adds bounded, phrase- and title-weighted lexical references from canonical domain stores to memory search.
     * Exact finance totals and full case timelines remain the responsibility of their specialist tools.
     */
    private suspend fun searchRelatedActivity(scope: String, terms: List<String>, query: String): UnifiedActivitySearch {
        val hits = mutableListOf<RankedActivity>()
        val relativeDates = resolveRelativeSearchDates(query)
        val foldedTerms = (terms + relativeDates.flatMap(::memorySearchTerms)).map(::normalizeMemorySearchText).distinct()
        val explicitDates = ISO_LOCAL_DATE.findAll(query).mapNotNull { match ->
            runCatching { LocalDate.parse(match.value) }.getOrNull()?.toString()
        }.toSet() + relativeDates

        val phraseTerms = memorySearchTerms((listOf(query) + relativeDates).joinToString(" "))
        val phrase = phraseTerms.joinToString(" ")

        fun score(fields: List<String>, title: String? = null): Int {
            if (explicitDates.isNotEmpty() && fields.none { field -> field in explicitDates }) return 0
            val fieldTerms = fields.map(::memorySearchTerms)
            val tokens = fieldTerms.flatten().toSet()
            val matchedTerms = foldedTerms.filter { term -> tokens.any { token -> memorySearchTokenMatches(term, token) } }
            val matched = matchedTerms.size
            if (matched == 0) return 0
            val titleTokens = title?.let(::memorySearchTerms).orEmpty()
            val titleMatches = matchedTerms.count { term -> titleTokens.any { memorySearchTokenMatches(term, it) } }
            val exactPhrase = phraseTerms.size > 1 && fieldTerms.any { it.windowed(phraseTerms.size)
                .any { window -> window.joinToString(" ") == phrase } }
            return matched * 100 / foldedTerms.size + titleMatches * 30 + if (exactPhrase) 80 else 0
        }

        if (financeEnabled) {
            val people = repository.financePeople(scope).associateBy { it.personId }
            val adjustments = repository.financeAdjustments(scope)
            val annotationsByEvent = adjustments.filter { it.kind == "annotation" }.groupBy { it.reversesEventId }
            val voidStates = adjustments.filter { it.kind in setOf("event_void", "event_restore") }
                .groupBy { it.reversesEventId }.mapValues { (_, rows) ->
                    rows.sortedWith(compareBy({ it.occurredAt.orEmpty() }, { it.eventId }))
                        .fold(false) { voided, row -> row.kind == "event_void" }
                }
            repository.allFinanceEvents(scope).forEach { event ->
                val person = people[event.personId]
                val annotations = annotationsByEvent[event.eventId].orEmpty()
                val dates = listOfNotNull(localDate(event.occurredAt ?: event.recordedAt))
                val searchable = listOfNotNull(person?.displayName, event.comment, event.source) + annotations.map { it.reason } + dates
                val relevance = score(searchable, person?.displayName)
                if (relevance > 0) {
                    val isVoided = voidStates[event.eventId] ?: false
                    hits += RankedActivity(relevance, event.occurredAt ?: event.recordedAt, "money_event", event.eventId,
                        mapOf("kind" to "money_event", "source_id" to event.eventId, "source_revision_id" to null,
                            "person_id" to event.personId, "person_name" to person?.displayName, "event_kind" to event.kind,
                            "minor_units" to event.minorUnits, "currency" to event.currency,
                            "occurred_at" to (event.occurredAt ?: event.recordedAt), "voided" to isVoided,
                            "comment" to event.comment?.take(180), "source" to event.source.take(180),
                            "annotation_ids" to annotations.take(4).map { it.eventId },
                            "annotations" to annotations.take(4).map { it.reason.take(160) },
                            "annotations_truncated" to (annotations.size > 4),
                            "exact_financial_totals_require_search_money" to true))
                }
            }
        }

        memory.tasks().tasks.asSequence().filter { it.scopeId == scope }.forEach { task ->
            val dates = listOfNotNull(localDate(task.dueLocal, task.dueZone))
            val relevance = score(listOfNotNull(task.title, task.personLabel, task.projectLabel) + dates, task.title)
            if (relevance > 0) hits += RankedActivity(relevance, task.dueLocal.orEmpty(), "task", task.id,
                mapOf("kind" to "task", "source_id" to task.id, "source_revision_id" to "${task.id}:${task.version}",
                    "task_id" to task.id, "version" to task.version, "title" to task.title,
                    "completed" to task.completed, "due_local" to task.dueLocal, "due_zone" to task.dueZone,
                    "person_label" to task.personLabel, "project_label" to task.projectLabel,
                    "completion_timestamp_available" to false))
        }

        repository.allCalendarEvents(scope).forEach { event ->
            val dates = listOfNotNull(calendarDate(event.startLocal, event.zoneId, event.timePrecision),
                event.endLocal?.let { calendarDate(it, event.zoneId, event.timePrecision) })
            val relevance = score(listOf(event.title) + dates, event.title)
            if (relevance > 0) hits += RankedActivity(relevance, event.startLocal, "calendar_event", event.eventId,
                mapOf("kind" to "calendar_event", "source_id" to event.eventId,
                    "source_revision_id" to "${event.eventId}:${event.version}", "version" to event.version,
                    "title" to event.title, "start_local" to event.startLocal, "end_local" to event.endLocal,
                    "time_zone" to event.zoneId, "time_precision" to event.timePrecision, "status" to event.status))
        }

        repository.tables(scope).forEach { snapshot ->
            val columnNames = snapshot.columns.associate { it.columnId to it.name }
            snapshot.rows.forEach rowLoop@{ row ->
                val values = runCatching { JSONObject(row.valuesJson) }.getOrNull() ?: return@rowLoop
                val searchableValues = values.keys().asSequence().mapNotNull { key ->
                    values.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.let { "${columnNames[key].orEmpty()} $it" }
                }.toList()
                val searchable = listOf(snapshot.table.title) + snapshot.columns.map { it.name } + searchableValues
                val relevance = score(searchable, snapshot.table.title)
                if (relevance > 0) hits += RankedActivity(relevance, row.rowId, "table_row", row.rowId,
                    mapOf("kind" to "table_row", "source_id" to row.rowId, "source_revision_id" to "${row.rowId}:${row.version}",
                        "row_id" to row.rowId, "row_version" to row.version, "table_id" to snapshot.table.tableId,
                        "table_title" to snapshot.table.title.take(120), "schema_version" to snapshot.table.schemaVersion,
                        "column_names" to snapshot.columns.take(12).associate { it.columnId to it.name.take(80) },
                        "values_json" to row.valuesJson.take(500), "values_truncated" to (row.valuesJson.length > 500)))
            }
        }

        val caseCandidates = cases.searchCases(scope, query, limit = 20)
        caseCandidates.forEach caseLoop@{ record ->
            val snapshot = cases.caseSnapshot(scope, record.caseId) ?: return@caseLoop
            val participants = snapshot.participants.map { mapOf("person_object_id" to it.objectId, "name" to it.title) }
            val relevance = score(listOfNotNull(record.title, record.summary, record.nextAction) + snapshot.participants.map { it.title }, record.title)
            if (relevance > 0) hits += RankedActivity(relevance, record.updatedAt, "case", record.caseId,
                mapOf("kind" to "case", "source_id" to record.caseId, "source_revision_id" to "${record.caseId}:${record.version}",
                    "case_id" to record.caseId, "version" to record.version, "title" to record.title,
                    "summary" to record.summary?.take(240), "status" to record.status,
                    "next_action" to record.nextAction?.take(180), "participants" to participants.take(8),
                    "sources" to snapshot.evidence.filter { it.sourceAvailable }.take(8).map { source -> mapOf(
                        "source_kind" to source.evidence.sourceKind, "source_id" to source.evidence.sourceId,
                        "source_revision_id" to source.evidence.sourceRevisionId, "relation" to source.evidence.relation,
                        "title" to source.title.take(160), "detail" to source.detail?.take(180)) },
                    "sources_truncated" to (snapshot.evidence.count { it.sourceAvailable } > 8)))
        }

        val ranked = hits.sortedWith(compareByDescending<RankedActivity> { it.score }
            .thenByDescending { it.sortAt }.thenBy { it.kind }.thenBy { it.id })
        return UnifiedActivitySearch(ranked.take(MAX_UNIFIED_ACTIVITY_HITS).map { it.data },
            ranked.size > MAX_UNIFIED_ACTIVITY_HITS || caseCandidates.size == MAX_CASE_CANDIDATES)
    }

    private fun resolveRelativeSearchDates(query: String): List<String> {
        val words = RELATIVE_DATE_WORD.findAll(normalizeMemorySearchText(query)).map { it.value }.toSet()
        if (words.isEmpty()) return emptyList()
        val today = LocalDate.now(searchClock.withZone(userTimeZone))
        return buildList {
            if (words.any { it in TODAY_WORDS }) add(today.toString())
            if (words.any { it in TOMORROW_WORDS }) add(today.plusDays(1).toString())
            if (words.any { it in YESTERDAY_WORDS }) add(today.minusDays(1).toString())
        }.distinct()
    }

    private data class RankedActivity(val score: Int, val sortAt: String, val kind: String, val id: String, val data: Map<String, Any?>)
    private data class UnifiedActivitySearch(val items: List<Map<String, Any?>>, val truncated: Boolean)

    private fun memorySearchTerms(value: String): List<String> = normalizeMemorySearchText(value)
        .split(MEMORY_SEARCH_SEPARATOR).filter { it.length > 1 && it !in MEMORY_SEARCH_STOP_WORDS }.distinct().take(20)

    private fun normalizeMemorySearchText(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(MEMORY_SEARCH_MARKS, "").replace('ə', 'e').replace('ı', 'i').replace('ё', 'е')
        .replace("ch", "c").replace("sh", "s")

    private fun memorySearchTokenMatches(query: String, candidate: String): Boolean {
        if (query == candidate) return true
        if (minOf(query.length, candidate.length) < 4) return false
        val sharedPrefix = query.zip(candidate).takeWhile { (left, right) -> left == right }.size
        return sharedPrefix >= 4
    }

    /** Normalize source times to the user's profile zone before matching a full ISO date. */
    private fun localDate(value: String?, sourceZoneId: String? = null): String? {
        if (value.isNullOrBlank()) return null
        val sourceZone = runCatching { sourceZoneId?.let(ZoneId::of) ?: userTimeZone }.getOrDefault(userTimeZone)
        return runCatching { Instant.parse(value).atZone(userTimeZone).toLocalDate().toString() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(userTimeZone).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(sourceZone).withZoneSameInstant(userTimeZone).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(value).toString() }.getOrNull()
    }

    private fun calendarDate(value: String, sourceZoneId: String, precision: String): String? =
        if (precision == "date") runCatching { LocalDate.parse(value).toString() }.getOrNull()
        else localDate(value, sourceZoneId)

    private companion object {
        val ISO_LOCAL_DATE = Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)")
        val RELATIVE_DATE_WORD = Regex("(?<![\\p{L}\\p{N}])(?:bu\\s+gun|bugun|sabah|dunen|dun|today|tomorrow|yesterday|сегодня|завтра|вчера)(?![\\p{L}\\p{N}])")
        val TODAY_WORDS = setOf("bu gun", "bugun", "today", "сегодня")
        val TOMORROW_WORDS = setOf("sabah", "tomorrow", "завтра")
        val YESTERDAY_WORDS = setOf("dunen", "dun", "yesterday", "вчера")
        val MEMORY_SEARCH_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        val MEMORY_SEARCH_MARKS = Regex("\\p{M}+")
        val MEMORY_SEARCH_STOP_WORDS = setOf("what", "whats", "find", "show", "about", "with", "the", "for", "and", "from",
            "what", "is", "was", "are", "to", "of", "in", "on", "me", "my", "who", "when", "where", "why", "how",
            "ne", "nə", "haqqinda", "haqqında", "haqda", "mene", "mənə", "menim", "mənim", "ve", "və", "bu", "bir",
            "что", "как", "мне", "мой", "моя", "мое", "об", "про", "для", "и", "по", "у", "к", "в", "на", "с", "из", "от", "за", "ли")
        const val MAX_UNIFIED_ACTIVITY_HITS = 12
        const val MAX_CASE_CANDIDATES = 20
    }

    private inner class CreateCase : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_case", 1, ToolAccess.PERSISTED_MEMORY, ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val summary = call.arguments["summary"] as? String
            val nextAction = call.arguments["next_action"] as? String
            if (title.isBlank() || title.length > 200 || summary?.length?.let { it > 4_000 } == true ||
                nextAction?.length?.let { it > 500 } == true) return ToolPreparation.Rejected("invalid_case")
            val digest = sha256("case_create\u0000${turn.scopeId}\u0000$title\u0000${summary.orEmpty()}\u0000${nextAction.orEmpty()}\u0000${turn.turnId}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                CreateCasePayload(turn.scopeId, commandId, title.trim(), summary?.trim()?.ifEmpty { null },
                    nextAction?.trim()?.ifEmpty { null }, turn.turnId)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateCasePayload ?: return rejected()
            return try {
                val saved = cases.createCase(payload.scope, payload.caseId, payload.title, payload.summary,
                    nextAction = payload.nextAction, sourceTurnId = payload.sourceTurnId)
                ToolCommitResult(ToolObservationStatus.APPLIED, "case_created", mapOf("case_id" to saved.caseId,
                    "title" to saved.title, "status" to saved.status, "version" to saved.version,
                    "next_action" to saved.nextAction))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "case_create_failed") }
        }
    }

    private inner class SearchCases : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_cases", 1, ToolAccess.PERSISTED_MEMORY, ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val query = call.arguments["query"] as? String ?: ""
            if (query.length > 200) return rejected()
            val matches = cases.searchCases(turn.scopeId, query, limit = 8)
            val items = matches.mapNotNull { row -> cases.caseSnapshot(turn.scopeId, row.caseId)?.let { snapshot ->
                mapOf("case_id" to row.caseId, "title" to row.title, "summary" to row.summary,
                    "status" to row.status, "next_action" to row.nextAction, "version" to row.version,
                    "participants" to snapshot.participants.map { mapOf("person_object_id" to it.objectId, "name" to it.title) }.take(8),
                    "sources" to snapshot.evidence.take(10).map { source -> mapOf("kind" to source.evidence.sourceKind,
                        "id" to source.evidence.sourceId, "revision_id" to source.evidence.sourceRevisionId,
                        "relation" to source.evidence.relation, "title" to source.title,
                        "detail" to source.detail?.take(300), "available" to source.sourceAvailable) },
                    "timeline" to snapshot.timeline.takeLast(12).map { entry -> mapOf("at" to entry.at,
                        "kind" to entry.sourceKind, "source_id" to entry.sourceId, "revision_id" to entry.sourceRevisionId,
                        "title" to entry.title, "detail" to entry.detail?.take(300)) })
            } }
            return ToolCommitResult(ToolObservationStatus.APPLIED, "cases_loaded", mapOf(
                "count" to items.size, "truncated" to (matches.size > items.size || items.size >= 8), "cases" to items))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class UpdateCase : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("update_case", 1, ToolAccess.PERSISTED_MEMORY, ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["case_id"] as? String ?: return ToolPreparation.Rejected("invalid_case_id")
            val current = cases.caseSnapshot(turn.scopeId, id)?.record ?: return ToolPreparation.Rejected("case_not_found")
            val title = (call.arguments["title"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.title
            val summary = (call.arguments["summary"] as? String)?.trim()?.ifEmpty { null } ?: current.summary
            val status = (call.arguments["status"] as? String) ?: current.status
            val clearNext = call.arguments["clear_next_action"] as? Boolean ?: false
            val next = if (clearNext) null else (call.arguments["next_action"] as? String)?.trim()?.ifEmpty { null } ?: current.nextAction
            val hasUpdate = title != current.title || summary != current.summary || status != current.status || next != current.nextAction
            if (!hasUpdate || title.length > 200 || summary?.length?.let { it > 4_000 } == true || next?.length?.let { it > 500 } == true ||
                status !in setOf("open", "waiting", "completed", "cancelled")) return ToolPreparation.Rejected("invalid_case_update")
            val digest = sha256("case_update\u0000${turn.scopeId}\u0000$id\u0000${current.version}\u0000$title\u0000${summary.orEmpty()}\u0000$status\u0000${next.orEmpty()}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to current.version), false,
                UpdateCasePayload(turn.scopeId, id, current.version, title, summary, status, next, turn.turnId)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? UpdateCasePayload ?: return rejected()
            return try {
                val updated = cases.updateCase(payload.scope, payload.caseId, payload.version, payload.title, payload.summary,
                    payload.status, payload.nextAction, payload.sourceTurnId)
                ToolCommitResult(ToolObservationStatus.APPLIED, "case_updated", mapOf("case_id" to updated.caseId,
                    "status" to updated.status, "version" to updated.version, "next_action" to updated.nextAction))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "case_update_conflict") }
        }
    }

    private inner class AddCaseParticipant : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("add_case_participant", 1, ToolAccess.PERSISTED_MEMORY, ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val caseId = call.arguments["case_id"] as? String ?: return ToolPreparation.Rejected("invalid_case_id")
            val personId = call.arguments["person_object_id"] as? String ?: return ToolPreparation.Rejected("invalid_person_id")
            val role = call.arguments["role"] as? String ?: "participant"
            if (role.isBlank() || role.length > 80 || role.any(Char::isISOControl)) return ToolPreparation.Rejected("invalid_role")
            val digest = sha256("case_participant\u0000${turn.scopeId}\u0000$caseId\u0000$personId\u0000$role")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                AddCaseParticipantPayload(turn.scopeId, caseId, personId, role.trim(), commandId)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? AddCaseParticipantPayload ?: return rejected()
            return try {
                val row = cases.addParticipant(payload.scope, payload.caseId, payload.personId, payload.role, payload.id)
                ToolCommitResult(ToolObservationStatus.APPLIED, "case_participant_added", mapOf("case_id" to row.caseId,
                    "person_object_id" to row.domainPersonId, "role" to row.role))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "case_participant_failed") }
        }
    }

    private inner class LinkCaseSource : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("link_case_source", 1, ToolAccess.PERSISTED_MEMORY, ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val caseId = call.arguments["case_id"] as? String ?: return ToolPreparation.Rejected("invalid_case_id")
            val kind = call.arguments["source_kind"] as? String ?: return ToolPreparation.Rejected("invalid_source_kind")
            val sourceId = call.arguments["source_id"] as? String ?: return ToolPreparation.Rejected("invalid_source_id")
            val relation = call.arguments["relation"] as? String ?: "related_to"
            val revisionId = call.arguments["source_revision_id"] as? String
            if (kind !in setOf("money_event", "domain_object", "calendar_event", "task", "memory_revision") ||
                sourceId.isBlank() || sourceId.length > 120 || relation.isBlank() || relation.length > 80 ||
                relation.any(Char::isISOControl) || revisionId?.length?.let { it > 120 } == true) return ToolPreparation.Rejected("invalid_case_source")
            val digest = sha256("case_source\u0000${turn.scopeId}\u0000$caseId\u0000$kind\u0000$sourceId\u0000$relation\u0000${revisionId.orEmpty()}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                LinkCaseSourcePayload(turn.scopeId, caseId, kind, sourceId, relation, revisionId, commandId)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? LinkCaseSourcePayload ?: return rejected()
            return try {
                val row = cases.addEvidence(payload.scope, payload.caseId, payload.kind, payload.sourceId,
                    payload.relation, payload.revisionId, payload.id)
                ToolCommitResult(ToolObservationStatus.APPLIED, "case_source_linked", mapOf("case_id" to row.caseId,
                    "source_kind" to row.sourceKind, "source_id" to row.sourceId, "revision_id" to row.sourceRevisionId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "case_source_failed") }
        }
    }

    private inner class CreateTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val rawColumns = (call.arguments["columns"] as? String).orEmpty()
            val rawSchema = (call.arguments["columns_json"] as? String)?.takeIf { it.isNotBlank() }
            val columns = if (rawSchema != null) {
                if (rawSchema.length > 6000) return ToolPreparation.Rejected("schema_too_large")
                val array = runCatching { org.json.JSONArray(rawSchema) }.getOrElse { return ToolPreparation.Rejected("invalid_schema") }
                if (array.length() !in 1..50) return ToolPreparation.Rejected("invalid_schema")
                (0 until array.length()).map { index ->
                    val item = runCatching { array.getJSONObject(index) }.getOrElse { return ToolPreparation.Rejected("invalid_schema") }
                    val name = item.optString("name").trim()
                    val type = runCatching { ColumnType.valueOf(item.optString("type", "TEXT").uppercase()) }.getOrNull()
                        ?: return ToolPreparation.Rejected("invalid_column_type")
                    if (type !in setOf(ColumnType.TEXT, ColumnType.INTEGER, ColumnType.DECIMAL, ColumnType.BOOLEAN,
                            ColumnType.DATE, ColumnType.DATETIME, ColumnType.MONEY, ColumnType.PERSON_REF, ColumnType.PROJECT_REF) ||
                        name.isBlank() || name.length > 100)
                        return ToolPreparation.Rejected("invalid_column")
                    ColumnSpec(UUID.randomUUID().toString(), name, type, item.optBoolean("required", false))
                }
            } else {
                val names = rawColumns.split(',').map(String::trim).filter(String::isNotEmpty)
                if (names.size !in 1..20 || names.any { it.length > 100 }) return ToolPreparation.Rejected("invalid_table")
                names.map { ColumnSpec(UUID.randomUUID().toString(), it, ColumnType.TEXT) }
            }
            if (title.isBlank() || title.length > 200 || columns.map { it.name.lowercase() }.distinct().size != columns.size)
                return ToolPreparation.Rejected("invalid_table")
            val id = commandId
            val digest = sha256("table\u0000${turn.scopeId}\u0000$title\u0000${columns.joinToString("\u0000") { "${it.name}:${it.type}:${it.required}" }}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                CreateTablePayload(turn.scopeId, id, title.trim(), columns)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateTablePayload ?: return rejected()
            return try {
                repository.createTable(payload.scope, payload.id, payload.title, "editable", payload.columns)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_created", mapOf("id" to payload.id,
                    "title" to payload.title, "columns" to payload.columns.map { mapOf("id" to it.id, "name" to it.name, "type" to it.type.name, "required" to it.required) }))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "table_create_failed") }
        }
    }

    private inner class AddTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("add_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val raw = call.arguments["values_json"] as? String ?: return ToolPreparation.Rejected("invalid_values")
            if (raw.length > 4000) return ToolPreparation.Rejected("row_too_large")
            val snapshot = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolPreparation.Rejected("table_not_found")
            val parsed = runCatching { JSONObject(raw) }.getOrElse { return ToolPreparation.Rejected("invalid_values") }
            val byName = snapshot.columns.associateBy { it.name.lowercase() }
            val values = linkedMapOf<String, Any?>()
            for (key in parsed.keys()) {
                val column = byName[key.lowercase()] ?: return ToolPreparation.Rejected("unknown_column")
                val rawValue = parsed.opt(key)
                val parsedCell = parseTableCell(column, rawValue)
                if (!parsedCell.valid) return ToolPreparation.Rejected("invalid_cell_value")
                values[column.columnId] = parsedCell.value
            }
            val rowId = commandId
            val digest = sha256("row\u0000$tableId\u0000$raw")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest,
                mapOf(tableId to snapshot.table.schemaVersion), false,
                AddRowPayload(turn.scopeId, tableId, snapshot.table.schemaVersion, rowId, values)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? AddRowPayload ?: return rejected()
            return try {
                val rows = repository.addRows(payload.scope, payload.tableId, payload.schemaVersion, listOf(payload.rowId to payload.values))
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_added", mapOf("table_id" to payload.tableId,
                    "row_id" to rows.single().rowId, "values" to rows.single().valuesJson))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "row_add_failed") }
        }
    }

    private inner class UpdateTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("update_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val rowId = call.arguments["row_id"] as? String ?: return ToolPreparation.Rejected("invalid_row_id")
            val raw = call.arguments["values_json"] as? String ?: return ToolPreparation.Rejected("invalid_values")
            if (raw.length > 4000) return ToolPreparation.Rejected("row_too_large")
            val table = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolPreparation.Rejected("table_not_found")
            if (table.table.mode != "editable") return ToolPreparation.Rejected("linked_view_read_only")
            val row = table.rows.firstOrNull { it.rowId == rowId } ?: return ToolPreparation.Rejected("row_not_found")
            val parsed = runCatching { JSONObject(raw) }.getOrElse { return ToolPreparation.Rejected("invalid_values") }
            if (parsed.length() == 0) return ToolPreparation.Rejected("empty_update")
            val byName = table.columns.associateBy { it.name.lowercase() }
            val merged = JSONObject(row.valuesJson)
            for (key in parsed.keys()) {
                val column = byName[key.lowercase()] ?: return ToolPreparation.Rejected("unknown_column")
                val value = parsed.opt(key)
                val parsedCell = parseTableCell(column, value)
                if (!parsedCell.valid) return ToolPreparation.Rejected("invalid_cell_value")
                merged.put(column.columnId, parsedCell.value ?: JSONObject.NULL)
            }
            val values = merged.keys().asSequence().associateWith { key ->
                val value = merged.opt(key)
                when (value) {
                    JSONObject.NULL -> null
                    is JSONObject -> mapOf("minorUnits" to value.getLong("minorUnits"), "currency" to value.getString("currency"))
                    is Map<*, *> -> value
                    else -> value
                }
            }
            val digest = sha256("table_row_update\u0000${turn.scopeId}\u0000$tableId\u0000$rowId\u0000${row.version}\u0000$raw")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest,
                mapOf(tableId to table.table.schemaVersion, rowId to row.version), false,
                UpdateRowPayload(turn.scopeId, tableId, rowId, row.version, table.table.schemaVersion, values)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? UpdateRowPayload ?: return rejected()
            return try {
                val row = repository.updateRow(payload.scope, payload.tableId, payload.rowId, payload.rowVersion,
                    payload.schemaVersion, payload.values)
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_updated", mapOf("table_id" to payload.tableId,
                    "row_id" to row.rowId, "version" to row.version, "values" to row.valuesJson))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "row_update_conflict") }
        }
    }

    private inner class DeleteTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val rowId = call.arguments["row_id"] as? String ?: return ToolPreparation.Rejected("invalid_row_id")
            val table = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolPreparation.Rejected("table_not_found")
            val row = table.rows.firstOrNull { it.rowId == rowId } ?: return ToolPreparation.Rejected("row_not_found")
            val digest = sha256("table_row_delete\u0000${turn.scopeId}\u0000$tableId\u0000$rowId\u0000${row.version}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(rowId to row.version), false,
                DeleteRowPayload(turn.scopeId, tableId, rowId, row.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteRowPayload ?: return rejected()
            return try {
                repository.deleteRow(payload.scope, payload.tableId, payload.rowId, payload.version, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_deleted", mapOf("row_id" to payload.rowId, "undo_available" to true))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "row_delete_conflict") }
        }
    }

    private inner class RestoreTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("restore_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val rowId = call.arguments["row_id"] as? String ?: return ToolPreparation.Rejected("invalid_row_id")
            val row = repository.tableRow(turn.scopeId, tableId, rowId) ?: return ToolPreparation.Rejected("row_not_found")
            if (row.deletedAt == null) return ToolPreparation.Rejected("row_not_deleted")
            val digest = sha256("table_row_restore\u0000${turn.scopeId}\u0000$tableId\u0000$rowId\u0000${row.version}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(rowId to row.version), false,
                DeleteRowPayload(turn.scopeId, tableId, rowId, row.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteRowPayload ?: return rejected()
            return try {
                repository.restoreRow(payload.scope, payload.tableId, payload.rowId, payload.version)
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_restored", mapOf("row_id" to payload.rowId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "row_restore_conflict") }
        }
    }

    private inner class RenameTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("rename_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val table = repository.table(turn.scopeId, tableId)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("table_not_found")
            if (title.isBlank() || title.length > 200) return ToolPreparation.Rejected("invalid_title")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("table_rename\u0000${turn.scopeId}\u0000$tableId\u0000${table.version}\u0000$title"),
                mapOf(tableId to table.version), false, RenameTablePayload(turn.scopeId, tableId, table.version, title.trim())))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? RenameTablePayload ?: return rejected()
            return try {
                val updated = repository.renameTable(payload.scope, payload.tableId, payload.version, payload.title)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_renamed", mapOf("id" to updated.tableId, "title" to updated.title))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "table_rename_conflict") }
        }
    }

    private inner class DeleteTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val table = repository.table(turn.scopeId, tableId)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("table_not_found")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("table_delete\u0000${turn.scopeId}\u0000$tableId\u0000${table.version}"),
                mapOf(tableId to table.version), false, TableLifecyclePayload(turn.scopeId, tableId, table.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? TableLifecyclePayload ?: return rejected()
            return try {
                repository.deleteTable(payload.scope, payload.tableId, payload.version, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_deleted", mapOf("table_id" to payload.tableId, "undo_available" to true))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "table_delete_conflict") }
        }
    }

    private inner class RestoreTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("restore_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val table = repository.table(turn.scopeId, tableId) ?: return ToolPreparation.Rejected("table_not_found")
            if (table.deletedAt == null) return ToolPreparation.Rejected("table_not_deleted")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("table_restore\u0000${turn.scopeId}\u0000$tableId\u0000${table.version}"),
                mapOf(tableId to table.version), false, TableLifecyclePayload(turn.scopeId, tableId, table.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? TableLifecyclePayload ?: return rejected()
            return try {
                repository.restoreTable(payload.scope, payload.tableId, payload.version)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_restored", mapOf("table_id" to payload.tableId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "table_restore_conflict") }
        }
    }

    private inner class ListTasks : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_tasks", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val snapshot = memory.tasks()
            val matching = snapshot.tasks.filter { it.scopeId == turn.scopeId }
            val selected = matching.take(16)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tasks_loaded", mapOf("tasks" to selected.map { mapOf("id" to it.id, "title" to it.title.take(160),
                    "completed" to it.completed, "due_local" to it.dueLocal, "due_zone" to it.dueZone,
                    "remind" to it.remind, "person" to it.personLabel?.take(80), "project" to it.projectLabel?.take(80)) },
                "task_count" to matching.size, "truncated" to (matching.size > selected.size)))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class CreateTask : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_task", 2, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            if (title.isBlank() || title.length > 200) return ToolPreparation.Rejected("invalid_task")
            val dueZone = (call.arguments["zone_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Asia/Baku"
            val reminderMinutes = call.arguments["remind_in_minutes"]?.let { raw ->
                val number = raw as? Number ?: return ToolPreparation.Rejected("invalid_reminder_interval")
                val exact = runCatching { java.math.BigDecimal(number.toString()).intValueExact() }.getOrNull()
                    ?: return ToolPreparation.Rejected("invalid_reminder_interval")
                if (exact !in 1..525_600) return ToolPreparation.Rejected("invalid_reminder_interval")
                exact
            }
            val absoluteDueLocal = (call.arguments["due_local"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (reminderMinutes != null && absoluteDueLocal != null) return ToolPreparation.Rejected("conflicting_reminder_time")
            val dueLocal = reminderMinutes?.let {
                ai.memora.app.domain.TaskReminderTime.resolveRelative(it, dueZone, java.time.Instant.now())
                    ?: return ToolPreparation.Rejected("invalid_reminder_interval")
            } ?: absoluteDueLocal
            val remind = reminderMinutes != null || (call.arguments["remind"] as? Boolean ?: false)
            val person = (call.arguments["person_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val project = (call.arguments["project_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (remind && dueLocal == null) return ToolPreparation.Rejected("reminder_requires_due_time")
            val preview = TaskRules.create(commandId, turn.scopeId, turn.actorId, commandId, title)
            if (preview !is TaskDecision.Changed) return ToolPreparation.Rejected("invalid_task")
            if (dueLocal != null && TaskRules.details(
                    (TaskRules.apply(null, preview.event) as? ai.memora.app.domain.TaskApply.Applied)?.task ?: return ToolPreparation.Rejected("invalid_task"),
                    1, turn.actorId, UUID.randomUUID().toString(), dueLocal, dueZone, remind, person, project) !is TaskDecision.Changed)
                return ToolPreparation.Rejected("invalid_task_details")
            val id = commandId
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("task\u0000${turn.scopeId}\u0000$id\u0000$title\u0000$dueLocal\u0000$dueZone\u0000$remind\u0000$person\u0000$project"), emptyMap(), false,
                CreateTaskPayload(id, turn.scopeId, turn.actorId, commandId, title.trim(), dueLocal, dueZone, remind, person, project)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateTaskPayload ?: return rejected()
            return try {
                val task = memoryTask(payload)
                val reminder = if (payload.remind) runCatching { onTaskChanged(task) }.getOrDefault("unavailable") else "not_requested"
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_created", mapOf("id" to task.id, "title" to task.title,
                    "due_local" to task.dueLocal, "due_zone" to task.dueZone, "remind" to task.remind, "reminder_status" to reminder))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "task_create_failed") }
        }
        private suspend fun memoryTask(payload: CreateTaskPayload): Task {
            val created = TaskEvent.Created(payload.taskId, payload.scope, payload.actor, payload.commandId, payload.title)
            val events = mutableListOf<TaskEvent>(created)
            if (payload.dueLocal != null) events += TaskEvent.DetailsChanged(payload.taskId, payload.scope, payload.actor,
                UUID.randomUUID().toString(), 1, payload.dueLocal, payload.dueZone, payload.remind, payload.person, payload.project)
            return repository.appendTaskEvents(events)
        }
    }

    private inner class SetTaskCompletion : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("set_task_completion", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val taskId = call.arguments["task_id"] as? String ?: return ToolPreparation.Rejected("invalid_task_id")
            val completed = call.arguments["completed"] as? Boolean ?: return ToolPreparation.Rejected("invalid_completion_state")
            val task = memory.tasks().tasks.firstOrNull { it.id == taskId && it.scopeId == turn.scopeId }
                ?: return ToolPreparation.Rejected("task_not_found")
            if (task.completed == completed) return ToolPreparation.Rejected("task_already_in_requested_state")
            val digest = sha256("task_completion\u0000${turn.scopeId}\u0000$taskId\u0000${task.version}\u0000$completed")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(taskId to task.version), false,
                CompletionPayload(task.id, turn.scopeId, turn.actorId, commandId, task.version, completed)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CompletionPayload ?: return rejected()
            return try {
                val task = repository.appendTaskEvent(TaskEvent.CompletionChanged(payload.id, payload.scope, payload.actor,
                    payload.commandId, payload.version, payload.completed))
                val reminder = runCatching { onTaskChanged(task) }.getOrDefault("unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_completion_changed", mapOf(
                    "id" to task.id, "title" to task.title, "completed" to task.completed, "version" to task.version,
                    "reminder_status" to reminder))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "task_update_conflict") }
        }
    }

    private inner class EditTask : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_task", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["task_id"] as? String ?: return ToolPreparation.Rejected("invalid_task_id")
            val task = memory.tasks().tasks.firstOrNull { it.id == id && it.scopeId == turn.scopeId }
                ?: return ToolPreparation.Rejected("task_not_found")
            val clearDue = call.arguments["clear_due"] as? Boolean ?: false
            val due = if (clearDue) null else (call.arguments["due_local"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: task.dueLocal
            val zone = if (due == null) null else (call.arguments["zone_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: task.dueZone ?: "Asia/Baku"
            val remind = if (due == null) false else call.arguments["remind"] as? Boolean ?: task.remind
            val title = (call.arguments["title"] as? String)?.takeIf { it.isNotBlank() } ?: task.title
            val person = (call.arguments["person_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: task.personLabel.takeUnless { call.arguments["person_label"] is String }
            val project = (call.arguments["project_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: task.projectLabel.takeUnless { call.arguments["project_label"] is String }
            val decision = TaskRules.edit(task, task.version, turn.actorId, commandId, title, due, zone, remind, person, project)
            val event = (decision as? TaskDecision.Changed)?.event ?: return ToolPreparation.Rejected("task_unchanged_or_invalid")
            val digest = sha256("task_edit\u0000${turn.scopeId}\u0000$id\u0000${task.version}\u0000$event")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to task.version), false,
                TaskEditPayload(event)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? TaskEditPayload ?: return rejected()
            return try {
                val task = repository.appendTaskEvent(payload.event)
                val reminder = runCatching { onTaskChanged(task) }.getOrDefault("unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_updated", mapOf("id" to task.id, "title" to task.title,
                    "due_local" to task.dueLocal, "due_zone" to task.dueZone, "remind" to task.remind, "reminder_status" to reminder))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "task_update_conflict") }
        }
    }

    private inner class ListCalendarEvents : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_calendar_events", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val from = call.arguments["from_local"] as? String ?: return rejected()
            val until = call.arguments["until_local"] as? String ?: return rejected()
            if (from.length > 30 || until.length > 30 || from >= until) return rejected()
            return try {
                val matching = repository.calendarBetween(turn.scopeId, from, until)
                val events = matching.take(16)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_loaded", mapOf("events" to events.map { event ->
                    mapOf("id" to event.eventId, "title" to event.title.take(160), "start_local" to event.startLocal,
                        "end_local" to event.endLocal, "zone_id" to event.zoneId, "precision" to event.timePrecision)
                }, "event_count" to matching.size, "truncated" to (matching.size > events.size)))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_calendar_range") }
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class SearchConversationHistory : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_conversation_history", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = conversations != null)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val query = (call.arguments["query"] as? String)?.trim().orEmpty()
            val daysBack = (call.arguments["days_back"] as? Number)?.toInt()
            val limit = (call.arguments["max_results"] as? Number)?.toInt() ?: 8
            if (query.length > 300 || (query.isBlank() && daysBack == null) || (daysBack != null && daysBack !in 1..365) || limit !in 1..8)
                return rejected()
            return try {
                val hits = conversations?.search(turn.scopeId, query, daysBack, limit).orEmpty()
                ToolCommitResult(ToolObservationStatus.APPLIED, "conversation_evidence_loaded", mapOf(
                    "searched_days_back" to daysBack, "count" to hits.size,
                    "evidence" to hits.map { hit -> mapOf("turn_id" to hit.turnId, "speaker" to hit.role,
                        "time_epoch_ms" to hit.createdAtMillis, "relevance" to hit.score, "snippet" to hit.snippet.take(600),
                        "snippet_truncated" to (hit.snippet.length > 600)) }))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "conversation_search_failed") }
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class ReadConversationTurn : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("read_conversation_turn", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = conversations != null)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val turnId = call.arguments["turn_id"] as? String ?: return rejected()
            if (turnId.isBlank() || turnId.length > 100) return rejected()
            return try {
                val source = conversations?.sourceUserTurn(turn.scopeId, turnId)
                    ?: return ToolCommitResult(ToolObservationStatus.CONFLICT, "conversation_source_not_found")
                ToolCommitResult(ToolObservationStatus.APPLIED, "conversation_source_loaded", mapOf(
                    "turn_id" to source.turnId, "time_epoch_ms" to source.createdAtMillis,
                    "user_message" to source.message.take(4_000), "truncated" to (source.message.length > 4_000)))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "conversation_source_read_failed") }
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class CreateCalendarEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_calendar_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val start = call.arguments["start_local"] as? String ?: return ToolPreparation.Rejected("invalid_start")
            val end = call.arguments["end_local"] as? String
            val zone = call.arguments["zone_id"] as? String ?: return ToolPreparation.Rejected("invalid_zone")
            val precision = call.arguments["precision"] as? String ?: "minute"
            if (title.isBlank() || title.length > 200 || start.length > 30 || (end?.length ?: 0) > 30 || zone.length > 80 || precision !in setOf("date", "minute"))
                return ToolPreparation.Rejected("invalid_calendar_event")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("calendar\u0000${turn.scopeId}\u0000$title\u0000$start\u0000$end\u0000$zone\u0000$precision"), emptyMap(), false,
                CalendarPayload(CalendarInput(commandId, turn.scopeId, title.trim(), start, end, zone, precision))))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CalendarPayload ?: return rejected()
            return try {
                val event = repository.createCalendarEvent(payload.input)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_event_created", mapOf("id" to event.eventId,
                    "title" to event.title, "start_local" to event.startLocal, "zone_id" to event.zoneId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_calendar_event") }
        }
    }

    private inner class EditCalendarEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_calendar_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val current = repository.calendarEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("calendar_event_not_found")
            if (current.status != "active") return ToolPreparation.Conflict("calendar_event_cancelled")
            val clearEnd = call.arguments["clear_end"] as? Boolean ?: false
            val endArgument = call.arguments["end_local"] as? String
            if (clearEnd && endArgument != null) return ToolPreparation.Rejected("invalid_calendar_end")
            val input = CalendarInput(eventId, turn.scopeId,
                (call.arguments["title"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.title,
                (call.arguments["start_local"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.startLocal,
                if (clearEnd) null else endArgument ?: current.endLocal,
                (call.arguments["zone_id"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.zoneId,
                (call.arguments["precision"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.timePrecision)
            if (input.title.length > 200 || input.startLocal.length > 30 || (input.endLocal?.length ?: 0) > 30 || input.zoneId.length > 80 ||
                input.precision !in setOf("date", "minute")) return ToolPreparation.Rejected("invalid_calendar_event")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("calendar_edit\u0000${turn.scopeId}\u0000$eventId\u0000${current.version}\u0000${input.hashCode()}"),
                mapOf(eventId to current.version), false, CalendarEditPayload(input, current.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CalendarEditPayload ?: return rejected()
            return try {
                val event = repository.updateCalendarEvent(payload.input.scopeId, payload.input, payload.expectedVersion)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_event_updated", mapOf("id" to event.eventId,
                    "title" to event.title, "start_local" to event.startLocal, "end_local" to event.endLocal,
                    "zone_id" to event.zoneId, "precision" to event.timePrecision))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "calendar_event_update_conflict") }
        }
    }

    private inner class CancelCalendarEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("cancel_calendar_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val current = repository.calendarEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("calendar_event_not_found")
            if (current.status != "active") return ToolPreparation.Conflict("calendar_event_already_cancelled")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("calendar_cancel\u0000${turn.scopeId}\u0000$eventId\u0000${current.version}"),
                mapOf(eventId to current.version), false, CalendarCancelPayload(turn.scopeId, eventId, current.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CalendarCancelPayload ?: return rejected()
            return try {
                val event = repository.cancelCalendarEvent(payload.scope, payload.eventId, payload.expectedVersion)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_event_cancelled", mapOf("id" to event.eventId,
                    "title" to event.title, "start_local" to event.startLocal, "status" to event.status))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "calendar_event_cancel_conflict") }
        }
    }

    private inner class SearchMoney : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_money", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = financeEnabled)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val name = (call.arguments["person_name"] as? String)?.trim().orEmpty()
            val query = (call.arguments["query"] as? String)?.trim().orEmpty()
            val kind = (call.arguments["kind"] as? String)?.takeIf { it.isNotBlank() }
            if ((name.isBlank() && query.isBlank()) || name.length > 100 || query.length > 200 ||
                (kind != null && kind !in setOf("receipt", "debt", "expense"))) return rejected()
            val history = try {
                if (name.isBlank()) null else when (val alias = personAliases?.resolveFinanceAlias(turn.scopeId, name)) {
                    null, FinanceAliasResolution.NotAlias -> repository.financeHistory(turn.scopeId, name)
                    is FinanceAliasResolution.Resolved -> repository.financeHistoryByPersonId(turn.scopeId, alias.financePerson.personId)
                    FinanceAliasResolution.OwnerLinkRequired -> return ToolCommitResult(ToolObservationStatus.CONFLICT,
                        "person_alias_finance_identity_unconfirmed")
                    FinanceAliasResolution.RetiredAlias -> return ToolCommitResult(ToolObservationStatus.CONFLICT, "person_alias_retired")
                    FinanceAliasResolution.Ambiguous -> return ToolCommitResult(ToolObservationStatus.CONFLICT, "person_alias_ambiguous")
                }
            }
            catch (_: IllegalStateException) { return ToolCommitResult(ToolObservationStatus.CONFLICT, "person_name_ambiguous") }
            val names = repository.financePeople(turn.scopeId).associate { it.personId to it.displayName }
            val matchingEvents = (if (name.isNotBlank()) history?.events.orEmpty() else repository.allFinanceEvents(turn.scopeId).filter { event ->
                listOfNotNull(names[event.personId], event.comment, event.source).any { it.contains(query, ignoreCase = true) }
            }).filter { kind == null || it.kind == kind }
            val events = matchingEvents.asReversed().take(12)
            val adjustments = repository.financeAdjustments(turn.scopeId)
            val eventData = events.map { event ->
                val annotations = adjustments.filter { it.kind == "annotation" && it.reversesEventId == event.eventId }
                    .sortedByDescending { it.occurredAt }
                mapOf("event_id" to event.eventId, "kind" to event.kind,
                "person_name" to names[event.personId], "minor_units" to event.minorUnits, "currency" to event.currency,
                "remaining_minor_units" to history?.remainingDebtMinorByEvent?.get(event.eventId),
                "applied_to_debt_minor_units" to if (event.kind == "receipt") repository.appliedToDebtMinor(turn.scopeId, event.eventId) else null,
                "voided" to (history?.voidedEventIds?.contains(event.eventId) ?: repository.isFinanceEventVoided(turn.scopeId, event.eventId)),
                "recorded_at" to event.recordedAt, "occurred_at" to event.occurredAt,
                "annotations" to annotations.take(4).map { it.reason },
                "annotation_count" to annotations.size, "annotations_truncated" to (annotations.size > 4),
                "comment" to event.comment?.take(250), "comment_truncated" to ((event.comment?.length ?: 0) > 250),
                "source" to event.source.take(300), "source_truncated" to (event.source.length > 300)) }
            val financialSummary = history?.let { personHistory ->
                val active = personHistory.events.filterNot { it.eventId in personHistory.voidedEventIds }
                mapOf("currency" to "AZN",
                    "received_minor_units" to active.filter { it.kind == "receipt" }.fold(0L) { total, event -> Math.addExact(total, event.minorUnits) },
                    "spent_minor_units" to active.filter { it.kind == "expense" }.fold(0L) { total, event -> Math.addExact(total, event.minorUnits) },
                    "outstanding_debt_minor_units" to personHistory.remainingDebtMinorByEvent.values.fold(0L) { total, amount -> Math.addExact(total, amount) })
            }
            return ToolCommitResult(ToolObservationStatus.APPLIED, "financial_evidence_loaded", boundedFinanceEvidence(mapOf(
                "person_found" to (history != null), "person_name" to history?.person?.displayName,
                "event_order" to "recorded_at_desc", "financial_summary" to financialSummary,
            ), eventData, matchingEvents.size))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class AnalyzeFinancePeriod : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("analyze_finance_period", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = financeEnabled)

        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            if (call.arguments.containsKey("month") && call.arguments["month"] != null && call.arguments["month"] !is String)
                return ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_period")
            val requested = call.arguments["month"] as? String
            val month = FinanceAnalytics.requestedMonth(requested)
                ?: return ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_period")
            return try {
                val report = repository.financeAnalytics(turn.scopeId, month)
                ToolCommitResult(ToolObservationStatus.APPLIED, "finance_report_ready", report.toToolData())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ToolCommitResult(ToolObservationStatus.FAILED, "finance_report_unavailable")
            }
        }

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) =
            ToolPreparation.Rejected("read_only_tool")

        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class SummarizeWorkStatus : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("summarize_work_status", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)

        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            if (call.arguments.isNotEmpty()) return rejected()
            return try {
                val allTasks = memory.tasks().tasks.filter { it.scopeId == turn.scopeId }
                val openTasks = allTasks.filterNot { it.completed }
                val sampledTasks = openTasks.take(MAX_WORK_TASKS).map { task -> mapOf(
                    "task_id" to task.id,
                    "title" to task.title.take(200),
                    "due_local" to task.dueLocal,
                    "due_zone" to task.dueZone,
                    "person_label" to task.personLabel,
                    "project_label" to task.projectLabel,
                ) }
                val statusCounts = cases.statusCounts(turn.scopeId)
                val totalCases = statusCounts.sumOf { it.count }
                val caseSample = cases.cases(turn.scopeId, MAX_WORK_CASES).map { case -> mapOf(
                    "case_id" to case.caseId,
                    "title" to case.title.take(200),
                    "status" to case.status,
                    "summary" to case.summary?.take(500),
                    "next_action" to case.nextAction?.take(250),
                    "updated_at" to case.updatedAt,
                    "source_turn_id" to case.sourceTurnId,
                ) }
                ToolCommitResult(ToolObservationStatus.APPLIED, "work_status_ready", mapOf(
                    "source" to "protected_task_event_projection_and_case_records",
                    "as_of" to Instant.now().toString(),
                    "tasks" to mapOf(
                        "total_current_tasks" to allTasks.size,
                        "open_current_tasks" to openTasks.size,
                        "completed_current_tasks" to (allTasks.size - openTasks.size),
                        "open_tasks" to sampledTasks,
                        "open_task_sample_truncated" to (openTasks.size > sampledTasks.size),
                        "completion_period_analysis_available" to false,
                    ),
                    "cases" to mapOf(
                        "current_case_count" to totalCases,
                        "open_case_count" to statusCounts.filterNot { it.status in TERMINAL_CASE_STATUSES }.sumOf { it.count },
                        "status_counts" to statusCounts.associate { it.status to it.count },
                        "recent_case_sample" to caseSample,
                        "recent_case_sample_truncated" to (totalCases > caseSample.size),
                    ),
                    "limitations" to listOf(
                        "task_completion_timestamps_not_stored; no period completion rate",
                        "task_and_case_records_do_not_establish_revenue_cost_profit_or_project_roi",
                        "person_and_project_labels_are_text_not_verified_identity_links",
                        "case_details_and_canonical_evidence_can_be_loaded_with_search_cases",
                    ),
                ))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ToolCommitResult(ToolObservationStatus.FAILED, "work_status_unavailable")
            }
        }

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) =
            ToolPreparation.Rejected("read_only_tool")

        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class RecordMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("record_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val kind = call.arguments["kind"] as? String ?: return ToolPreparation.Rejected("invalid_kind")
            val personName = (call.arguments["person_name"] as? String)?.trim().orEmpty()
            val amount = moneyAmountArgument(call.arguments["amount"]) ?: return ToolPreparation.Rejected("invalid_amount")
            val currency = (call.arguments["currency"] as? String)?.uppercase() ?: "AZN"
            val comment = (call.arguments["comment"] as? String)?.takeIf { it.isNotBlank() }
            val localDate = (call.arguments["occurred_date"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if ((kind != "expense" && personName.isBlank()) || personName.length > 100 || amount.length > 32 || VoiceMoneyRules.parseMinor(amount) == null ||
                currency != "AZN" || (call.arguments["occurred_date"] as? String)?.isNotBlank() == true && localDate == null ||
                (comment?.length ?: 0) > 1000)
                return ToolPreparation.Rejected("invalid_money_event")
            val personMatches = try { if (personName.isBlank()) emptyList() else repository.matchingFinancePeople(turn.scopeId, personName) }
                catch (_: Exception) { return ToolPreparation.Rejected("finance_store_unavailable") }
            var canonicalPersonName = personName
            var aliasFinancePersonId: String? = null
            when (val alias = personAliases?.resolveFinanceAlias(turn.scopeId, personName)) {
                null, FinanceAliasResolution.NotAlias -> Unit
                is FinanceAliasResolution.Resolved -> {
                    aliasFinancePersonId = alias.financePerson.personId
                    canonicalPersonName = alias.financePerson.displayName
                }
                FinanceAliasResolution.OwnerLinkRequired -> return ToolPreparation.Conflict("person_alias_finance_identity_unconfirmed")
                FinanceAliasResolution.RetiredAlias -> return ToolPreparation.Conflict("person_alias_retired")
                FinanceAliasResolution.Ambiguous -> return ToolPreparation.Conflict("person_alias_ambiguous")
            }
            if (personMatches.size > 1) return ToolPreparation.Conflict("person_name_ambiguous")
            if (aliasFinancePersonId != null && personMatches.any { it.personId != aliasFinancePersonId })
                return ToolPreparation.Conflict("person_alias_finance_identity_conflict")
            val personId = aliasFinancePersonId ?: personMatches.singleOrNull()?.personId
                ?: personName.takeIf { it.isNotBlank() }?.let { UUID.randomUUID().toString() }.orEmpty()
            val eventId = UUID.randomUUID().toString()
            val recordedAt = Instant.now()
            val occurredAt = (localDate ?: recordedAt.atZone(ZoneId.of("Asia/Baku")).toLocalDate())
                .atStartOfDay(ZoneId.of("Asia/Baku")).toInstant()
            val digest = sha256("money\u0000${turn.scopeId}\u0000$kind\u0000${VoiceMoneyRules.key(canonicalPersonName)}\u0000$amount\u0000$currency\u0000${occurredAt}\u0000${comment.orEmpty()}\u0000${turn.text}")
            if (kind !in setOf("receipt", "debt", "expense")) return ToolPreparation.Rejected("invalid_kind")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                FinancePayload(turn.scopeId, canonicalPersonName.trim(), kind, amount, currency, turn.text, comment,
                    personId, eventId, commandId, recordedAt, occurredAt)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? FinancePayload ?: return rejected()
            return try {
                val event = repository.recordFinanceEvent(payload.scope, payload.personName, payload.kind,
                    requireNotNull(VoiceMoneyRules.parseMinor(payload.amount)), payload.currency, payload.source,
                    payload.comment, payload.personId, payload.eventId, payload.commandId,
                    payload.recordedAt.toString(), payload.occurredAt.toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, when (event.kind) { "receipt" -> "receipt_recorded"; "debt" -> "debt_recorded"; else -> "expense_recorded" },
                    mapOf("id" to event.eventId, "person_name" to payload.personName, "minor_units" to event.minorUnits,
                        "currency" to event.currency, "occurred_at" to event.occurredAt, "comment" to event.comment))
            } catch (error: Exception) {
                when (error.message) {
                    "person_name_ambiguous" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "person_name_ambiguous")
                    "idempotency_conflict", "money_event_id_conflict" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "duplicate_conflict")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_store_unavailable")
                }
            }
        }
    }

    private inner class SettleDebt : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("settle_debt", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val obligationId = call.arguments["obligation_id"] as? String ?: return ToolPreparation.Rejected("invalid_obligation")
            val amount = moneyAmountArgument(call.arguments["amount"]) ?: return ToolPreparation.Rejected("invalid_amount")
            val minor = VoiceMoneyRules.parseMinor(amount) ?: return ToolPreparation.Rejected("invalid_amount")
            val debt = repository.moneyEvent(turn.scopeId, obligationId)?.takeIf { it.kind == "debt" }
                ?: return ToolPreparation.Rejected("obligation_not_found")
            val date = (call.arguments["occurred_date"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if ((call.arguments["occurred_date"] as? String)?.isNotBlank() == true && date == null)
                return ToolPreparation.Rejected("invalid_date")
            val now = Instant.now()
            val occurredAt = (date ?: now.atZone(ZoneId.of("Asia/Baku")).toLocalDate())
                .atStartOfDay(ZoneId.of("Asia/Baku")).toInstant()
            val reason = (call.arguments["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Repayment"
            if (reason.length > 500) return ToolPreparation.Rejected("invalid_reason")
            val paymentEventId = (call.arguments["payment_event_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ToolPreparation.Rejected("payment_event_required")
            if (paymentEventId.length > 100) return ToolPreparation.Rejected("invalid_payment_event")
            val payment = repository.moneyEvent(turn.scopeId, paymentEventId)?.takeIf { it.kind == "receipt" }
                ?: return ToolPreparation.Rejected("payment_event_not_found")
            if (payment.personId != debt.personId) return ToolPreparation.Conflict("payment_obligation_mismatch")
            if (payment.currency != debt.currency) return ToolPreparation.Conflict("currency_mismatch")
            if (repository.isFinanceEventVoided(turn.scopeId, paymentEventId)) return ToolPreparation.Conflict("payment_event_voided")
            if (minor > payment.minorUnits - repository.appliedToDebtMinor(turn.scopeId, paymentEventId))
                return ToolPreparation.Conflict("payment_amount_already_applied")
            val remaining = try { repository.outstandingDebtMinor(turn.scopeId, obligationId) }
                catch (_: Exception) { return ToolPreparation.Rejected("obligation_unavailable") }
            if (remaining <= 0 || minor > remaining) return ToolPreparation.Conflict("settlement_exceeds_balance")
            val digest = sha256("settlement\u0000${turn.scopeId}\u0000$obligationId\u0000$minor\u0000${debt.currency}\u0000$occurredAt\u0000$reason\u0000${paymentEventId.orEmpty()}\u0000${turn.text}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(obligationId to debt.minorUnits), false,
                SettlementPayload(turn.scopeId, obligationId, minor, debt.currency, occurredAt.toString(), reason, commandId, paymentEventId)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? SettlementPayload ?: return rejected()
            return try {
                val remaining = repository.recordSettlement(payload.scope, payload.commandId, payload.commandId,
                    payload.obligationId, payload.minorUnits, payload.currency, payload.occurredAt, payload.reason, payload.paymentEventId)
                ToolCommitResult(ToolObservationStatus.APPLIED, "debt_settlement_recorded", mapOf(
                    "obligation_id" to payload.obligationId, "settled_minor_units" to payload.minorUnits,
                    "payment_event_id" to payload.paymentEventId, "currency" to payload.currency,
                    "remaining_minor_units" to remaining, "occurred_at" to payload.occurredAt))
            } catch (error: Exception) {
                when (error.message) {
                    "settlement_exceeds_balance", "payment_obligation_mismatch", "payment_event_voided", "payment_amount_already_applied" ->
                        ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    "obligation_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "obligation_not_found")
                    "payment_event_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "payment_event_not_found")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "settlement_failed")
                }
            }
        }
    }

    private inner class ReverseMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("reverse_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val reason = (call.arguments["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "User-requested correction"
            val issue = repository.canReverseFinanceEvent(turn.scopeId, eventId)
            if (issue != null) return ToolPreparation.Conflict(issue)
            val event = repository.moneyEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("event_not_found")
            val digest = sha256("money_void\u0000${turn.scopeId}\u0000$eventId\u0000$reason")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(eventId to 1L), false,
                ReverseFinancePayload(turn.scopeId, eventId, commandId, reason)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? ReverseFinancePayload ?: return rejected()
            return try {
                val result = repository.reverseFinanceEvent(payload.scope, payload.eventId, payload.commandId,
                    payload.reason, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "financial_event_reversed", mapOf(
                    "event_id" to result.event.eventId, "kind" to result.event.kind, "minor_units" to result.event.minorUnits,
                    "currency" to result.event.currency, "reversed" to true,
                    "reversed_debt_allocations" to result.reversedAllocations.map { mapOf(
                        "obligation_id" to it.obligationId, "minor_units" to it.minorUnits) },
                    "replacement_required_if_correcting" to true))
            } catch (error: Exception) {
                when (error.message) {
                    "event_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "event_not_found")
                    "event_already_reversed", "event_has_settlements" -> ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "financial_event_reverse_failed")
                }
            }
        }
    }

    private inner class CorrectMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("correct_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val amount = moneyAmountArgument(call.arguments["new_amount"]) ?: return ToolPreparation.Rejected("invalid_amount_type")
            val minorUnits = VoiceMoneyRules.parseMinor(amount) ?: return ToolPreparation.Rejected("invalid_amount_format")
            val event = repository.moneyEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("event_not_found")
            if (event.currency != "AZN") return ToolPreparation.Rejected("unsupported_currency")
            if (event.minorUnits == minorUnits) return ToolPreparation.Rejected("amount_unchanged")
            val reason = (call.arguments["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "User-requested amount correction"
            if (reason.length > 500) return ToolPreparation.Rejected("invalid_reason")
            val digest = sha256("finance_correction\u0000${turn.scopeId}\u0000$eventId\u0000$minorUnits\u0000$reason")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                FinanceCorrectionPayload(turn.scopeId, eventId, minorUnits, commandId, reason)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? FinanceCorrectionPayload ?: return rejected()
            return try {
                val result = repository.correctFinanceEventAmount(payload.scope, payload.eventId, payload.newMinorUnits,
                    payload.commandId, payload.reason, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "money_event_corrected", mapOf(
                    "event_id" to result.event.eventId,
                    "kind" to result.event.kind,
                    "new_minor_units" to result.event.minorUnits,
                    "currency" to result.event.currency,
                    "adjusted_allocation_count" to result.adjustedAllocations.size,
                    "remaining_debt_minor_units" to result.remainingDebtMinorByEvent,
                ))
            } catch (error: Exception) {
                when (error.message) {
                    "event_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "event_not_found")
                    "event_already_reversed" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "event_already_reversed")
                    "correction_below_settled_amount", "correction_requires_allocation_review", "settlement_exceeds_balance",
                    "currency_mismatch", "amount_unchanged" -> ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_correction_failed")
                }
            }
        }
    }

    private inner class AnnotateMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("annotate_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            if (repository.moneyEvent(turn.scopeId, eventId) == null) return ToolPreparation.Rejected("event_not_found")
            if (repository.isFinanceEventVoided(turn.scopeId, eventId)) return ToolPreparation.Rejected("event_already_reversed")
            val annotation = turn.text.trim()
            if (annotation.isBlank() || annotation.length > 500) return ToolPreparation.Rejected("invalid_annotation")
            val digest = sha256("finance_annotation\u0000${turn.scopeId}\u0000$eventId\u0000$annotation")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                FinanceAnnotationPayload(turn.scopeId, eventId, commandId, annotation)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? FinanceAnnotationPayload ?: return rejected()
            return try {
                val row = repository.annotateFinanceEvent(payload.scope, payload.eventId, payload.commandId,
                    payload.annotation, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "money_event_annotated", mapOf(
                    "event_id" to payload.eventId, "annotation" to row.reason))
            } catch (error: Exception) {
                when (error.message) {
                    "event_not_found", "event_already_reversed" -> ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    "idempotency_conflict" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "duplicate_conflict")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_annotation_failed")
                }
            }
        }
    }

    private data class SavePayload(val scope: String, val objectId: String,
        val kind: DomainKind, val title: String, val body: String)
    private data class SaveProfileFactPayload(val scope: String, val category: String, val fact: String,
        val sourceTurnId: String, val sourceText: String)
    private data class EditProfileFactPayload(val scope: String, val id: String, val expectedRevision: Long,
        val category: String, val fact: String, val sourceTurnId: String, val sourceText: String)
    private data class DeleteProfileFactPayload(val scope: String, val id: String, val expectedRevision: Long)
    private data class SaveEpisodePayload(val scope: String, val title: String, val summary: String, val sources: List<String>)
    private data class DeleteEpisodePayload(val scope: String, val episodeId: String)
    private data class EditObjectPayload(val scope: String, val id: String, val version: Long, val title: String,
        val body: String, val status: String)
    private data class DeleteObjectPayload(val scope: String, val preview: ai.memora.app.data.protectedstore.DeletePreview)
    private data class RestoreObjectPayload(val scope: String, val id: String, val version: Long)
    private data class CreateTablePayload(val scope: String, val id: String, val title: String, val columns: List<ColumnSpec>)
    private data class AddRowPayload(val scope: String, val tableId: String, val schemaVersion: Long,
        val rowId: String, val values: Map<String, Any?>)
    private data class UpdateRowPayload(val scope: String, val tableId: String, val rowId: String,
        val rowVersion: Long, val schemaVersion: Long, val values: Map<String, Any?>)
    private data class DeleteRowPayload(val scope: String, val tableId: String, val rowId: String, val version: Long)
    private data class RenameTablePayload(val scope: String, val tableId: String, val version: Long, val title: String)
    private data class TableLifecyclePayload(val scope: String, val tableId: String, val version: Long)
    private data class CreateTaskPayload(val taskId: String, val scope: String, val actor: String,
        val commandId: String, val title: String, val dueLocal: String?, val dueZone: String?, val remind: Boolean,
        val person: String?, val project: String?)
    private data class LinkPayload(val scope: String, val id: String, val from: String, val to: String, val relation: String)
    private data class CreateCasePayload(val scope: String, val caseId: String, val title: String, val summary: String?,
        val nextAction: String?, val sourceTurnId: String)
    private data class UpdateCasePayload(val scope: String, val caseId: String, val version: Long, val title: String,
        val summary: String?, val status: String, val nextAction: String?, val sourceTurnId: String)
    private data class AddCaseParticipantPayload(val scope: String, val caseId: String, val personId: String,
        val role: String, val id: String)
    private data class LinkCaseSourcePayload(val scope: String, val caseId: String, val kind: String, val sourceId: String,
        val relation: String, val revisionId: String?, val id: String)
    private data class CompletionPayload(val id: String, val scope: String, val actor: String, val commandId: String,
        val version: Long, val completed: Boolean)
    private data class CalendarPayload(val input: CalendarInput)
    private data class CalendarEditPayload(val input: CalendarInput, val expectedVersion: Long)
    private data class CalendarCancelPayload(val scope: String, val eventId: String, val expectedVersion: Long)
    private data class FinancePayload(val scope: String, val personName: String, val kind: String, val amount: String,
        val currency: String, val source: String, val comment: String?, val personId: String, val eventId: String,
        val commandId: String, val recordedAt: Instant, val occurredAt: Instant)
    private data class SettlementPayload(val scope: String, val obligationId: String, val minorUnits: Long,
        val currency: String, val occurredAt: String, val reason: String, val commandId: String, val paymentEventId: String?)
    private data class ReverseFinancePayload(val scope: String, val eventId: String, val commandId: String, val reason: String)
    private data class FinanceCorrectionPayload(val scope: String, val eventId: String, val newMinorUnits: Long,
        val commandId: String, val reason: String)
    private data class FinanceAnnotationPayload(val scope: String, val eventId: String, val commandId: String,
        val annotation: String)
    private data class TaskEditPayload(val event: TaskEvent)

    private data class ParsedCell(val valid: Boolean, val value: Any?)

    private fun parseTableCell(column: TableColumnRow, raw: Any?): ParsedCell {
        if (raw == JSONObject.NULL) return ParsedCell(true, null)
        return try {
            when (ColumnType.valueOf(column.valueType)) {
                ColumnType.TEXT, ColumnType.PERSON_REF, ColumnType.PROJECT_REF -> when (raw) {
                    is String -> ParsedCell(true, raw)
                    is Number, is Boolean -> ParsedCell(true, raw.toString())
                    else -> ParsedCell(false, null)
                }
                ColumnType.INTEGER -> when (raw) {
                    is Int, is Long -> ParsedCell(true, (raw as Number).toLong())
                    is String -> raw.toLongOrNull()?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                    else -> ParsedCell(false, null)
                }
                ColumnType.DECIMAL -> (raw as? String ?: (raw as? Number)?.toString())
                    ?.takeIf { runCatching { java.math.BigDecimal(it); true }.getOrDefault(false) }
                    ?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                ColumnType.BOOLEAN -> when (raw) {
                    is Boolean -> ParsedCell(true, raw)
                    is String -> raw.toBooleanStrictOrNull()?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                    else -> ParsedCell(false, null)
                }
                ColumnType.DATE -> (raw as? String)?.takeIf { runCatching { LocalDate.parse(it); true }.getOrDefault(false) }
                    ?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                ColumnType.DATETIME -> (raw as? String)?.takeIf { runCatching { java.time.OffsetDateTime.parse(it); true }.getOrDefault(false) }
                    ?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                ColumnType.MONEY -> {
                    val values = when (raw) {
                        is JSONObject -> mapOf("minorUnits" to raw.getLong("minorUnits"), "currency" to raw.getString("currency"))
                        is Map<*, *> -> raw
                        is String -> {
                            val match = Regex("^\\s*([0-9]{1,14}(?:[.,][0-9]{1,2})?)(?:\\s+([A-Za-z]{3}))?\\s*$").matchEntire(raw)
                                ?: return ParsedCell(false, null)
                            val minor = java.math.BigDecimal(match.groupValues[1].replace(',', '.')).movePointRight(2).longValueExact()
                            mapOf("minorUnits" to minor, "currency" to match.groupValues[2].ifBlank { "AZN" }.uppercase())
                        }
                        else -> return ParsedCell(false, null)
                    }
                    val minor = values["minorUnits"] as? Number ?: return ParsedCell(false, null)
                    val currency = values["currency"] as? String ?: return ParsedCell(false, null)
                    if (minor.toLong() < 0 || runCatching { java.util.Currency.getInstance(currency) }.isFailure) ParsedCell(false, null)
                    else ParsedCell(true, mapOf("minorUnits" to minor.toLong(), "currency" to currency))
                }
            }
        } catch (_: Exception) { ParsedCell(false, null) }
    }

    private fun rejected() = ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_arguments")
    private fun exactPositiveLong(value: Any?): Long? = (value as? Number)?.let { number ->
        runCatching { java.math.BigDecimal(number.toString()).longValueExact() }.getOrNull()
    }?.takeIf { it > 0 }
    private fun moneyAmountArgument(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.length <= 32 }
        is Number -> runCatching {
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString().takeIf { it.length <= 32 }
        }.getOrNull()
        else -> null
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private const val MAX_WORK_TASKS = 16
private const val MAX_WORK_CASES = 12
private val TERMINAL_CASE_STATUSES = setOf("completed", "archived", "cancelled")
