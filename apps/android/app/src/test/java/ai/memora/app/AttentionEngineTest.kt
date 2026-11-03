package ai.memora.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.AGENT_DB_12_TO_13
import ai.memora.app.data.protectedstore.AGENT_DB_13_TO_14
import ai.memora.app.data.protectedstore.ConversationEpisodeRow
import ai.memora.app.data.protectedstore.ConversationEpisodeSourceRow
import ai.memora.app.data.protectedstore.ConversationEpisodeRevisionRow
import ai.memora.app.data.protectedstore.AttentionSettingsRepository
import ai.memora.app.data.protectedstore.AttentionSettingsRow
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.LocalCalendarEventRow
import ai.memora.app.domain.Task
import ai.memora.app.reminders.AttentionEngine
import ai.memora.app.reminders.AttentionKind
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AttentionEngineTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun attentionUsesOnlyScopedOpenRecordsAndRanksRealDeadlines() {
        val now = Instant.parse("2026-11-03T00:00:00Z").toEpochMilli()
        val scope = "owner-scope"
        val tasks = listOf(
            Task("overdue", scope, "Synthetic overdue", false, 2, "2026-11-02T18:00", "Asia/Baku"),
            Task("future", scope, "Synthetic later", false, 1, "2026-11-05T11:00", "Asia/Baku"),
            Task("foreign", "other-scope", "Foreign task", false, 1, "2026-11-03T01:00", "Asia/Baku"),
            Task("done", scope, "Completed task", true, 2, "2026-11-03T01:00", "Asia/Baku"),
            Task("undated", scope, "No invented date", false, 1),
        )
        val cases = listOf(
            case("case", scope, "Open case", "Review saved next action"),
            case("closed", scope, "Closed case", "Should not appear", status = "completed"),
            case("foreign-case", "other-scope", "Foreign case", "Must stay private"),
        )
        val events = listOf(
            calendar("soon", scope, "Calendar source", "2026-11-03T05:00", "minute"),
            calendar("inactive", scope, "Cancelled event", "2026-11-03T05:30", "minute", status = "cancelled"),
            calendar("foreign-event", "other-scope", "Foreign event", "2026-11-03T05:30", "minute"),
        )

        val result = AttentionEngine.suggest(scope, tasks, cases, events, now, ZoneId.of("Asia/Baku"), limit = 3)

        assertEquals(listOf("overdue", "soon", "future"), result.map { it.sourceId })
        assertEquals(AttentionKind.TASK, result.first().kind)
        assertEquals(0, result.first().priority)
        assertFalse(result.any { it.sourceId == "foreign" || it.sourceId.startsWith("foreign-") || it.sourceId == "closed" })
        val withCase = AttentionEngine.suggest(scope, tasks.filter { it.id == "undated" }, cases.take(1), emptyList(),
            now, ZoneId.of("Asia/Baku"))
        assertEquals(listOf(AttentionKind.CASE), withCase.map { it.kind })
        assertNull(withCase.single().dueAtMillis) // A case without a stored due date remains unscheduled.
    }

    @Test fun invalidDstSourceTimesAreOmittedAndQuietHoursHandleDstAndMidnight() {
        val now = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli()
        val scope = "dst-scope"
        val gapTask = Task("gap", scope, "Invalid local task time", false, 1,
            "2026-03-29T02:30", "Europe/Berlin")
        val ambiguousEvent = calendar("ambiguous", scope, "Ambiguous event", "2026-10-25T02:30", "minute", zone = "Europe/Berlin")
        assertTrue(AttentionEngine.suggest(scope, listOf(gapTask), emptyList(), listOf(ambiguousEvent), now,
            ZoneId.of("Europe/Berlin")).isEmpty())

        val dailyAtGap = AttentionEngine.nextTriggerMillis(now, LocalTime.of(2, 30), 0, 0, ZoneId.of("Europe/Berlin"))
        assertEquals(Instant.parse("2026-03-29T01:00:00Z").toEpochMilli(), dailyAtGap) // 02:30 is skipped; 03:00 is first valid time.

        val afterQuietStart = Instant.parse("2026-11-03T18:30:00Z").toEpochMilli() // 22:30 in Baku
        val quietEnd = AttentionEngine.nextTriggerMillis(afterQuietStart, LocalTime.of(22, 0), 22 * 60, 8 * 60,
            ZoneId.of("Asia/Baku"))
        assertEquals(Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(), quietEnd)
        val delayedBriefing = AttentionEngine.nextTriggerMillis(afterQuietStart, LocalTime.of(21, 50), 22 * 60, 8 * 60,
            ZoneId.of("Asia/Baku"), lastNotificationDate = java.time.LocalDate.parse("2026-11-02"))
        assertEquals(Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(), delayedBriefing)
        val alreadyDelivered = AttentionEngine.nextTriggerMillis(afterQuietStart, LocalTime.of(21, 50), 22 * 60, 8 * 60,
            ZoneId.of("Asia/Baku"), lastNotificationDate = java.time.LocalDate.parse("2026-11-03"))
        assertEquals(Instant.parse("2026-11-04T17:50:00Z").toEpochMilli(), alreadyDelivered)
        assertTrue(AttentionEngine.isWithinQuietHours(LocalTime.of(23, 59), 22 * 60, 8 * 60))
        assertFalse(AttentionEngine.isWithinQuietHours(LocalTime.of(12, 0), 22 * 60, 8 * 60))
    }

    @Test fun allDayCalendarItemRemainsRelevantAfterItsMidnightStart() {
        val now = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
        val item = calendar("all-day", "day-scope", "All-day item", "2026-11-03", "date", zone = "Asia/Baku")
        val result = AttentionEngine.suggest("day-scope", emptyList(), emptyList(), listOf(item), now, ZoneId.of("Asia/Baku"))
        assertEquals(1, result.size)
        assertEquals("all-day", result.single().sourceId)
        assertEquals(1, result.single().priority)
        assertEquals("2026-11-03", result.single().sourceLocalTime)
    }

    @Test fun snoozeKeepsItsOneHourTargetAcrossMidnightAndQuietHours() {
        val zone = ZoneId.of("Asia/Baku")
        val afterMidnightCandidate = Instant.parse("2026-11-03T20:30:00Z").toEpochMilli() // 00:30 local after snooze crossed midnight.
        assertEquals(Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(),
            AttentionEngine.nextAllowedMillis(afterMidnightCandidate, 22 * 60, 8 * 60, zone))

        val beforeQuietCandidate = Instant.parse("2026-11-03T18:30:00Z").toEpochMilli() // 22:30 local.
        assertEquals(Instant.parse("2026-11-04T04:00:00Z").toEpochMilli(),
            AttentionEngine.nextAllowedMillis(beforeQuietCandidate, 22 * 60, 8 * 60, zone))

        assertEquals(afterMidnightCandidate,
            AttentionEngine.nextAllowedMillis(afterMidnightCandidate, 0, 0, zone))
    }

    @Test fun scopedPreferencesDeliveryDedupDismissAndOneHourSnoozePersist() = runBlocking {
        val db = databaseRule.database
        val repo = AttentionSettingsRepository(db)
        val scope = "attention-scope"
        val now = Instant.parse("2026-11-03T09:00:00Z").toEpochMilli()
        val initial = repo.savePreferences(scope, enabled = true, briefingTimeLocal = "09:15",
            quietStartMinute = 22 * 60, quietEndMinute = 8 * 60, nowMillis = now)
        assertTrue(initial.enabled)
        assertNull(repo.settings("another-scope"))
        assertTrue(repo.recordNotificationDate(scope, "2026-11-03", now))
        assertFalse(repo.recordNotificationDate(scope, "2026-11-03", now + 1))
        assertTrue(repo.snoozeUntil(scope, "2026-11-03", now + 60 * 60 * 1000, now))
        assertFalse(repo.recordNotificationDate(scope, "2026-11-03", now + 60 * 60 * 1000 - 1, snoozeReplay = true))
        assertTrue(repo.recordNotificationDate(scope, "2026-11-03", now + 60 * 60 * 1000, snoozeReplay = true))
        assertFalse(repo.snoozeUntil(scope, "2026-11-02", now + 2 * 60 * 60 * 1000, now + 60 * 60 * 1000))
        assertTrue(repo.snoozeUntil(scope, "2026-11-03", now + 25 * 60 * 60 * 1000, now + 60 * 60 * 1000))
        assertTrue(repo.recordNotificationDate(scope, "2026-11-04", now + 25 * 60 * 60 * 1000, snoozeReplay = true))
        assertFalse(repo.recordNotificationDate(scope, "2026-11-04", now + 26 * 60 * 60 * 1000))
        repo.dismissForDate(scope, "2026-11-05", now + 48 * 60 * 60 * 1000)
        assertFalse(repo.recordNotificationDate(scope, "2026-11-05", now + 49 * 60 * 60 * 1000))
        assertEquals("09:15", repo.settings(scope)?.briefingTimeLocal)
    }

    @Test fun versionTwelveMigrationPreservesExistingRowsAndAddsOnlyAttentionSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "attention-migration-${UUID.randomUUID()}"
        val marker = "existing-protected-row"
        val v12 = Room.databaseBuilder(context, AttentionMigrationV12::class.java, name).allowMainThreadQueries().build()
        try { runBlocking { v12.markerDao().insert(MigrationMarker(marker)) } } finally { v12.close() }

        val v13 = Room.databaseBuilder(context, AttentionMigrationV13::class.java, name)
            .addMigrations(AGENT_DB_12_TO_13).allowMainThreadQueries().build()
        val settings = AttentionSettingsRow("synthetic-attention-scope", true, "09:15", 22 * 60, 8 * 60,
            "2026-11-03", null, null, null, Instant.parse("2026-11-03T00:00:00Z").toEpochMilli())
        try {
            runBlocking {
                assertEquals(marker, v13.markerDao().read().single().value)
                assertTrue(v13.attentionDao().read().isEmpty())
                assertNull(v13.attentionDao().settings("no-default-row"))
                v13.attentionDao().insert(settings)
            }
        } finally {
            v13.close()
        }
        val reopened = Room.databaseBuilder(context, AttentionMigrationV13::class.java, name).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals(marker, reopened.markerDao().read().single().value)
                assertEquals(settings, reopened.attentionDao().settings("synthetic-attention-scope"))
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionThirteenMigrationPreservesExistingRowsAndAddsEpisodeTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "episode-migration-${UUID.randomUUID()}"
        val marker = "existing-row-before-episode-migration"
        val v13 = Room.databaseBuilder(context, AttentionMigrationV13::class.java, name).allowMainThreadQueries().build()
        try { runBlocking { v13.markerDao().insert(MigrationMarker(marker)) } } finally { v13.close() }

        val v14 = Room.databaseBuilder(context, EpisodeMigrationV14::class.java, name)
            .addMigrations(AGENT_DB_13_TO_14).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals(marker, v14.markerDao().read().single().value)
                assertTrue(v14.episodeDao().episodes().isEmpty())
                val episode = ConversationEpisodeRow("synthetic-episode-id", "synthetic-scope", "Synthetic title",
                    "Synthetic summary", 1, 1, 1)
                v14.episodeDao().insert(episode)
                v14.episodeDao().insertSource(ConversationEpisodeSourceRow("synthetic-scope", episode.episodeId, "synthetic-turn"))
                val revision = ConversationEpisodeRevisionRow("synthetic-scope", episode.episodeId, 1,
                    episode.title, episode.summary, episode.updatedAtMillis)
                v14.episodeDao().insertRevision(revision)
                assertEquals(episode, v14.episodeDao().episodes().single())
                assertEquals(listOf("synthetic-turn"), v14.episodeDao().sourceTurns(episode.episodeId))
                assertEquals(listOf(revision), v14.episodeDao().revisions(episode.episodeId))
            }
        } finally {
            v14.close()
            context.deleteDatabase(name)
        }
    }

    private fun case(id: String, scope: String, title: String, nextAction: String?, status: String = "open") =
        CaseRecordRow(id, scope, title, null, status, nextAction, 1, "source-$id",
            "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", null)

    private fun calendar(id: String, scope: String, title: String, local: String, precision: String,
        status: String = "active", zone: String = "Asia/Baku") =
        LocalCalendarEventRow(id, scope, title, local, null, zone, precision, 1, status)
}

