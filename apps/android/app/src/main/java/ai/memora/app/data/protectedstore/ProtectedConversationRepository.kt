package ai.memora.app.data.protectedstore

import androidx.room.withTransaction

/** A single owner-scoped conversation shared by voice and text, stored only in the encrypted Room database. */
internal class ProtectedConversationRepository(private val db: AgentDatabase) {
    private val dao = db.protectedStoreDao()

    suspend fun recent(scopeId: String, limit: Int = 80): List<AgentConversationMessageRow> {
        require(scopeId.isNotBlank() && limit in 1..240)
        return dao.recentConversationMessages(scopeId, limit).asReversed()
    }

    suspend fun recentContext(scopeId: String, limit: Int = 12): List<AgentConversationMessageRow> =
        recent(scopeId, limit)

    suspend fun appendTurn(scopeId: String, turnId: String, userMessage: String, assistantMessage: String,
        createdAtMillis: Long = System.currentTimeMillis()) {
        require(scopeId.isNotBlank() && turnId.isNotBlank() && turnId.length <= 100)
        require(userMessage.isNotBlank() && userMessage.length <= 20_000)
        require(assistantMessage.isNotBlank() && assistantMessage.length <= 20_000)
        db.withTransaction {
            val existing = dao.conversationMessagesForTurn(scopeId, turnId)
            if (existing.isNotEmpty()) {
                check(existing.map { it.role to it.message } == listOf("user" to userMessage, "model" to assistantMessage))
                return@withTransaction
            }
            dao.putConversationMessages(listOf(
                AgentConversationMessageRow(scopeId = scopeId, turnId = turnId, role = "user", message = userMessage,
                    createdAtMillis = createdAtMillis),
                AgentConversationMessageRow(scopeId = scopeId, turnId = turnId, role = "model", message = assistantMessage,
                    createdAtMillis = createdAtMillis),
            ))
            dao.trimConversation(scopeId, 240)
        }
    }

    suspend fun clear(scopeId: String) {
        require(scopeId.isNotBlank())
        dao.clearConversation(scopeId)
    }
}
