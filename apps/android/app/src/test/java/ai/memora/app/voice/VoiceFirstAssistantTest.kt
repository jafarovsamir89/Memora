package ai.memora.app.voice

import ai.memora.app.data.VoiceMoneyStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceFirstAssistantTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clock = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC)

    @Test fun statementThenQuestionUsesPersistedEvidenceAndDoesNotInventATime() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "memory.bin")) { key }
        val parser = object : SpokenIntentParser {
            override suspend fun parse(transcript: String) = if (transcript.contains("nə qədər"))
                SpokenIntent.ReceivedQuestion("Samir") else SpokenIntent.Received("Samir", "100")
        }
        val assistant = VoiceFirstAssistant(store, parser, clock)
        val saved = assistant.handle("Samir mənə 100 manat verdi.") as VoiceFirstResult.Ready
        assertTrue(saved.saved); assertEquals("Hər şey hazırdır.", saved.spoken)
        val answer = assistant.handle("Samir mənə nə qədər pul vermişdi?") as VoiceFirstResult.Ready
        assertFalse(answer.saved); assertEquals("Samir sizə 100 manat verib.", answer.spoken)
        assertFalse(answer.spoken.contains("bu gün"))
    }

    @Test fun ungroundedModelProposalOrStorageFailureNeverClaimsSuccess() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "memory.bin")) { key }
        val invalid = VoiceFirstAssistant(store, object : SpokenIntentParser {
            override suspend fun parse(transcript: String) = SpokenIntent.Received("Samir", "100")
        }, clock)
        assertFalse((invalid.handle("Samir mənə nə qədər pul verdi?") as VoiceFirstResult.Ready).saved)
        assertFalse((invalid.handle("Samir mənə 50 manat verdi.") as VoiceFirstResult.Ready).saved)
        val noKey = VoiceFirstAssistant(VoiceMoneyStore(File(folder.root, "denied.bin")) { null }, object : SpokenIntentParser {
            override suspend fun parse(transcript: String) = SpokenIntent.Received("Samir", "100")
        }, clock)
        assertEquals(VoiceFirstResult.Unavailable, noKey.handle("Samir mənə 100 manat verdi."))
    }

    @Test fun timeQualifiedQuestionAndCompoundClaimAreNotMistakenForSimpleFacts() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "scope.bin")) { key }
        val parser = object : SpokenIntentParser {
            override suspend fun parse(transcript: String) = if (transcript.contains("?"))
                SpokenIntent.ReceivedQuestion("Samir") else SpokenIntent.Received("Samir", "100")
        }
        val assistant = VoiceFirstAssistant(store, parser, clock)
        assertTrue((assistant.handle("Samir mənə 100 manat verdi.") as VoiceFirstResult.Ready).saved)
        val qualified = assistant.handle("Samir dünən mənə nə qədər pul vermişdi?") as VoiceFirstResult.Ready
        assertFalse(qualified.spoken.contains("100"))
        val complex = assistant.handle("Samir mənə 100 manat verdi, sonra qaytardım.") as VoiceFirstResult.Ready
        assertFalse(complex.saved)
    }

    @Test fun russianStatementAndQuestionUseTheSameStoredEvidence() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "ru.bin")) { key }
        val parser = object : SpokenIntentParser {
            override suspend fun parse(transcript: String) = if (transcript.contains("?"))
                SpokenIntent.ReceivedQuestion("Samir") else SpokenIntent.Received("Samir", "100")
        }
        val assistant = VoiceFirstAssistant(store, parser, clock)
        assertEquals("Всё готово.", (assistant.handle("Samir дал мне 100 манат.") as VoiceFirstResult.Ready).spoken)
        assertEquals("Samir передал вам 100 манат.", (assistant.handle("Samir сколько мне дал?") as VoiceFirstResult.Ready).spoken)
    }

    @Test fun debtQuestionUsesOnlyExplicitDebtAndSkipsRemoteClassification() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "debt.bin")) { key }
        val parser = object : SpokenIntentParser { override suspend fun parse(transcript: String): SpokenIntent = error("unexpected remote call") }
        val assistant = VoiceFirstAssistant(store, parser, clock)
        val missing = assistant.handle("Salam, Samirin mene negede borcu var?") as VoiceFirstResult.Ready
        assertTrue(missing.spoken.contains("məlumat yoxdur"))
        assertTrue((assistant.handle("Samirin mənə yüz manat borcu var.") as VoiceFirstResult.Ready).saved)
        val answer = assistant.handle("Salam, Samirin mənə nə qədər borcu var?") as VoiceFirstResult.Ready
        assertTrue(answer.spoken.contains("100 manat")); assertTrue(answer.spoken.contains("borcludur"))
        val russian = assistant.handle("Сколько Samir мне должен?") as VoiceFirstResult.Ready
        assertTrue(russian.spoken.contains("100 манат"))
    }

    @Test fun receivedMoneyNeverBecomesDebtAndLaterReceiptMakesDebtUncertain() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "direction.bin")) { key }
        val parser = object : SpokenIntentParser { override suspend fun parse(transcript: String) = SpokenIntent.Received("Samir", "100") }
        val assistant = VoiceFirstAssistant(store, parser, clock)
        assertTrue((assistant.handle("Samir mənə 100 manat verdi.") as VoiceFirstResult.Ready).saved)
        assertTrue((assistant.handle("Samirin mənə nə qədər borcu var?") as VoiceFirstResult.Ready).spoken.contains("məlumat yoxdur"))
        assertTrue((assistant.handle("Samirin mənə 100 manat borcu var.") as VoiceFirstResult.Ready).saved)
        assertTrue((assistant.handle("Samirin mənə nə qədər borcu var?") as VoiceFirstResult.Ready).spoken.contains("dəqiqləşdirmək"))
    }

    @Test fun spokenHundredIsAcceptedWhenBothModelAndTranscriptAgree() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "spoken.bin")) { key }
        val parser = object : SpokenIntentParser { override suspend fun parse(transcript: String) = SpokenIntent.Received("Samir", "yüz") }
        assertTrue((VoiceFirstAssistant(store, parser, clock).handle("Samir mənə yüz manat verdi.") as VoiceFirstResult.Ready).saved)
        assertFalse((VoiceFirstAssistant(store, parser, clock).handle("Samir mənə iyirmi manat verdi.") as VoiceFirstResult.Ready).saved)
    }

    @Test fun debtNameSuffixAndAmbiguousMultipleDebtStatements() = runBlocking {
        val store = VoiceMoneyStore(File(folder.root, "multiple.bin")) { key }
        val parser = object : SpokenIntentParser { override suspend fun parse(transcript: String): SpokenIntent = error("unexpected call") }
        val assistant = VoiceFirstAssistant(store, parser, clock)
        assertTrue((assistant.handle("Lalənin mənə 100 manat borcu var.") as VoiceFirstResult.Ready).saved)
        assertTrue((assistant.handle("Lalənin mənə 20 manat borcu var.") as VoiceFirstResult.Ready).saved)
        val result = assistant.handle("Lalənin mənə nə qədər borcu var?") as VoiceFirstResult.Ready
        assertTrue(result.spoken.contains("dəqiqləşdirmək")); assertFalse(result.spoken.contains("120"))
    }
}
