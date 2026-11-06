package ai.memora.app.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscribeProtocolTest {
    @Test fun audioUsesSelectedGeminiMultimodalModelAndAzFirstVerbatimPrompt() {
        assertEquals("gemini-3.6-flash", VoiceLanguagePolicy.AUDIO_TRANSCRIPTION_MODEL)
        val instruction = VoiceLanguagePolicy.transcriptionInstruction()
        assertTrue(instruction.contains("verbatim"))
        assertTrue(instruction.contains("Azerbaijani az-AZ"))
        assertTrue(instruction.contains("Russian"))
        assertTrue(instruction.contains("[anlaşılmır]"))
    }

    @Test fun onlyBoundedMonoPcm16kWavIsAccepted() {
        val wav = pcmToWav(ByteArray(3200))
        validateTranscriptionWav(wav)
        val stereo = wav.clone()
        java.nio.ByteBuffer.wrap(stereo).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(22, 2)
        assertThrows(IllegalArgumentException::class.java) { validateTranscriptionWav(stereo) }
        assertThrows(IllegalArgumentException::class.java) { validateTranscriptionWav(wav.copyOf(wav.size - 2)) }
        val wrongRate = wav.clone()
        java.nio.ByteBuffer.wrap(wrongRate).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(24, 24_000)
        assertThrows(IllegalArgumentException::class.java) { validateTranscriptionWav(wrongRate) }
    }

    @Test fun tokenMappingsCannotTranslateOrRewriteFacts() {
        val raw = "Sabah, pozvoni Leyle: 40 manat değil, 50 manat."
        assertEquals("Sabah, позвони Leyle: 40 manat deyil, 50 manat.", TranscriptOrthography.fromMappings(raw,
            """{"Sabah":"завтра","pozvoni":"позвони","değil":"deyil","Leyle":"Лейле"}"""))
        assertEquals(raw, TranscriptOrthography.fromMappings(raw, "not JSON"))
        assertEquals(TranscriptOrthography.localSuggestion(raw), TranscriptOrthography.fromMappings(raw, """{"40":"400"}"""))
    }
}
