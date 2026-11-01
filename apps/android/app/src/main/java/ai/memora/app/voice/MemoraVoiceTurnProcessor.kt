package ai.memora.app.voice

/** Routes every natural-language turn through Gemini and the allowlisted local tools. */
internal class MemoraVoiceTurnProcessor(context: android.content.Context) : VoiceTurnProcessor {
    private val agent = AgentVoiceTurnProcessor(context)

    override suspend fun handle(transcript: String): VoiceFirstResult = agent.handle(transcript)
}
