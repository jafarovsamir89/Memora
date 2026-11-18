package ai.memora.app

import android.os.Bundle
import android.os.SystemClock
import androidx.room.withTransaction
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.CaseRevisionRow
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.DomainRevisionRow
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.TableColumnRow
import ai.memora.app.data.protectedstore.TableDataRow
import ai.memora.app.data.protectedstore.UserTableRow
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Synthetic-only bounded fixtures for the real scoped Cases, Notes and Tables read paths. */
internal object SyntheticDomainVolumeProbe {
    private const val CASE_MARKER = "synthetic-volume-case-"
    private const val NOTE_MARKER = "synthetic-volume-note-"
    private const val TABLE_ID = "synthetic-volume-table"
    private const val TABLE_ROW_MARKER = "synthetic-volume-table-row-"
    private const val PERSON_MARKER = "synthetic-volume-domain-person-"
    private const val SOURCE_MARKER = "synthetic-volume-source-"
    private const val TABLE_TITLE = "Synthetic volume table"
    private val allowedCounts = setOf(100, 1_000)

    fun run(database: AgentDatabase, scope: String, targetCount: Int): Bundle {
        require(targetCount in allowedCounts) { "unsupported_synthetic_domain_volume" }
        val dao = database.protectedStoreDao()
        val caseRepository = ProtectedCaseRepository(database)
        val domainRepository = ProtectedDomainRepository(database)

        val personCount = 100
        val peopleBefore = runBlocking { domainRepository.domainObjects(scope, DomainKind.PERSON, limit = 200) }
        check(peopleBefore.all { it.objectId.startsWith(PERSON_MARKER) }) { "unexpected_domain_people_in_volume_profile" }
        check(peopleBefore.size <= personCount) { "person_fixture_cannot_shrink" }
        peopleBefore.forEachIndexed { index, row ->
            check(row == personAt(index, scope)) { "person_fixture_not_contiguous_or_changed" }
            check(runBlocking { dao.domainRevisions(row.objectId) } == listOf(personRevisionAt(index, scope))) {
                "person_revision_fixture_mismatch"
            }
        }
        var insertedPeople = 0
        val peopleSeedStarted = SystemClock.elapsedRealtimeNanos()
        for (index in peopleBefore.size until personCount) {
            runBlocking {
                domainRepository.createObject(
                    scope = scope,
                    id = "$PERSON_MARKER${suffix(index)}",
                    kind = DomainKind.PERSON,
                    title = personAt(index, scope).title,
                    body = personAt(index, scope).body,
                    sourceRevisionId = null,
                    now = timeAt(index),
                )
            }
            insertedPeople++
        }
        val peopleSeedMillis = (SystemClock.elapsedRealtimeNanos() - peopleSeedStarted) / 1_000_000L
        val allPeople = runBlocking { domainRepository.domainObjects(scope, DomainKind.PERSON, limit = 200) }
        check(allPeople == (0 until personCount).map { personAt(it, scope) }) { "person_fixture_readback_mismatch" }
        allPeople.forEachIndexed { index, row ->
            check(runBlocking { dao.domainRevisions(row.objectId) } == listOf(personRevisionAt(index, scope))) {
                "person_revision_readback_mismatch"
            }
        }
        val peopleRepositorySamples = samples {
            domainRepository.domainObjects(scope, DomainKind.PERSON, limit = 200)
                .also { check(it == allPeople) { "person_repository_sample_mismatch" } }
        }
        val personPageSamples = samples {
            domainRepository.domainObjectsPage(scope, DomainKind.PERSON, limit = 30, offset = 70)
                .also { check(it == allPeople.drop(70).take(30)) { "person_page_sample_mismatch" } }
        }
        val personSearchSamples = samplesMicros {
            allPeople.filter { row ->
                row.title.lowercase().contains("volume person 0099") || row.body.orEmpty().lowercase().contains("volume person 0099")
            }.also { check(it.singleOrNull()?.objectId == "${PERSON_MARKER}0099") { "person_search_exact_id_mismatch" } }
        }

        val casesBefore = readAllCases(caseRepository, scope)
        check(casesBefore.all { it.caseId.startsWith(CASE_MARKER) }) { "unexpected_cases_in_volume_profile" }
        check(casesBefore.size <= targetCount) { "case_fixture_cannot_shrink" }
        casesBefore.forEachIndexed { position, row ->
            val index = casesBefore.lastIndex - position
            check(row == caseAt(index, scope)) { "case_fixture_not_contiguous_or_changed" }
            check(runBlocking { dao.caseRevisions(row.caseId) } == listOf(caseRevisionAt(index, scope))) {
                "case_revision_fixture_mismatch"
            }
        }

        val notesBefore = readAllNotes(domainRepository, scope)
        check(notesBefore.all { it.objectId.startsWith(NOTE_MARKER) }) { "unexpected_notes_in_volume_profile" }
        check(notesBefore.size <= targetCount) { "note_fixture_cannot_shrink" }
        notesBefore.forEachIndexed { index, row ->
            check(row == noteAt(index, scope)) { "note_fixture_not_contiguous_or_changed" }
            check(runBlocking { dao.domainRevisions(row.objectId) } == listOf(noteRevisionAt(index))) {
                "note_revision_fixture_mismatch"
            }
        }

        val initialTable = UserTableRow(TABLE_ID, scope, TABLE_TITLE, "editable", 1, 1, null, null)
        val activeTables = runBlocking { dao.activeUserTables(scope) }
        check(activeTables.all { it.tableId == TABLE_ID }) { "unexpected_tables_in_volume_profile" }
        if (activeTables.isEmpty()) {
            check(runBlocking { dao.userTable(scope, TABLE_ID) } == null) { "inactive_table_marker_collision" }
            runBlocking {
                domainRepository.createTable(
                    scope = scope,
                    id = TABLE_ID,
                    title = TABLE_TITLE,
                    mode = "editable",
                    schema = listOf(ColumnSpec("description", "Description", ColumnType.TEXT)),
                    now = "2026-11-18T00:00:00Z",
                )
            }
        } else {
            check(activeTables == listOf(initialTable)) { "table_fixture_changed" }
            check(runBlocking { dao.columns(TABLE_ID) } == listOf(
                TableColumnRow(TABLE_ID, "description", 0, "Description", "TEXT", false, JSONObject().toString())
            )) { "table_columns_fixture_changed" }
        }
        val tableRowsBefore = runBlocking { dao.rows(TABLE_ID, scope) }
        check(tableRowsBefore.all { it.rowId.startsWith(TABLE_ROW_MARKER) }) { "unexpected_table_rows_in_volume_profile" }
        check(tableRowsBefore.size <= targetCount) { "table_fixture_cannot_shrink" }
        tableRowsBefore.forEachIndexed { index, row ->
            check(row == tableRowAt(index, scope)) { "table_fixture_not_contiguous_or_changed" }
        }

        var insertedCases = 0
        var insertedNotes = 0
        var insertedTableRows = 0
        val seedStarted = SystemClock.elapsedRealtimeNanos()
        runBlocking {
            database.withTransaction {
                for (index in casesBefore.size until targetCount) {
                    dao.insertCase(caseAt(index, scope))
                    dao.insertCaseRevision(caseRevisionAt(index, scope))
                    insertedCases++
                }
                if (notesBefore.size < targetCount) {
                    val newNotes = (notesBefore.size until targetCount).map { noteAt(it, scope) }
                    dao.insertDomainObjects(newNotes)
                    newNotes.forEachIndexed { offset, note -> dao.insertDomainRevision(noteRevisionAt(notesBefore.size + offset)) }
                    insertedNotes = newNotes.size
                }
                if (tableRowsBefore.size < targetCount) {
                    val newRows = (tableRowsBefore.size until targetCount).map { tableRowAt(it, scope) }
                    dao.insertTableRows(newRows)
                    insertedTableRows = newRows.size
                }
            }
        }
        val seedMillis = (SystemClock.elapsedRealtimeNanos() - seedStarted) / 1_000_000L

        val allCases = readAllCases(caseRepository, scope)
        check(allCases.size == targetCount && allCases == (targetCount - 1 downTo 0).map { caseAt(it, scope) }) {
            "case_fixture_readback_mismatch"
        }
        val allNotes = readAllNotes(domainRepository, scope)
        check(allNotes == (0 until targetCount).map { noteAt(it, scope) }) { "note_fixture_readback_mismatch" }
        val tableSnapshot = runBlocking { domainRepository.tables(scope) }.single()
        check(tableSnapshot.table == initialTable && tableSnapshot.rows.size == targetCount) { "table_fixture_readback_mismatch" }
        tableSnapshot.rows.forEachIndexed { index, row ->
            check(row == tableRowAt(index, scope)) { "table_row_fixture_readback_mismatch" }
        }

        val lastSuffix = (targetCount - 1).toString().padStart(4, '0')
        val pageStarted = SystemClock.elapsedRealtimeNanos()
        val caseLastPage = runBlocking { caseRepository.cases(scope, limit = 30, offset = targetCount - 30) }
        val casePageInitialMicros = (SystemClock.elapsedRealtimeNanos() - pageStarted) / 1_000L
        check(caseLastPage.size == 30 && caseLastPage.map { it.caseId }.toSet().size == 30) {
            "case_page_bounds_mismatch"
        }

        val casePageSamples = samples {
            caseRepository.cases(scope, limit = 30, offset = targetCount - 30)
                .also { check(it.size == 30) { "case_page_sample_mismatch" } }
        }
        val caseSearchSamples = samples {
            caseRepository.searchCases(scope, "volume marker $lastSuffix", limit = 1)
                .also { check(it.singleOrNull()?.caseId == "$CASE_MARKER$lastSuffix") { "case_search_exact_id_mismatch" } }
        }
        val notePageSamples = samples {
            domainRepository.domainObjectsPage(scope, DomainKind.NOTE, limit = 30, offset = targetCount - 30)
                .also { check(it.size == 30) { "note_page_sample_mismatch" } }
        }
        val tableSnapshotSamples = samples {
            domainRepository.tables(scope).single().rows.also { check(it.size == targetCount) { "table_snapshot_sample_mismatch" } }
        }

        return Bundle().apply {
            putInt("synthetic_case_rows", allCases.size)
            putInt("synthetic_note_rows", allNotes.size)
            putInt("synthetic_table_rows", tableSnapshot.rows.size)
            putInt("case_rows_inserted_this_run", insertedCases)
            putInt("note_rows_inserted_this_run", insertedNotes)
            putInt("table_rows_inserted_this_run", insertedTableRows)
            putLong("domain_seed_insert_ms", seedMillis)
            putLong("case_last_page_initial_us", casePageInitialMicros)
            putString("case_last_page_first_id", caseLastPage.first().caseId)
            putString("case_search_expected_id", "$CASE_MARKER$lastSuffix")
            putString("cross_domain_fixture_digest_sha256", digest(allCases, allNotes, tableSnapshot.rows))
            putInt("synthetic_people_count", allPeople.size)
            putInt("person_rows_inserted_this_run", insertedPeople)
            putLong("person_seed_ms", peopleSeedMillis)
            putString("person_fixture_digest_sha256", digestPeople(allPeople))
            putSamples(this, "people_repository", peopleRepositorySamples)
            putSamples(this, "people_page", personPageSamples)
            putMicroSamples(this, "people_search", personSearchSamples)
            putString("domain_measurement_scope", "warm_local_room_repository_reads_20_samples_per_path_no_ui_network")
            putSamples(this, "case_page", casePageSamples)
            putSamples(this, "case_search", caseSearchSamples)
            putSamples(this, "note_page", notePageSamples)
            putSamples(this, "table_snapshot", tableSnapshotSamples)
        }
    }

