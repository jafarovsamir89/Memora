package ai.memora.app.voice

import ai.memora.app.data.VoiceMoneyKey
import ai.memora.app.data.VoiceMoneyStore
import java.io.File

/** Keeps the proven receipt/debt path while routing the rest of the user's requests to the agent. */
internal class MemoraVoiceTurnProcessor(context: android.content.Context) : VoiceTurnProcessor {
    private val legacyFinance = VoiceFirstAssistant(VoiceMoneyStore(File(context.filesDir, "voice-memory.bin"), VoiceMoneyKey::get),
        GeminiSpokenIntentParser())
    private val agent = AgentVoiceTurnProcessor(context)
    override suspend fun handle(transcript: String): VoiceFirstResult =
        if (FINANCE_MARKERS.containsMatchIn(transcript)) legacyFinance.handle(transcript) else agent.handle(transcript)

    private companion object {
        val FINANCE_MARKERS = Regex("(?iu)(?:\\b(?:manat|azn|borc|borclu|pul|verdi|verib|vermisdi|vermişdi|ödədi|odedi|qaytardi|qaytardı|дал|дала|долг|должен|манат|рубл|евро|деньг)\\b)")
    }
}
