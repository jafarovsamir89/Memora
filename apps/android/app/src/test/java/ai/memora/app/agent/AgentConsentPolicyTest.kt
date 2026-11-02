package ai.memora.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentConsentPolicyTest {
    @Test fun voiceAndTextUseTheirOwnCloudConsent() = runBlocking {
        val policy = ConsentAgentRuntimePolicy(
            cloudConsent = { it == AgentInputChannel.VOICE },
            memoryConsent = { true },
        )

        assertEquals(PolicyAction.ALLOW, policy.beforeModelSend(turn(AgentInputChannel.VOICE), false).action)
        val textDenied = policy.beforeModelSend(turn(AgentInputChannel.TEXT), false)
        assertEquals(PolicyAction.DENY, textDenied.action)
        assertEquals("cloud_consent_required", textDenied.code)
    }

    @Test fun revokedMemoryConsentBlocksHistoryToolAndPreparedCommit() = runBlocking {
        var memoryAllowed = true
        val policy = ConsentAgentRuntimePolicy(cloudConsent = { true }, memoryConsent = { memoryAllowed })
        val turn = turn(AgentInputChannel.TEXT, listOf(AgentDialogueMessage("user", "synthetic context")))
        val descriptor = AgentToolDescriptor("save_note", 1, ToolAccess.PERSISTED_MEMORY, ToolEffect.WRITE, true)
        val command = PreparedAgentCommand("00000000-0000-0000-0000-000000000001", "0".repeat(64), emptyMap(), false, Unit)

        assertEquals(PolicyAction.ALLOW, policy.beforeModelSend(turn, includesStoredMemory = true).action)
        assertEquals(PolicyAction.ALLOW, policy.authorizeTool(turn, descriptor).action)
        memoryAllowed = false
        val sendDenied = policy.beforeModelSend(turn, includesStoredMemory = true)
        assertEquals(PolicyAction.DENY, sendDenied.action)
        assertEquals("memory_consent_required", sendDenied.code)
        assertEquals(PolicyAction.DENY, policy.authorizeTool(turn, descriptor).action)
        val commitDenied = policy.authorizeCommit(turn, command)
        assertEquals(PolicyAction.DENY, commitDenied.action)
        assertEquals("memory_consent_required", commitDenied.code)
    }

    private fun turn(channel: AgentInputChannel, history: List<AgentDialogueMessage> = emptyList()) = AgentTurnRequest(
        turnId = "00000000-0000-0000-0000-000000000002",
        scopeId = "00000000-0000-0000-0000-000000000003",
        actorId = "00000000-0000-0000-0000-000000000004",
        sourceRevisionId = null,
        locale = "az-AZ",
        text = "synthetic request",
        channel = channel,
        conversationHistory = history,
    )
}
