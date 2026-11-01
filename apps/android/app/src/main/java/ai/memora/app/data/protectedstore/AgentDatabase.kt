package ai.memora.app.data.protectedstore

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal val AGENT_DB_1_TO_2 = androidx.room.migration.Migration(1, 2) { db ->
    db.execSQL("CREATE TABLE IF NOT EXISTS memory_draft (id INTEGER NOT NULL, text TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(id))")
    db.execSQL("CREATE TABLE IF NOT EXISTS memory_source (sourceId TEXT NOT NULL, commandId TEXT NOT NULL, draftVersion INTEGER, zone TEXT NOT NULL, origin TEXT NOT NULL, archived INTEGER NOT NULL, organizationVersion INTEGER NOT NULL, scopeId TEXT NOT NULL, PRIMARY KEY(sourceId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS memory_revision (sourceId TEXT NOT NULL, revisionId TEXT NOT NULL, number INTEGER NOT NULL, text TEXT NOT NULL, recordedAt TEXT NOT NULL, PRIMARY KEY(sourceId, revisionId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS memory_tombstone (commandId TEXT NOT NULL, sourceId TEXT NOT NULL, revisionId TEXT NOT NULL, priorRevisionIdsJson TEXT NOT NULL, PRIMARY KEY(commandId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS task_event (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId TEXT NOT NULL, scopeId TEXT NOT NULL, actorId TEXT NOT NULL, commandId TEXT NOT NULL, type TEXT NOT NULL, previousVersion INTEGER, title TEXT, completed INTEGER, dueLocal TEXT, dueZone TEXT, remind INTEGER, personLabel TEXT, projectLabel TEXT)")
    db.execSQL("CREATE TABLE IF NOT EXISTS deleted_task (taskId TEXT NOT NULL, PRIMARY KEY(taskId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS person (personId TEXT NOT NULL, displayName TEXT NOT NULL, PRIMARY KEY(personId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS money_event (eventId TEXT NOT NULL, commandId TEXT NOT NULL, personId TEXT NOT NULL, kind TEXT NOT NULL, minorUnits INTEGER NOT NULL, currency TEXT NOT NULL, source TEXT NOT NULL, recordedAt TEXT NOT NULL, occurredAt TEXT, PRIMARY KEY(eventId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS legacy_migration (storeName TEXT NOT NULL, sourceDigest TEXT NOT NULL, schemaVersion INTEGER NOT NULL, itemCount INTEGER NOT NULL, verified INTEGER NOT NULL, PRIMARY KEY(storeName))")
    db.execSQL("CREATE TABLE IF NOT EXISTS agent_outbox (operationId TEXT NOT NULL, commandId TEXT NOT NULL, digest TEXT NOT NULL, status TEXT NOT NULL, targetVersionsJson TEXT NOT NULL, PRIMARY KEY(operationId))")
}

internal val AGENT_DB_2_TO_3 = androidx.room.migration.Migration(2, 3) { db ->
    db.execSQL("CREATE TABLE IF NOT EXISTS agent_turn_journal (turnId TEXT NOT NULL, status TEXT NOT NULL, updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(turnId))")
}

