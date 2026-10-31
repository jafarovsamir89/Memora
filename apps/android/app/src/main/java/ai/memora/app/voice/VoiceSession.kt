package ai.memora.app.voice

enum class VoiceState { Idle, Starting, Listening, Processing, Finished, Unavailable, Error }

interface VoiceEngine {
    interface Listener {
        fun ready()
        fun ended()
        fun result(text: String)
        fun error()
    }
    fun available(): Boolean
    fun start(language: String, listener: Listener)
    fun stop()
    fun cancel()
}

/** Does not request permissions, persist text, execute actions, or start without an explicit call. */
class VoiceSession(private val engine: VoiceEngine, private val onState: (VoiceState) -> Unit, private val onText: (String) -> Unit) {
    private var generation = 0
    private var active = false
    private var stopping = false

    fun start(language: String) {
        if (active) return
        try {
            if (!engine.available()) { onState(VoiceState.Unavailable); return }
            active = true
            stopping = false
            val current = ++generation
            onState(VoiceState.Starting)
            engine.start(language, object : VoiceEngine.Listener {
                private fun valid() = active && generation == current
                override fun ready() { if (valid() && !stopping) onState(VoiceState.Listening) }
                override fun ended() { if (valid()) { stopping = true; onState(VoiceState.Processing) } }
                override fun result(text: String) {
                    if (!valid()) return
                    active = false
                    try { engine.cancel() } catch (_: Exception) { }
                    if (text.isBlank()) onState(VoiceState.Error)
                    else { onState(VoiceState.Finished); onText(text.trim()) }
                }
                override fun error() {
                    if (!valid()) return
                    active = false
                    try { engine.cancel() } catch (_: Exception) { }
                    onState(VoiceState.Error)
                }
            })
        } catch (_: Exception) {
            active = false
            try { engine.cancel() } catch (_: Exception) { }
            onState(VoiceState.Error)
        }
    }

    fun stop() {
        if (!active || stopping) return
        stopping = true
        try { onState(VoiceState.Processing); engine.stop() }
        catch (_: Exception) { cancel(); onState(VoiceState.Error) }
    }

    fun cancel() {
        if (!active) return
        active = false
        generation++
        try { engine.cancel() } catch (_: Exception) { }
        onState(VoiceState.Idle)
    }
}
