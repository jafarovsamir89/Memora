package ai.memora.app.data.protectedstore

import ai.memora.app.UiAgentDatabaseRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersonAliasRepositoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun aliasesAreExactScopedAuditedAndReversible() = runBlocking {
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val aliases = PersonAliasRepository(db)
        domain.createObject("scope-a", "person-a", DomainKind.PERSON, "Samir Aliyev", null, null, "2026-11-03T10:00:00Z")
        domain.createObject("scope-a", "person-b", DomainKind.PERSON, "Other Person", null, null, "2026-11-03T10:00:00Z")
        domain.createObject("scope-b", "person-c", DomainKind.PERSON, "Other Person", null, null, "2026-11-03T10:00:00Z")

        val saved = aliases.add("scope-a", "person-a", "  SAMIR   Ustad ", sourceTurnId = "synthetic-turn", nowMillis = 10)
        assertEquals("SAMIR Ustad", saved.aliasText)
        assertEquals("samir ustad", saved.normalizedAlias)
        assertEquals(saved, aliases.add("scope-a", "person-a", "samir ustad", nowMillis = 11))
        assertTrue(runCatching { aliases.add("scope-a", "person-b", "Samir Ustad", nowMillis = 12) }.isFailure)
        assertTrue(runCatching { aliases.add("scope-a", "person-b", "Samir Aliyev", nowMillis = 13) }.isFailure)
        val otherScope = aliases.add("scope-b", "person-c", "Samir Ustad", nowMillis = 14)
        assertEquals("scope-b", otherScope.scopeId)

        val retired = aliases.retire("scope-a", saved.aliasId, saved.version, nowMillis = 20)
        assertEquals("retired", db.protectedStoreDao().personAliasRevisions("scope-a", saved.aliasId).last().action)
        assertEquals(FinanceAliasResolution.RetiredAlias, aliases.resolveFinanceAlias("scope-a", "Samir Ustad"))
        assertTrue(runCatching { aliases.add("scope-a", "person-b", "Samir Ustad", nowMillis = 21) }.isFailure)
        domain.createObject("scope-a", "person-canonical-collision", DomainKind.PERSON, "Samir Ustad", null, null,
            "2026-11-03T10:01:00Z")
        assertTrue(runCatching { aliases.restore("scope-a", retired.aliasId, retired.version, nowMillis = 22) }.isFailure)
        assertEquals(1, aliases.list("scope-a", "person-a").size)
    }

    @Test fun financeAliasUsesOnlyOwnerConfirmedIdentityAndNeverCreatesLedgerPerson() = runBlocking {
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val cases = ProtectedCaseRepository(db)
        val aliases = PersonAliasRepository(db)
        domain.createObject("scope", "person", DomainKind.PERSON, "Canonical Person", null, null, "2026-11-03T10:00:00Z")
        domain.recordFinanceEvent("scope", "Ledger Canonical", "receipt", 10_000, "AZN", "synthetic", null,
            "finance-person", "finance-event", "finance-command", "2026-11-03T10:01:00Z", "2026-11-03T10:01:00Z")
        aliases.add("scope", "person", "Samir Usta", nowMillis = 10)
        assertEquals(FinanceAliasResolution.OwnerLinkRequired, aliases.resolveFinanceAlias("scope", "SAMIR USTA"))
        assertEquals(1, db.protectedStoreDao().people("scope").size)

        cases.confirmFinancePersonIdentity("scope", "finance-person", "person", confirmedBy = "owner",
            now = "2026-11-03T10:02:00Z")
        val resolved = aliases.resolveFinanceAlias("scope", "  Samir   Usta ") as FinanceAliasResolution.Resolved
        assertEquals("finance-person", resolved.financePerson.personId)
        assertEquals("Canonical Person", resolved.domainPerson.title)
        aliases.retire("scope", db.protectedStoreDao().personAliases("scope", "person").single().aliasId, 1, nowMillis = 30)
        assertEquals(FinanceAliasResolution.RetiredAlias, aliases.resolveFinanceAlias("scope", "Samir Usta"))
    }
}