/** P3–P6 domain tables. Additive only; P1 acceptance still gates application workflow cutover. */
internal val AGENT_DB_3_TO_4 = androidx.room.migration.Migration(3, 4) { db ->
    db.execSQL("CREATE TABLE IF NOT EXISTS domain_object (objectId TEXT NOT NULL, scopeId TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT, status TEXT NOT NULL, version INTEGER NOT NULL, deletedAt TEXT, PRIMARY KEY(objectId))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_domain_object_scopeId_kind_deletedAt ON domain_object(scopeId, kind, deletedAt)")
    db.execSQL("CREATE TABLE IF NOT EXISTS domain_revision (objectId TEXT NOT NULL, version INTEGER NOT NULL, payloadJson TEXT NOT NULL, sourceRevisionId TEXT, changedAt TEXT NOT NULL, PRIMARY KEY(objectId, version))")
    db.execSQL("CREATE TABLE IF NOT EXISTS domain_link (linkId TEXT NOT NULL, scopeId TEXT NOT NULL, fromId TEXT NOT NULL, toId TEXT NOT NULL, relation TEXT NOT NULL, version INTEGER NOT NULL, deletedAt TEXT, PRIMARY KEY(linkId))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_domain_link_scopeId_fromId_toId ON domain_link(scopeId, fromId, toId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS user_table (tableId TEXT NOT NULL, scopeId TEXT NOT NULL, title TEXT NOT NULL, mode TEXT NOT NULL, schemaVersion INTEGER NOT NULL, version INTEGER NOT NULL, deletedAt TEXT, PRIMARY KEY(tableId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS table_column (tableId TEXT NOT NULL, columnId TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, valueType TEXT NOT NULL, required INTEGER NOT NULL, configJson TEXT NOT NULL, PRIMARY KEY(tableId, columnId))")
    db.execSQL("CREATE TABLE IF NOT EXISTS table_row (rowId TEXT NOT NULL, tableId TEXT NOT NULL, scopeId TEXT NOT NULL, version INTEGER NOT NULL, valuesJson TEXT NOT NULL, deletedAt TEXT, PRIMARY KEY(rowId))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_table_row_tableId_scopeId_deletedAt ON table_row(tableId, scopeId, deletedAt)")
    db.execSQL("CREATE TABLE IF NOT EXISTS money_adjustment (eventId TEXT NOT NULL, commandId TEXT NOT NULL, obligationId TEXT NOT NULL, kind TEXT NOT NULL, minorUnits INTEGER NOT NULL, currency TEXT NOT NULL, occurredAt TEXT, reversesEventId TEXT, reason TEXT NOT NULL, PRIMARY KEY(eventId))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_money_adjustment_obligationId ON money_adjustment(obligationId)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_money_adjustment_commandId ON money_adjustment(commandId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS local_calendar_event (eventId TEXT NOT NULL, scopeId TEXT NOT NULL, title TEXT NOT NULL, startLocal TEXT NOT NULL, endLocal TEXT, zoneId TEXT NOT NULL, timePrecision TEXT NOT NULL, version INTEGER NOT NULL, status TEXT NOT NULL, PRIMARY KEY(eventId))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_local_calendar_event_scopeId_startLocal ON local_calendar_event(scopeId, startLocal)")
}

internal val AGENT_DB_4_TO_5 = androidx.room.migration.Migration(4, 5) { db ->
    db.execSQL("CREATE TABLE IF NOT EXISTS table_schema_revision (tableId TEXT NOT NULL, version INTEGER NOT NULL, columnsJson TEXT NOT NULL, changedAt TEXT NOT NULL, PRIMARY KEY(tableId, version))")
}

internal val AGENT_DB_5_TO_6 = androidx.room.migration.Migration(5, 6) { db ->
    db.execSQL("ALTER TABLE money_event ADD COLUMN scopeId TEXT NOT NULL DEFAULT 'local'")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_money_event_scopeId_eventId ON money_event(scopeId, eventId)")
    db.execSQL("ALTER TABLE money_adjustment ADD COLUMN scopeId TEXT NOT NULL DEFAULT 'local'")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_money_adjustment_scopeId_obligationId ON money_adjustment(scopeId, obligationId)")
}

/** Minimal command journal. Only identifiers, digest, status and recovery versions are retained. */
@Entity(tableName = "agent_command_journal")
internal data class AgentCommandJournalRow(
    @PrimaryKey val commandId: String,
    val digest: String,
    val status: String,
    /** JSON array of only related IDs and the minimum target versions needed for recovery. */
    val targetVersionsJson: String,
)

/** Ephemeral execution state only; never stores request, transcript, prompt, or model output. */
@Entity(tableName = "agent_turn_journal")
internal data class AgentTurnJournalRow(
    @PrimaryKey val turnId: String,
    val status: String,
    val updatedAtMillis: Long,
)

@Entity(tableName = "domain_object", indices = [androidx.room.Index(value = ["scopeId", "kind", "deletedAt"])])
internal data class DomainObjectRow(@PrimaryKey val objectId: String, val scopeId: String, val kind: String,
    val title: String, val body: String?, val status: String, val version: Long, val deletedAt: String?)

@Entity(tableName = "domain_revision", primaryKeys = ["objectId", "version"])
internal data class DomainRevisionRow(val objectId: String, val version: Long, val payloadJson: String,
    val sourceRevisionId: String?, val changedAt: String)