    private fun readAllCases(repository: ProtectedCaseRepository, scope: String): List<CaseRecordRow> = runBlocking {
        buildList {
            var offset = 0
            while (true) {
                val page = repository.cases(scope, limit = 100, offset = offset)
                addAll(page)
                offset += page.size
                if (page.size < 100) break
            }
        }
    }

    private fun readAllNotes(repository: ProtectedDomainRepository, scope: String): List<DomainObjectRow> = runBlocking {
        buildList {
            var offset = 0
            while (true) {
                val page = repository.domainObjectsPage(scope, DomainKind.NOTE, limit = 100, offset = offset)
                addAll(page)
                offset += page.size
                if (page.size < 100) break
            }
        }
    }

    private fun caseAt(index: Int, scope: String): CaseRecordRow {
        val suffix = suffix(index)
        val time = timeAt(index)
        return CaseRecordRow(
            caseId = "$CASE_MARKER$suffix",
            scopeId = scope,
            title = "Synthetic volume marker $suffix",
            summary = "Synthetic case summary $suffix",
            status = "open",
            nextAction = "Review synthetic item $suffix",
            version = 1,
            sourceTurnId = "$SOURCE_MARKER$index",
            createdAt = time,
            updatedAt = time,
            deletedAt = null,
        )
    }

