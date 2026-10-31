package ai.memora.app.voice

import org.junit.Assert.*
import org.junit.Test

class TranscriptOrthographyTest {
    @Test fun cyrillicOnlySpeechNeedsNoExtraModelRequest() {
        assertFalse(TranscriptOrthography.needsScriptReview("Завтра в 5 часов позвонить Ильгару."))
        assertTrue(TranscriptOrthography.needsScriptReview("Sabah позвони."))
        assertEquals("20 manat deyil, 30 manat. Deyil.", TranscriptOrthography.localSuggestion("20 manat değil, 30 manat. Değil."))
        assertEquals("değildir, deyil.", TranscriptOrthography.localSuggestion("değildir, deyil."))
    }
    @Test fun scriptRestorationMayPreserveMixedSpeechButNeverChangeNumericFacts() {
        val raw = "Sabah saat 5-də pozvoni İlqaru. 20 manat deyil, 30 manat."
        assertTrue(TranscriptOrthography.safeSuggestion(raw, "Sabah saat 5-də позвони Илгару. 20 manat deyil, 30 manat."))
        assertFalse(TranscriptOrthography.safeSuggestion(raw, "Sabah saat 6-da позвони Илгару. 20 manat deyil, 30 manat."))
        assertFalse(TranscriptOrthography.safeSuggestion(raw, "Sabah saat 5-də позвони Илгару. 200 manat deyil, 30 manat."))
        assertFalse(TranscriptOrthography.safeSuggestion(raw, "Sabah saat 5-də позвони Илгару. 30 manat."))
    }

    @Test fun droppingANegationOrReturningEmptyIsRejected() {
        assertTrue(TranscriptOrthography.safeSuggestion("20 manat değil.", "20 manat deyil."))
        assertFalse(TranscriptOrthography.safeSuggestion("20 manat deyil.", "20 manat."))
        assertFalse(TranscriptOrthography.safeSuggestion("ne 20 manat.", "20 manat."))
        assertFalse(TranscriptOrthography.safeSuggestion("Завтра.", ""))
    }

    @Test fun translationIsRejectedAndOriginalCyrillicAndPunctuationRemainIntact() {
        val raw = "Завтра в 5 часов позвонить Ильгару."
        assertEquals(raw, TranscriptOrthography.validatedSuggestion(raw, "Sabah saat 5-də İlqara zəng etmək."))
        assertEquals("Sabah saat 5-də позвони İlqaru. 20 manat deyil, 30 manat.",
            TranscriptOrthography.validatedSuggestion("Sabah saat 5-də pozvoni İlqaru. 20 manat değil, 30 manat.",
                "Sabah saat 5-də позвони Ильгару. 20 manat deyil, 30 manat."))
        assertEquals("Завтра в 5 часов позвонить Ильгару.",
            TranscriptOrthography.validatedSuggestion(raw, "Sabah в 5 часов позвонить Ильгару!"))
    }
}
