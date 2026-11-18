package ai.memora.app.acceptance

import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.UiAgentDatabaseRule
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import android.content.Context
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyntheticReliabilityProfileTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()
    private val fixtureDirectories = mutableListOf<File>()

    @After fun removeSyntheticFiles() {
        fixtureDirectories.forEach { directory ->
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }

    @Test fun fullProfileSeedsKnownIdsLinksAndExactFinanceInvariants() = runBlocking {
        val snapshot = createFixture().seed()
        val ids = SyntheticReliabilityIds

        assertEquals(ids.profile, snapshot.profileId)
        assertEquals(ids.scope, snapshot.scopeId)
        assertEquals(listOf(
            ids.personElchin to "Elçin Məmmədov",
            ids.personElchinLookalike to "Elçin Məmmədov",
            ids.personRuslan to "Ruslan Əliyev",
            ids.personLeyla to "Leyla Həsənova",
        ), snapshot.canonicalPeople)
        assertEquals(listOf(
            Triple(ids.aliasElchinBmw, "Elçin BMW ustası", ids.personElchin),
            Triple(ids.aliasElchinService, "Elçin servis", ids.personElchin),
            Triple(ids.aliasRuslan, "Ruslan", ids.personRuslan),
        ), snapshot.aliases)
        assertEquals(listOf("Elçin servis ustası" to ids.personElchin, "R. Əliyev" to ids.personRuslan),
            snapshot.unconfirmedAliasSuggestionsNotPersisted)
        assertEquals(ids.financePerson to ids.personElchin, snapshot.financeIdentity)
        assertEquals(listOf(ids.projectBmw, ids.projectOther), snapshot.projects)
        assertEquals(listOf(ids.noteSource, ids.noteOther), snapshot.notes)
        assertEquals(listOf(Triple(ids.projectNoteLink, ids.projectBmw, ids.noteSource),
            Triple(ids.projectOtherLink, ids.projectOther, ids.noteOther)), snapshot.domainLinks)
        assertEquals(listOf(ids.caseBmw), snapshot.caseIds)
        assertEquals(listOf(ids.caseBmw to ids.personElchin), snapshot.caseParticipants)
        assertEquals(listOf("domain_object" to ids.projectBmw, "money_event" to ids.financeEvent,
            "money_event" to ids.financeReceiptElchin), snapshot.caseEvidence)
        assertEquals(listOf(
            SyntheticFinanceInvariant(ids.financeEvent, ids.financePerson, "debt", 30_000, "AZN"),
            SyntheticFinanceInvariant(ids.financeReceiptElchin, ids.financePerson, "receipt", 12_000, "AZN"),
            SyntheticFinanceInvariant(ids.financeReceiptRuslan, ids.financePersonRuslan, "receipt", 15_000, "AZN"),
        ), snapshot.finance)
        assertEquals(listOf(ids.financeEvent to 18_000L), snapshot.debtBalances)
        assertTrue(snapshot.financeAdjustments.any { it.second == "settlement" && it.third == 10_000L })
        assertTrue(snapshot.financeAdjustments.any { it.second == "amount_correction" && it.third == 2_000L })
        assertTrue(snapshot.financeAdjustments.any { it.second == "correction" && it.third == 2_000L })
        assertEquals(listOf(ids.roomTask to "2026-11-20T10:00", ids.roomTaskPast to "2026-11-17T10:00"), snapshot.roomTasks)
        assertEquals(listOf(ids.roomTask to ids.personElchin), snapshot.taskPersonLinks)
        assertEquals(listOf(ids.calendarEvent to "2026-11-21T11:30", ids.calendarPast to "2026-11-17T09:00"),
            snapshot.calendarEvents)
        assertEquals(listOf(ids.calendarEvent to ids.personElchin), snapshot.calendarPersonLinks)
        assertEquals(listOf(ids.conversationTurn, ids.conversationTurnRu, ids.conversationTurnMixed), snapshot.conversationTurns)
        assertEquals(listOf(
            "Elçin BMW təmiri üçün mənə 300 manat borcludur. Mən azərbaycanca cavabları üstün tuturam.",
            "Он сегодня дал 100 манат за ремонт BMW.",
            "Elçin indi 120 verdi, BMW təmiri üçün.",
        ), snapshot.userUtterances)
        assertEquals(1, snapshot.episodes.size)
        assertEquals(listOf(
            Triple(ids.factAzerbaijaniPreference, "preference", "Prefers replies in Azerbaijani."),
            Triple(ids.factBmwContact, "context", "Elçin is the confirmed BMW repair contact."),
        ), snapshot.profileFacts)
        assertEquals(listOf(ids.inboxSource to "Synthetic inbox source about BMW service",
            ids.inboxSourceRu to "Synthetic RU inbox source about repair"), snapshot.inboxSources)
        assertEquals(listOf(ids.legacyTask to "2026-11-19T10:00", ids.legacyTaskPast to "2026-11-17T10:00"),
            snapshot.legacyTasks)
        assertEquals(listOf(ids.editableTableRow to "{\"status\":\"waiting for parts\",\"title\":\"BMW service\"}"),
            snapshot.editableTableRows)
        val unconfirmedTexts = snapshot.unconfirmedAliasSuggestionsNotPersisted.map { it.first }
        assertTrue(snapshot.aliases.none { it.second in unconfirmedTexts })
        assertEquals(listOf(ids.personForeign), ProtectedDomainRepository(databaseRule.database)
            .domainObjects(ids.foreignScope, DomainKind.PERSON, 10).map { it.objectId })
    }

    @Test fun sameNamePeopleRemainDistinctAndFinanceUsesOnlyOwnerConfirmedId() = runBlocking {
        createFixture().seed()
        val ids = SyntheticReliabilityIds
        val choices = requireNotNull(ProtectedCaseRepository(databaseRule.database)
            .financePersonIdentityChoices(ids.scope, ids.financePerson))

        val matchingSameNamePeople = ProtectedDomainRepository(databaseRule.database)
            .domainObjects(ids.scope, DomainKind.PERSON, 10)
            .filter { it.title == "Elçin Məmmədov" }.map { it.objectId }.sorted()
        assertEquals(listOf(ids.personElchin, ids.personElchinLookalike), matchingSameNamePeople)
        assertEquals(listOf(ids.personElchin), choices.candidates.map { it.objectId })
        assertEquals(ids.personElchin, choices.confirmedLink?.domainPersonId)
        assertTrue(choices.candidates.none { it.objectId == ids.personElchinLookalike })
    }

    @Test fun independentDatabasesProduceTheSameSyntheticSnapshot() = runBlocking {
        val first = createFixture().seed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secondDatabase = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val second = createFixture(secondDatabase).seed()
            assertEquals(first, second)
        } finally {
            secondDatabase.close()
        }
    }

    @Test fun fixtureRefusesToSeedAnAlreadyPopulatedDatabase() = runBlocking {
        val fixture = createFixture()
        fixture.seed()
        try {
            fixture.seed()
            throw AssertionError("Expected the fixture to reject an already populated database")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("non-empty Room database"))
        }
    }

    private fun createFixture(database: AgentDatabase = databaseRule.database): SyntheticReliabilityProfile {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "memora-acceptance-synthetic-").toFile()
        fixtureDirectories += directory
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return SyntheticReliabilityProfile(database, directory, key)
    }
}
