package ai.memora.app.voice

import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceLanguagePolicyTest {
    @Test fun azerbaijaniNeverFallsBackToTurkish() {
        assertEquals("az-AZ", VoiceLanguagePolicy.locale("az").toLanguageTag())
        assertFalse(VoiceLanguagePolicy.acceptsVoice("az", Locale.forLanguageTag("tr-TR")))
        assertFalse(VoiceLanguagePolicy.acceptsVoice("az", null))
        assertTrue(VoiceLanguagePolicy.acceptsVoice("az", Locale.forLanguageTag("az-AZ")))
        assertTrue(VoiceLanguagePolicy.acceptsVoice("ru", Locale.forLanguageTag("ru-RU")))
    }

    @Test fun policyPreservesCodeSwitchingNegationAndNoUnverifiedActions() {
        val instruction = VoiceLanguagePolicy.instruction()
        listOf("az-AZ", "Türkiyə türkcəsi deyil", "rus", "məbləğ", "inkar", "mənbəsiz").forEach { assertTrue(instruction.contains(it)) }
        assertTrue(VoiceLanguagePolicy.transcriptionInstruction().contains("verbatim"))
        assertTrue(VoiceLanguagePolicy.transcriptionInstruction().contains("[anlaşılmır]"))
    }

    @Test fun transcriptSeparatesUserTurnsAndNeverPersistsAssistantAsUser() {
        val buffer = VoiceTranscriptBuffer()
        buffer.append("Əli, ", null); buffer.append("120 deyil, 20 manat.", null)
        buffer.append(null, "Dəqiqləşdirin."); buffer.append("Завтра, saat beşdə.", null)
        assertEquals("Əli, 120 deyil, 20 manat.\nЗавтра, saat beşdə.", buffer.input)
        assertEquals("Dəqiqləşdirin.", buffer.output)
        assertFalse(buffer.overflow)
    }

    @Test fun overflowIsExplicitAndDoesNotDropEarlierEvidence() {
        val buffer = VoiceTranscriptBuffer(5)
        buffer.append("Əli", null); buffer.append(" 120", null)
        assertTrue(buffer.overflow)
        assertEquals("Əli", buffer.input)
    }

    @Test fun wavHeaderMatchesTheActualRecordedFormat() {
        val pcm = ByteArray(3200)
        val wav = pcmToWav(pcm)
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(3236, bytes.getInt(4)); assertEquals(16_000, bytes.getInt(24))
        assertEquals(32_000, bytes.getInt(28)); assertEquals(3200, bytes.getInt(40))
        assertEquals(1.toShort(), bytes.getShort(22)); assertEquals(16.toShort(), bytes.getShort(34))
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
    }
}