@Entity(tableName = "domain_link", indices = [androidx.room.Index(value = ["scopeId", "fromId", "toId"])])
internal data class DomainLinkRow(@PrimaryKey val linkId: String, val scopeId: String, val fromId: String,
    val toId: String, val relation: String, val version: Long, val deletedAt: String?)

@Entity(tableName = "user_table")
internal data class UserTableRow(@PrimaryKey val tableId: String, val scopeId: String, val title: String,
    val mode: String, val schemaVersion: Long, val version: Long, val deletedAt: String?)

@Entity(tableName = "table_column", primaryKeys = ["tableId", "columnId"])
internal data class TableColumnRow(val tableId: String, val columnId: String, val position: Int, val name: String,
    val valueType: String, val required: Boolean, val configJson: String)

@Entity(tableName = "table_schema_revision", primaryKeys = ["tableId", "version"])
internal data class TableSchemaRevisionRow(val tableId: String, val version: Long, val columnsJson: String, val changedAt: String)

@Entity(tableName = "table_row", indices = [androidx.room.Index(value = ["tableId", "scopeId", "deletedAt"])])
internal data class TableDataRow(@PrimaryKey val rowId: String, val tableId: String, val scopeId: String,
    val version: Long, val valuesJson: String, val deletedAt: String?)

@Entity(tableName = "money_adjustment", indices = [androidx.room.Index(value = ["obligationId"]), androidx.room.Index(value = ["commandId"], unique = true), androidx.room.Index(value = ["scopeId", "obligationId"])])
internal data class MoneyAdjustmentRow(@PrimaryKey val eventId: String, val commandId: String, val obligationId: String,
    val kind: String, val minorUnits: Long, val currency: String, val occurredAt: String?, val reversesEventId: String?, val reason: String,
    @androidx.room.ColumnInfo(defaultValue = "'local'") val scopeId: String = "local")

@Entity(tableName = "local_calendar_event", indices = [androidx.room.Index(value = ["scopeId", "startLocal"])])
internal data class LocalCalendarEventRow(@PrimaryKey val eventId: String, val scopeId: String, val title: String,
    val startLocal: String, val endLocal: String?, val zoneId: String, val timePrecision: String,
    val version: Long, val status: String)

@Entity(tableName = "memory_draft")
internal data class MemoryDraftRow(@PrimaryKey val id: Int = 1, val text: String, val version: Long)

@Entity(tableName = "memory_source")
internal data class MemorySourceRow(
    @PrimaryKey val sourceId: String, val commandId: String, val draftVersion: Long?, val zone: String,
    val origin: String, val archived: Boolean, val organizationVersion: Long, val scopeId: String,
)

@Entity(tableName = "memory_revision", primaryKeys = ["sourceId", "revisionId"])
internal data class MemoryRevisionRow(
    val sourceId: String, val revisionId: String, val number: Long, val text: String, val recordedAt: String,
)

@Entity(tableName = "memory_tombstone")
internal data class MemoryTombstoneRow(
    @PrimaryKey val commandId: String, val sourceId: String, val revisionId: String, val priorRevisionIdsJson: String,
)

@Entity(tableName = "task_event")
internal data class TaskEventRow(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0, val taskId: String, val scopeId: String,
    val actorId: String, val commandId: String, val type: String, val previousVersion: Long?,
    val title: String?, val completed: Boolean?, val dueLocal: String?, val dueZone: String?,
    val remind: Boolean?, val personLabel: String?, val projectLabel: String?,
)

@Entity(tableName = "deleted_task")
internal data class DeletedTaskRow(@PrimaryKey val taskId: String)

@Entity(tableName = "person")
internal data class PersonRow(@PrimaryKey val personId: String, val displayName: String)

@Entity(tableName = "money_event", indices = [androidx.room.Index(value = ["scopeId", "eventId"])])
internal data class MoneyEventRow(
    @PrimaryKey val eventId: String, val commandId: String, val personId: String, val kind: String,
    val minorUnits: Long, val currency: String, val source: String, val recordedAt: String, val occurredAt: String?,
    @androidx.room.ColumnInfo(defaultValue = "'local'") val scopeId: String = "local",
)

@Entity(tableName = "legacy_migration")
internal data class LegacyMigrationRow(
    @PrimaryKey val storeName: String, val sourceDigest: String, val schemaVersion: Int,
    val itemCount: Int, val verified: Boolean,
)

