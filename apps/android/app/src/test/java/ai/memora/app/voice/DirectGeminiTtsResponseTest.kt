package ai.memora.app.voice

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DirectGeminiTtsResponseTest {
    @Test fun decodesCanonicalCamelCaseInlineAudio() {
        val pcm = decodeDirectGeminiTtsAudio(JSONObject("""
            {"candidates":[{"content":{"parts":[
              {"inlineData":{"mimeType":"audio/pcm","data":"AAECAw=="}}
            ]}}]}
        """))

        assertArrayEquals(byteArrayOf(0, 1, 2, 3), pcm)
    }

    @Test fun keepsLegacySnakeCaseInlineAudioCompatible() {
        val pcm = decodeDirectGeminiTtsAudio(JSONObject("""
            {"candidates":[{"content":{"parts":[
              {"inline_data":{"mime_type":"audio/pcm","data":"AAECAw=="}}
            ]}}]}
        """))

        assertArrayEquals(byteArrayOf(0, 1, 2, 3), pcm)
    }
}
