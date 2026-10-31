package ai.memora.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscribeProtocolTest {
    @Test fun recognitionIsVerbatimWithAzRuHintsAndOneExplicitActivity() {
        val setup = JSONObject(TranscribeProtocol.setup("synthetic-project")).getJSONObject("setup")
        val config = setup.getJSONObject("inputAudioTranscription")
        assertEquals(listOf("az-AZ", "ru-RU"), (0 until config.getJSONArray("languageCodes").length())
            .map { config.getJSONArray("languageCodes").getString(it) })
        assertEquals("VERBATIM", config.getString("mode"))
        assertTrue(setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
        assertFalse(setup.has("tools"))
    }

    @Test fun finalTextIsAcceptedButSpeculativeAndModelTextAreNeverEvidence() {
        assertNull(TranscribeProtocol.finalTranscript("""{"serverContent":{"interimInputTranscription":{"text":"qırx"}}}"""))
        assertNull(TranscribeProtocol.finalTranscript("""{"serverContent":{"modelTurn":{"parts":[{"text":"Saved"}]}}}"""))
        assertEquals("Əli, 40 deyil, 20 manat. Завтра.", TranscribeProtocol.finalTranscript(
            """{"serverContent":{"inputTranscription":{"text":"Əli, 40 deyil, 20 manat. Завтра."}}}"""))
        assertNull(TranscribeProtocol.finalTranscript("""{"serverContent":{"inputTranscription":{"text":" "}}}"""))
    }

    @Test fun corruptedAudioAndOversizedTranscriptFailInsteadOfTruncating() {
        val wav = pcmToWav(ByteArray(3200))
        assertEquals(16_000, TranscribeProtocol.sampleRate(wav))
        val stereo = wav.clone()
        ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 2)
        assertThrows(IllegalArgumentException::class.java) { TranscribeProtocol.sampleRate(stereo) }
        assertThrows(IllegalArgumentException::class.java) { TranscribeProtocol.sampleRate(wav.copyOf(wav.size - 2)) }
        val huge = JSONObject().put("serverContent", JSONObject().put("inputTranscription", JSONObject().put("text", "a".repeat(20_001))))
        assertThrows(IllegalArgumentException::class.java) { TranscribeProtocol.finalTranscript(huge.toString()) }
        assertThrows(IllegalStateException::class.java) { TranscribeProtocol.finalTranscript("""{"error":{"message":"private provider body"}}""") }
    }

    @Test fun audioPayloadExcludesTheWavHeaderAndKeepsActualSampleRate() {
        val pcm = ByteArray(3200) { (it % 255).toByte() }
        val wav = pcmToWav(pcm)
        val blob = JSONObject(TranscribeProtocol.audio(wav, 44, wav.size, 16_000))
            .getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", blob.getString("mimeType"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(blob.getString("data")))
    }

    @Test fun tokenMappingsCannotTranslateOrRewriteFacts() {
        val raw = "Sabah, pozvoni Leyle: 40 manat değil, 50 manat."
        assertEquals("Sabah, позвони Leyle: 40 manat deyil, 50 manat.", TranscriptOrthography.fromMappings(raw,
            """{"Sabah":"завтра","pozvoni":"позвони","değil":"deyil","Leyle":"Лейле"}"""))
        assertEquals(raw, TranscriptOrthography.fromMappings(raw, "not JSON"))
        assertEquals(TranscriptOrthography.localSuggestion(raw), TranscriptOrthography.fromMappings(raw, """{"40":"400"}"""))
    }
}
