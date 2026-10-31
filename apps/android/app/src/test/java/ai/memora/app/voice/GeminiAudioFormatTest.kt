package ai.memora.app.voice

import org.junit.Assert.*
import org.junit.Test

class GeminiAudioFormatTest {
    @Test fun acceptsGeminisObservedPcmButRejectsDifferentEncodingAndRate() {
        assertTrue(GeminiAudioFormat.accepts("audio/l16; rate=24000; channels=1"))
        assertTrue(GeminiAudioFormat.accepts("audio/pcm"))
        assertFalse(GeminiAudioFormat.accepts("audio/l16; rate=16000; channels=1"))
        assertFalse(GeminiAudioFormat.accepts("audio/l16; rate=24000; channels=2"))
        assertFalse(GeminiAudioFormat.accepts("audio/wav"))
    }
}
