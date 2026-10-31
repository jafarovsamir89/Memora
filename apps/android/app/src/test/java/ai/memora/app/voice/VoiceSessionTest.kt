package ai.memora.app.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionTest {
    private class FakeEngine(var supported: Boolean = true) : VoiceEngine {
        var starts = 0
        var stops = 0
        var cancels = 0
        var listener: VoiceEngine.Listener? = null
        override fun available() = supported
        override fun start(language: String, listener: VoiceEngine.Listener) { starts++; this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
    }

    @Test fun explicitStartAndResultDoNotSaveAnything() {
        val engine = FakeEngine()
        val texts = mutableListOf<String>()
        val states = mutableListOf<VoiceState>()
        val session = VoiceSession(engine, states::add, texts::add)
        assertEquals(0, engine.starts)
        session.start("ru-RU")
        engine.listener!!.ready()
        engine.listener!!.result("synthetic")
        assertEquals(listOf("synthetic"), texts)
        assertEquals(VoiceState.Finished, states.last())
        assertEquals(1, engine.cancels)
    }

    @Test fun cancellationIgnoresLateResultAndReleasesEngine() {
        val engine = FakeEngine()
        val texts = mutableListOf<String>()
        val session = VoiceSession(engine, {}, texts::add)
        session.start("en-US")
        val old = engine.listener!!
        session.cancel()
        old.result("late")
        assertTrue(texts.isEmpty())
        assertEquals(1, engine.cancels)
    }

    @Test fun unavailableDoesNotStartOrFallBack() {
        val engine = FakeEngine(false)
        val states = mutableListOf<VoiceState>()
        val session = VoiceSession(engine, states::add, {})
        session.start("az-AZ")
        assertEquals(0, engine.starts)
        assertEquals(VoiceState.Unavailable, states.last())
    }

    @Test fun stopWaitsForResultAndErrorsDoNotProduceText() {
        val engine = FakeEngine()
        val states = mutableListOf<VoiceState>()
        val texts = mutableListOf<String>()
        val session = VoiceSession(engine, states::add, texts::add)
        session.start("ru-RU")
        session.stop()
        assertEquals(1, engine.stops)
        assertEquals(VoiceState.Processing, states.last())
        engine.listener!!.ready()
        session.stop()
        assertEquals(1, engine.stops)
        assertEquals(VoiceState.Processing, states.last())
        engine.listener!!.error()
        assertEquals(VoiceState.Error, states.last())
        assertTrue(texts.isEmpty())
    }

    @Test fun emptyResultIsErrorAndOldCallbacksCannotAffectNewSession() {
        val engine = FakeEngine()
        val states = mutableListOf<VoiceState>()
        val texts = mutableListOf<String>()
        val session = VoiceSession(engine, states::add, texts::add)
        session.start("ru-RU")
        val old = engine.listener!!
        session.cancel()
        session.start("ru-RU")
        old.error()
        assertEquals(VoiceState.Starting, states.last())
        engine.listener!!.result("   ")
        assertEquals(VoiceState.Error, states.last())
        assertTrue(texts.isEmpty())
    }
}