@Entity(tableName = "agent_outbox")
internal data class AgentOutboxRow(
    @PrimaryKey val operationId: String, val commandId: String, val digest: String, val status: String,
    val targetVersionsJson: String,
)

@androidx.room.Dao
internal interface ProtectedStoreDao {
    @androidx.room.Query("SELECT * FROM memory_draft WHERE id = 1") suspend fun draft(): MemoryDraftRow?
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putDraft(row: MemoryDraftRow)
    @androidx.room.Query("SELECT * FROM memory_source ORDER BY sourceId") suspend fun sources(): List<MemorySourceRow>
    @androidx.room.Query("SELECT * FROM memory_revision ORDER BY sourceId, number") suspend fun revisions(): List<MemoryRevisionRow>
    @androidx.room.Query("SELECT * FROM memory_tombstone ORDER BY commandId") suspend fun tombstones(): List<MemoryTombstoneRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putSources(rows: List<MemorySourceRow>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putRevisions(rows: List<MemoryRevisionRow>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putTombstones(rows: List<MemoryTombstoneRow>)
    @androidx.room.Query("SELECT * FROM task_event ORDER BY sequence") suspend fun taskEvents(): List<TaskEventRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putTaskEvents(rows: List<TaskEventRow>)
    @androidx.room.Query("SELECT * FROM task_event WHERE commandId=:id") suspend fun taskEventsForCommand(id: String): List<TaskEventRow>
    @androidx.room.Query("SELECT taskId FROM deleted_task ORDER BY taskId") suspend fun deletedTasks(): List<String>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putDeletedTasks(rows: List<DeletedTaskRow>)
    @androidx.room.Query("SELECT * FROM person ORDER BY personId") suspend fun people(): List<PersonRow>
    @androidx.room.Query("SELECT * FROM money_event ORDER BY eventId") suspend fun moneyEvents(): List<MoneyEventRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putPeople(rows: List<PersonRow>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putMoneyEvents(rows: List<MoneyEventRow>)
    @androidx.room.Query("SELECT * FROM legacy_migration ORDER BY storeName") suspend fun migrations(): List<LegacyMigrationRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putMigrations(rows: List<LegacyMigrationRow>)
    @androidx.room.Query("SELECT * FROM agent_command_journal WHERE commandId = :id") suspend fun command(id: String): AgentCommandJournalRow?
    @androidx.room.Query("SELECT * FROM agent_command_journal WHERE status IN ('prepared', 'applying', 'reconciling') ORDER BY commandId")
    suspend fun unresolvedAgentCommands(): List<AgentCommandJournalRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putCommand(row: AgentCommandJournalRow)
    @androidx.room.Query("DELETE FROM agent_command_journal WHERE commandId = :id") suspend fun deleteCommand(id: String)
    @androidx.room.Query("SELECT * FROM agent_turn_journal WHERE turnId = :id") suspend fun turn(id: String): AgentTurnJournalRow?
    @androidx.room.Query("SELECT * FROM agent_turn_journal ORDER BY updatedAtMillis, turnId") suspend fun turns(): List<AgentTurnJournalRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun putTurn(row: AgentTurnJournalRow)
    @androidx.room.Query("DELETE FROM agent_turn_journal WHERE turnId = :id") suspend fun deleteTurn(id: String)
    @androidx.room.Query("UPDATE agent_command_journal SET status = :status WHERE commandId = :id AND status = :expected")
    suspend fun transitionCommand(id: String, expected: String, status: String): Int
    @androidx.room.Query("SELECT * FROM agent_outbox WHERE operationId = :id") suspend fun outbox(id: String): AgentOutboxRow?
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun putOutbox(row: AgentOutboxRow)
    @androidx.room.Query("UPDATE agent_outbox SET status = :status WHERE operationId = :id") suspend fun updateOutbox(id: String, status: String)
    @androidx.room.Query("DELETE FROM agent_outbox WHERE operationId = :id") suspend fun deleteOutbox(id: String)

    @androidx.room.Query("SELECT * FROM domain_object WHERE scopeId = :scope AND objectId = :id") suspend fun domainObject(scope: String, id: String): DomainObjectRow?
    @androidx.room.Query("SELECT * FROM domain_object WHERE scopeId = :scope AND deletedAt IS NULL ORDER BY kind, title, objectId") suspend fun activeObjects(scope: String): List<DomainObjectRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertDomainObject(row: DomainObjectRow)
    @androidx.room.Query("UPDATE domain_object SET title=:title, body=:body, status=:status, version=:nextVersion WHERE scopeId=:scope AND objectId=:id AND version=:expected AND deletedAt IS NULL") suspend fun updateDomainObject(scope: String, id: String, expected: Long, nextVersion: Long, title: String, body: String?, status: String): Int
    @androidx.room.Query("UPDATE domain_object SET deletedAt=:at, version=:nextVersion WHERE scopeId=:scope AND objectId=:id AND version=:expected AND deletedAt IS NULL") suspend fun tombstoneDomainObject(scope: String, id: String, expected: Long, nextVersion: Long, at: String): Int
    @androidx.room.Query("UPDATE domain_object SET deletedAt=NULL, version=:nextVersion WHERE scopeId=:scope AND objectId=:id AND version=:expected AND deletedAt IS NOT NULL") suspend fun restoreDomainObject(scope: String, id: String, expected: Long, nextVersion: Long): Int
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertDomainRevision(row: DomainRevisionRow)
    @androidx.room.Query("SELECT * FROM domain_revision WHERE objectId=:id ORDER BY version") suspend fun domainRevisions(id: String): List<DomainRevisionRow>
    @androidx.room.Query("SELECT l.* FROM domain_link l INNER JOIN domain_object f ON f.objectId=l.fromId AND f.scopeId=l.scopeId INNER JOIN domain_object t ON t.objectId=l.toId AND t.scopeId=l.scopeId WHERE l.scopeId=:scope AND l.deletedAt IS NULL AND f.deletedAt IS NULL AND t.deletedAt IS NULL ORDER BY l.linkId") suspend fun activeLinks(scope: String): List<DomainLinkRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertDomainLink(row: DomainLinkRow)
    @androidx.room.Query("SELECT * FROM user_table WHERE scopeId=:scope AND tableId=:id") suspend fun userTable(scope: String, id: String): UserTableRow?
    @androidx.room.Query("SELECT * FROM user_table WHERE scopeId=:scope AND deletedAt IS NULL ORDER BY title, tableId") suspend fun activeUserTables(scope: String): List<UserTableRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertUserTable(row: UserTableRow)
    @androidx.room.Query("SELECT * FROM table_column WHERE tableId=:id ORDER BY position") suspend fun columns(id: String): List<TableColumnRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertColumns(rows: List<TableColumnRow>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertSchemaRevision(row: TableSchemaRevisionRow)
    @androidx.room.Query("SELECT * FROM table_schema_revision WHERE tableId=:id ORDER BY version") suspend fun schemaRevisions(id: String): List<TableSchemaRevisionRow>
    @androidx.room.Update suspend fun updateColumns(rows: List<TableColumnRow>)
    @androidx.room.Query("UPDATE user_table SET schemaVersion=:next, version=:next WHERE scopeId=:scope AND tableId=:id AND schemaVersion=:expected AND deletedAt IS NULL") suspend fun updateTableSchemaVersion(scope: String, id: String, expected: Long, next: Long): Int
    @androidx.room.Query("UPDATE user_table SET title=:title, version=:next WHERE scopeId=:scope AND tableId=:id AND version=:expected AND deletedAt IS NULL") suspend fun renameUserTable(scope: String, id: String, expected: Long, next: Long, title: String): Int
    @androidx.room.Query("UPDATE user_table SET deletedAt=:at, version=:next WHERE scopeId=:scope AND tableId=:id AND version=:expected AND deletedAt IS NULL") suspend fun tombstoneUserTable(scope: String, id: String, expected: Long, next: Long, at: String): Int
    @androidx.room.Query("UPDATE user_table SET deletedAt=NULL, version=:next WHERE scopeId=:scope AND tableId=:id AND version=:expected AND deletedAt IS NOT NULL") suspend fun restoreUserTable(scope: String, id: String, expected: Long, next: Long): Int
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertTableRows(rows: List<TableDataRow>)
    @androidx.room.Query("SELECT * FROM table_row WHERE tableId=:id AND scopeId=:scope AND deletedAt IS NULL ORDER BY rowId") suspend fun rows(id: String, scope: String): List<TableDataRow>
    @androidx.room.Query("SELECT * FROM table_row WHERE rowId=:id") suspend fun rowById(id: String): TableDataRow?
    @androidx.room.Query("UPDATE table_row SET valuesJson=:values, version=:next WHERE rowId=:row AND tableId=:table AND scopeId=:scope AND version=:expected AND deletedAt IS NULL") suspend fun updateTableRow(row: String, table: String, scope: String, expected: Long, next: Long, values: String): Int
    @androidx.room.Query("UPDATE table_row SET deletedAt=:at, version=:next WHERE rowId=:row AND tableId=:table AND scopeId=:scope AND version=:expected AND deletedAt IS NULL") suspend fun tombstoneTableRow(row: String, table: String, scope: String, expected: Long, next: Long, at: String): Int
    @androidx.room.Query("UPDATE table_row SET deletedAt=NULL, version=:next WHERE rowId=:row AND tableId=:table AND scopeId=:scope AND version=:expected AND deletedAt IS NOT NULL") suspend fun restoreTableRow(row: String, table: String, scope: String, expected: Long, next: Long): Int
    @androidx.room.Query("SELECT * FROM money_adjustment WHERE scopeId=:scope AND obligationId=:id ORDER BY eventId") suspend fun adjustments(scope: String, id: String): List<MoneyAdjustmentRow>
    @androidx.room.Query("SELECT * FROM money_adjustment WHERE commandId=:id") suspend fun adjustmentForCommand(id: String): MoneyAdjustmentRow?
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertAdjustment(row: MoneyAdjustmentRow)
    @androidx.room.Query("SELECT * FROM money_event WHERE scopeId=:scope AND eventId=:id") suspend fun moneyEvent(scope: String, id: String): MoneyEventRow?
    @androidx.room.Query("SELECT * FROM local_calendar_event WHERE scopeId=:scope AND eventId=:id") suspend fun calendarEvent(scope: String, id: String): LocalCalendarEventRow?
    @androidx.room.Query("SELECT * FROM local_calendar_event WHERE scopeId=:scope AND startLocal>=:from AND startLocal<:until ORDER BY startLocal, eventId") suspend fun calendarBetween(scope: String, from: String, until: String): List<LocalCalendarEventRow>
    @androidx.room.Query("SELECT * FROM local_calendar_event WHERE scopeId=:scope AND timePrecision='date' AND startLocal>=substr(:from,1,10) AND startLocal<substr(:until,1,10) ORDER BY startLocal, eventId") suspend fun allDayCalendarBetween(scope: String, from: String, until: String): List<LocalCalendarEventRow>
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT) suspend fun insertCalendarEvent(row: LocalCalendarEventRow)
    @androidx.room.Query("UPDATE local_calendar_event SET title=:title, startLocal=:start, endLocal=:end, zoneId=:zone, timePrecision=:precision, version=:next WHERE scopeId=:scope AND eventId=:id AND version=:expected") suspend fun updateCalendarEvent(scope: String, id: String, expected: Long, next: Long, title: String, start: String, end: String?, zone: String, precision: String): Int
}

@Database(entities = [AgentCommandJournalRow::class, MemoryDraftRow::class, MemorySourceRow::class,
    MemoryRevisionRow::class, MemoryTombstoneRow::class, TaskEventRow::class, DeletedTaskRow::class,
    PersonRow::class, MoneyEventRow::class, LegacyMigrationRow::class, AgentOutboxRow::class, AgentTurnJournalRow::class,
    DomainObjectRow::class, DomainRevisionRow::class, DomainLinkRow::class, UserTableRow::class, TableColumnRow::class,
    TableDataRow::class, MoneyAdjustmentRow::class, LocalCalendarEventRow::class, TableSchemaRevisionRow::class],
    version = 6, exportSchema = true)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun protectedStoreDao(): ProtectedStoreDao
    companion object {
        @Volatile private var instance: AgentDatabase? = null

        @Synchronized
        fun open(context: Context): AgentDatabase {
            instance?.let { return it }
            System.loadLibrary("sqlcipher")
            return openUsingProvider(context, DatabaseKeyProvider(context), DatabaseKeyProvider.DATABASE_NAME)
                .also { instance = it }
        }

        /** Isolated synthetic entry point so storage probes exercise the exact app startup recovery path. */
        internal fun openForSyntheticProbe(
            context: Context,
            provider: DatabaseKeyProvider,
            databaseName: String,
        ): AgentDatabase {
            require(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            require(databaseName != DatabaseKeyProvider.DATABASE_NAME)
            System.loadLibrary("sqlcipher")
            return openUsingProvider(context, provider, databaseName)
        }

        internal fun openWithKeyForSyntheticProbe(context: Context, databaseName: String, key: ByteArray): AgentDatabase {
            require(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            require(databaseName != DatabaseKeyProvider.DATABASE_NAME)
            System.loadLibrary("sqlcipher")
            return openWithKey(context, key, databaseName)
        }

        private fun openUsingProvider(
            context: Context,
            provider: DatabaseKeyProvider,
            databaseName: String,
        ): AgentDatabase {
            val primary = runCatching { provider.loadOrCreate() }
            val stagedResult = if (provider.hasDatabaseFile()) runCatching { provider.loadStagedRotation() } else null
            val staged = stagedResult?.getOrNull()

            if (provider.hasDatabaseFile() && staged != null) {
                val primaryDatabase = primary.getOrNull()?.let { key -> runCatching { openWithKey(context, key, databaseName) }.getOrNull() }
                if (primaryDatabase != null) {
                    staged.fill(0)
                    runCatching { provider.discardStagedRotation() }
                    return primaryDatabase
                }
                val recovered = runCatching { openWithKey(context, staged, databaseName) }.getOrElse { error ->
                    staged.fill(0)
                    throw DatabaseKeyUnavailableException("Neither current nor staged key can open the encrypted database", error)
                }
                try {
                    provider.promoteStagedRotation()
                } catch (error: Exception) {
                    recovered.close()
                    throw DatabaseKeyUnavailableException("Staged database key opened the database but could not be promoted", error)
                }
                return recovered
            }

            if (staged != null) staged.fill(0)
            val key = primary.getOrElse { throw it }
            return openWithKey(context, key, databaseName).also {
                if (stagedResult?.isFailure == true) runCatching { provider.discardStagedRotation() }
            }
        }

        /** Rotate SQLCipher's key only after a durable wrapped candidate exists. */
        @Synchronized
        fun rotateEncryptionKey(context: Context): AgentDatabase {
            val database = open(context)
            val provider = DatabaseKeyProvider(context)
            val oldKey = provider.loadOrCreate()
            val nextKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            var replacement: AgentDatabase? = null
            try {
                provider.stageRotation(nextKey)
                val cipherDatabase = database.openHelper.writableDatabase as? net.zetetic.database.sqlcipher.SQLiteDatabase
                    ?: error("SQLCipher database adapter unavailable")
                cipherDatabase.changePassword(nextKey)
                database.close()
                instance = null
                replacement = openWithKey(context, nextKey, DatabaseKeyProvider.DATABASE_NAME)
                instance = replacement
                provider.promoteStagedRotation()
                oldKey.fill(0)
                return replacement
            } catch (error: Exception) {
                oldKey.fill(0)
                if (replacement == null) {
                    runCatching { database.close() }
                    if (instance === database) instance = null
                    nextKey.fill(0)
                }
                throw DatabaseKeyUnavailableException(
                    if (replacement == null) "Database key rotation interrupted; startup will reconcile the staged key"
                    else "Database key rotated; startup can reconcile the staged key envelope",
                    error,
                )
            }
        }

        private fun openWithKey(context: Context, databaseKey: ByteArray, databaseName: String): AgentDatabase {
            var database: AgentDatabase? = null
            try {
                database = Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, databaseName)
                    .addMigrations(AGENT_DB_1_TO_2, AGENT_DB_2_TO_3, AGENT_DB_3_TO_4, AGENT_DB_4_TO_5, AGENT_DB_5_TO_6)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    // WAL remains disabled until P1 WAL/SHM privacy and recovery gates pass.
                    .openHelperFactory(SupportOpenHelperFactory(databaseKey, null, false))
                    .build()
                database.openHelper.writableDatabase
                return database
            } catch (error: Exception) {
                runCatching { database?.close() }
                databaseKey.fill(0)
                throw error
            }
        }
    }

}
