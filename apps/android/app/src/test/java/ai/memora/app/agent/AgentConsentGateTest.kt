package ai.memora.app.agent

import org.junit.Assert.*
import org.junit.Test

class AgentConsentGateTest {
    private val scope = "00000000-0000-4000-8000-000000000001"
    private val voice = ConsentRecord("00000000-0000-4000-8000-000000000002", scope, AgentProvider.GEMINI,
        ConsentCategory.VOICE_OR_TEXT_TURN, 1, 1, ConsentStatus.GRANTED)
    private val memory = ConsentRecord("00000000-0000-4000-8000-000000000003", scope, AgentProvider.GEMINI,
        ConsentCategory.PERSISTED_MEMORY, 1, 3, ConsentStatus.GRANTED)
    private val context = ConsentContext(scope, AgentProvider.GEMINI, CloudPayloadKind.MEMORY_EXCERPT, "a".repeat(64), 1, 1, listOf(voice, memory))

    @Test fun voiceConsentDoesNotAuthorizeStoredMemory() {
        assertEquals(ConsentDecision.MissingOrRevoked, AgentConsentGate.prepare(context.copy(records = listOf(voice))))
        assertTrue(AgentConsentGate.prepare(context.copy(kind = CloudPayloadKind.CURRENT_TURN, records = listOf(voice))) is ConsentDecision.Allowed)
        assertTrue(AgentConsentGate.prepare(context) is ConsentDecision.Allowed)
    }

    @Test fun revocationBeforeSendOrConsentRevisionBlocksOldTicket() {
        val ticket = (AgentConsentGate.prepare(context) as ConsentDecision.Allowed).ticket
        assertEquals(ConsentDecision.MissingOrRevoked, AgentConsentGate.recheck(ticket, context.copy(records = listOf(voice, memory.copy(status = ConsentStatus.REVOKED, revision = 4)))))
        assertEquals(ConsentDecision.Changed, AgentConsentGate.recheck(ticket, context.copy(records = listOf(voice, memory.copy(revision = 5)))))
        assertEquals(ConsentDecision.Changed, AgentConsentGate.recheck(ticket, context.copy(sessionGeneration = 2)))
        assertEquals(ConsentDecision.Changed, AgentConsentGate.recheck(ticket, context.copy(payloadDigest = "b".repeat(64))))
    }

    @Test fun mismatchedScopeAndPolicyNeverAuthorize() {
        assertEquals(ConsentDecision.MissingOrRevoked, AgentConsentGate.prepare(context.copy(records = listOf(voice, memory.copy(scopeId = voice.id)))))
        assertEquals(ConsentDecision.MissingOrRevoked, AgentConsentGate.prepare(context.copy(requiredPolicyVersion = 2)))
        assertEquals(ConsentDecision.Invalid, AgentConsentGate.prepare(context.copy(payloadDigest = "not-a-digest")))
        assertEquals(ConsentDecision.Invalid, AgentConsentGate.prepare(context.copy(records = listOf(voice, voice))))
        assertEquals(ConsentDecision.Invalid, AgentConsentGate.prepare(context.copy(sessionGeneration = -1)))
    }

    @Test fun recordOrderIsNotAuthorityAndMissingVoiceDenies() {
        val ticket = (AgentConsentGate.prepare(context) as ConsentDecision.Allowed).ticket
        assertTrue(AgentConsentGate.recheck(ticket, context.copy(records = listOf(memory, voice))) is ConsentDecision.Allowed)
        assertEquals(ConsentDecision.MissingOrRevoked, AgentConsentGate.prepare(context.copy(records = listOf(memory))))
    }
}