    private fun caseRevisionAt(index: Int, scope: String): CaseRevisionRow {
        val row = caseAt(index, scope)
        val payload = JSONObject().put("title", row.title).put("summary", row.summary)
            .put("status", row.status).put("nextAction", row.nextAction).toString()
        return CaseRevisionRow(row.caseId, 1, payload, row.sourceTurnId, row.updatedAt)
    }

    private fun noteAt(index: Int, scope: String): DomainObjectRow {
        val suffix = suffix(index)
        return DomainObjectRow(
            objectId = "$NOTE_MARKER$suffix",
            scopeId = scope,
            kind = DomainKind.NOTE.name,
            title = "Synthetic volume note $suffix",
            body = "Synthetic volume note body $suffix",
            status = "active",
            version = 1,
            deletedAt = null,
        )
    }

    private fun personAt(index: Int, scope: String): DomainObjectRow {
        val suffix = suffix(index)
        return DomainObjectRow(
            objectId = "$PERSON_MARKER$suffix",
            scopeId = scope,
            kind = DomainKind.PERSON.name,
            title = "Synthetic volume person $suffix",
            body = "Synthetic volume person note $suffix",
            status = "active",
            version = 1,
            deletedAt = null,
        )
    }

    private fun personRevisionAt(index: Int, scope: String): DomainRevisionRow {
        val row = personAt(index, scope)
        val payload = JSONObject().put("title", row.title).put("body", row.body)
            .put("status", row.status).put("deletedAt", JSONObject.NULL).toString()
        return DomainRevisionRow(row.objectId, 1, payload, null, timeAt(index))
    }

