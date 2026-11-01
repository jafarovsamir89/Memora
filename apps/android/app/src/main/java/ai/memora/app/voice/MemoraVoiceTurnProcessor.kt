package ai.memora.app.voice

import ai.memora.app.data.VoiceMoneyKey
import ai.memora.app.data.VoiceMoneyStore
import java.io.File

/** Routes every natural-language turn through Gemini and the allowlisted local tools. */
internal class MemoraVoiceTurnProcessor(context: android.content.Context) : VoiceTurnProcessor {
    private val financeStore = VoiceMoneyStore(File(context.filesDir, "voice-memory.bin"), VoiceMoneyKey::get)
    private val agent = AgentVoiceTurnProcessor(context, financeStore)

    override suspend fun handle(transcript: String): VoiceFirstResult = agent.handle(transcript)
}