@Entity(tableName = "migration_marker")
internal data class MigrationMarker(@PrimaryKey val value: String)

@Dao
internal interface MigrationMarkerDao {
    @Insert suspend fun insert(row: MigrationMarker)
    @Query("SELECT * FROM migration_marker") suspend fun read(): List<MigrationMarker>
}

@Database(entities = [MigrationMarker::class], version = 12, exportSchema = false)
internal abstract class AttentionMigrationV12 : RoomDatabase() { abstract fun markerDao(): MigrationMarkerDao }

@Dao
internal interface AttentionMigrationDao {
    @Query("SELECT * FROM migration_marker") suspend fun markers(): List<MigrationMarker>
    @Insert suspend fun insert(row: AttentionSettingsRow)
    @Query("SELECT * FROM attention_settings") suspend fun read(): List<AttentionSettingsRow>
    @Query("SELECT * FROM attention_settings WHERE scopeId=:scope") suspend fun settings(scope: String): AttentionSettingsRow?
}

@Database(entities = [MigrationMarker::class, AttentionSettingsRow::class], version = 13, exportSchema = false)
internal abstract class AttentionMigrationV13 : RoomDatabase() {
    abstract fun markerDao(): MigrationMarkerDao
    abstract fun attentionDao(): AttentionMigrationDao
}

@Dao
internal interface EpisodeMigrationDao {
    @Query("SELECT * FROM conversation_episode") suspend fun episodes(): List<ConversationEpisodeRow>
    @Insert suspend fun insert(row: ConversationEpisodeRow)
    @Insert suspend fun insertSource(row: ConversationEpisodeSourceRow)
    @Query("SELECT turnId FROM conversation_episode_source WHERE episodeId=:episodeId") suspend fun sourceTurns(episodeId: String): List<String>
    @Insert suspend fun insertRevision(row: ConversationEpisodeRevisionRow)
    @Query("SELECT * FROM conversation_episode_revision WHERE episodeId=:episodeId") suspend fun revisions(episodeId: String): List<ConversationEpisodeRevisionRow>
}

@Database(entities = [MigrationMarker::class, AttentionSettingsRow::class, ConversationEpisodeRow::class,
    ConversationEpisodeSourceRow::class, ConversationEpisodeRevisionRow::class], version = 14, exportSchema = false)
internal abstract class EpisodeMigrationV14 : RoomDatabase() {
    abstract fun markerDao(): MigrationMarkerDao
    abstract fun episodeDao(): EpisodeMigrationDao
}