    private fun noteRevisionAt(index: Int): DomainRevisionRow {
        val row = noteAt(index, "")
        val payload = JSONObject().put("title", row.title).put("body", row.body).put("status", row.status).toString()
        return DomainRevisionRow(row.objectId, 1, payload, null, timeAt(index))
    }

    private fun tableRowAt(index: Int, scope: String): TableDataRow {
        val suffix = suffix(index)
        val values = JSONObject().put("description", "Synthetic volume table row $suffix").toString()
        return TableDataRow("$TABLE_ROW_MARKER$suffix", TABLE_ID, scope, 1, values, null)
    }

    private fun timeAt(index: Int): String = Instant.parse("2026-11-17T00:00:00Z").plusSeconds(index.toLong()).toString()
    private fun suffix(index: Int): String = index.toString().padStart(4, '0')

    private fun samples(read: suspend () -> Any): LongArray = runBlocking {
        read()
        LongArray(20) {
            val started = SystemClock.elapsedRealtimeNanos()
            read()
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        }
    }

    private fun putSamples(result: Bundle, name: String, values: LongArray) {
        val sorted = values.sorted()
        result.putLongArray("${name}_samples_us", values)
        result.putLong("${name}_p50_us", sorted[sorted.size / 2])
        result.putLong("${name}_p95_us", sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)])
        result.putInt("${name}_sample_count", values.size)
    }

    private fun samplesMicros(read: () -> Unit): LongArray {
        read()
        return LongArray(20) {
            val started = SystemClock.elapsedRealtimeNanos()
            read()
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        }
    }

    private fun putMicroSamples(result: Bundle, name: String, values: LongArray) {
        val sorted = values.sorted()
        result.putLongArray("${name}_samples_us", values)
        result.putLong("${name}_p50_us", sorted[sorted.size / 2])
        result.putLong("${name}_p95_us", sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)])
        result.putInt("${name}_sample_count", values.size)
    }

    private fun digest(cases: List<CaseRecordRow>, notes: List<DomainObjectRow>, rows: List<TableDataRow>): String {
        val canonical = (cases.map { "${it.caseId}|${it.title}|${it.status}|${it.updatedAt}" } +
            notes.map { "${it.objectId}|${it.title}|${it.version}" } +
            rows.map { "${it.rowId}|${it.tableId}|${it.valuesJson}" }).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun digestPeople(people: List<DomainObjectRow>): String {
        val canonical = people.joinToString("\n") { "${it.objectId}|${it.scopeId}|${it.title}|${it.version}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
