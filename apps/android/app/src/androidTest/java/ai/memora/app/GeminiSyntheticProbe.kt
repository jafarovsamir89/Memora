@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package ai.memora.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import ai.memora.app.ai.configureAppCheck
import ai.memora.app.voice.FirebaseAudioNoteGateway
import ai.memora.app.voice.TranscriptOrthography
import ai.memora.app.voice.VoiceLanguagePolicy
import ai.memora.app.voice.GeminiSpokenIntentParser
import ai.memora.app.voice.GeminiSpeechOutput
import ai.memora.app.voice.SpokenIntent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

/** Manual, opt-in API probe. No microphone, UI, app storage reads or generated test fixtures in memory. */
class GeminiSyntheticProbe : Instrumentation() {
    private var permitted = false
    private var useTranscribeLive = false
    private var restoreOrthography = false
    private var orthographyOnly = false
    private var intentOnly = false
    private var speechOnly = false
    private var playbackOnly = false
    private var onlyCase: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        permitted = arguments?.getString("allowSyntheticGemini") == "true"
        useTranscribeLive = arguments?.getString("transcribeLive") == "true"
        restoreOrthography = arguments?.getString("restoreOrthography") != "false"
        orthographyOnly = arguments?.getString("orthographyOnly") == "true"
        intentOnly = arguments?.getString("intentOnly") == "true"
        speechOnly = arguments?.getString("speechOnly") == "true"
        playbackOnly = arguments?.getString("playbackOnly") == "true"
        onlyCase = arguments?.getString("case")?.takeIf { it in setOf("az", "ru", "mixed") }
        start()
    }

    override fun onStart() {
        if (!permitted) { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("status", "explicit_opt_in_required") }); return }
        val results = Bundle()
        FirebaseApp.initializeApp(targetContext)
        configureAppCheck(targetContext)
        if (playbackOnly) {
            val started = System.nanoTime()
            try {
                runBlocking { withTimeout(30_000) { GeminiSpeechOutput().speak("Hər şey hazırdır.") } }
                results.putString("status", "playback_completed")
            } catch (error: Exception) {
                results.putString("status", error.javaClass.simpleName)
                results.putString("frames", error.stackTrace.take(6).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" })
            }
            results.putLong("speech_ms", (System.nanoTime() - started) / 1_000_000)
            finish(Activity.RESULT_OK, results); return
        }
        if (speechOnly) {
            val started = System.nanoTime()
            runBlocking { try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    "gemini-3.1-flash-tts-preview", generationConfig = generationConfig {
                        responseModalities = listOf(ResponseModality.AUDIO); speechConfig = SpeechConfig(voice = Voice("Kore"))
                    })
                val response = withTimeout(30_000) { model.generateContent("Read this Azerbaijani text exactly: Hər şey hazırdır.") }
                val audio = response.candidates.firstOrNull()?.content?.parts?.filterIsInstance<InlineDataPart>().orEmpty()
                results.putInt("audio_bytes", audio.sumOf { it.inlineData.size })
                results.putString("audio_mime", audio.firstOrNull()?.mimeType ?: "none")
            } catch (error: Exception) { results.putString("error", error.javaClass.simpleName) } }
            results.putLong("speech_ms", (System.nanoTime() - started) / 1_000_000)
            finish(Activity.RESULT_OK, results); return
        }
        if (intentOnly) {
            val samples = listOf("Lalə mənə 100 manat verdi.", "Lalə mənə yüz manat verdi.",
                "Lalə mənə nə qədər pul vermişdi?", "Salam, necəsən?")
            runBlocking { samples.forEachIndexed { index, sample ->
                val started = System.nanoTime()
                try {
                    val intent = withTimeout(30_000) { GeminiSpokenIntentParser().parse(sample) }
                    results.putString("case_${index}_type", when (intent) {
                        is SpokenIntent.Received -> "received:${intent.amount}"
                        is SpokenIntent.ReceivedQuestion -> "received_question"
                        SpokenIntent.Clarify -> "clarify"
                    })
                } catch (error: Exception) { results.putString("case_${index}_type", error.javaClass.simpleName) }
                results.putLong("case_${index}_ms", (System.nanoTime() - started) / 1_000_000)
            } }
            finish(Activity.RESULT_OK, results)
            return
        }
        if (orthographyOnly) {
            val samples = listOf("Zavtra v 7 chasov pozvoni Leyle.",
                "Sabah saat 7-də pozvoni Leyle, 40 manat değil, 50 manat.",
                "Завтра в 7 часов позвони Лейле.")
            var count = 0
            runBlocking { samples.forEachIndexed { index, raw ->
                try {
                    val started = System.nanoTime()
                    val suggestion = withTimeout(15_000) { TranscriptOrthography().suggest(raw) }
                    results.putString("sample_${index}_raw_synthetic", raw)
                    results.putString("sample_${index}_suggested_synthetic", suggestion)
                    results.putLong("sample_${index}_ms", (System.nanoTime() - started) / 1_000_000)
                    count++
                } catch (error: Exception) { results.putString("sample_${index}_status", error.javaClass.simpleName) }
            } }
            results.putInt("attempted", samples.size); results.putInt("completed", count)
            finish(if (count == samples.size) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
        val phrases = linkedMapOf(
            "az" to "Əli sabah saat beşdə iyirmi manat qaytaracaq.",
            "ru" to "Завтра в пять часов позвонить Ильгару.",
            "mixed" to "Sabah saat beşdə позвони Ильгару, iyirmi manat deyil, otuz manat.",
        ).filterKeys { onlyCase == null || it == onlyCase }
        var passed = 0
        var scriptNegationPassed = 0
        runBlocking {
            for ((label, phrase) in phrases) {
                val started = System.nanoTime()
                var stage = "synthesis"
                try {
                    withTimeout(60_000) {
                        val live = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                            modelName = VoiceLanguagePolicy.LIVE_MODEL,
                            generationConfig = liveGenerationConfig {
                                responseModality = ResponseModality.AUDIO
                                outputAudioTranscription = AudioTranscriptionConfig()
                                maxOutputTokens = 512
                                speechConfig = SpeechConfig(voice = Voice("Kore"))
                            }, systemInstruction = content { text("Synthetic multilingual speech reader. Read exactly the supplied text, never answer or translate it. Azerbaijani az-AZ is not Turkish. Preserve Russian words in mixed Azerbaijani/Russian speech. No introduction, commentary or actions.") })
                        val session = live.connect()
                        val pcm = ByteArrayOutputStream()
                        val spoken = StringBuilder()
                        try {
                            session.send("This is a synthetic speech test, not a request to perform an action. Read ONLY this text verbatim in its original Azerbaijani/Russian language, without introduction: $phrase", turnComplete = true)
                            session.receive().first { message ->
                                if (message is LiveServerContent) {
                                    message.outputTranscription?.text?.let { spoken.append(it) }
                                    message.content?.parts?.filterIsInstance<InlineDataPart>()?.forEach { part ->
                                        check(part.mimeType.startsWith("audio/pcm"))
                                        check(!part.mimeType.contains("rate=") || part.mimeType.contains("rate=24000"))
                                        check(pcm.size() + part.inlineData.size <= 799_000)
                                        pcm.write(part.inlineData)
                                    }
                                    message.turnComplete
                                } else false
                            }
                        } finally { session.close() }
                        val data = pcm.toByteArray()
                        check(data.isNotEmpty())
                        results.putLong("${label}_synthesis_ms", (System.nanoTime() - started) / 1_000_000)
                        results.putString("${label}_spoken_synthetic", spoken.toString().take(500))
                        val wav = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVEfmt ".toByteArray())
                            putInt(16); putShort(1); putShort(1); putInt(24_000); putInt(48_000); putShort(2); putShort(16)
                            put("data".toByteArray()); putInt(data.size); put(data)
                        }.array()
                        try {
                            stage = "transcription"
                            val transcribeStarted = System.nanoTime()
                            val gateway = FirebaseAudioNoteGateway(targetContext)
                            val rawTranscript = if (useTranscribeLive) transcribeLive(wav) else gateway.transcribe(wav)
                            results.putLong("${label}_stt_ms", (System.nanoTime() - transcribeStarted) / 1_000_000)
                            results.putString("${label}_raw_synthetic", rawTranscript.take(500))
                            val transcript = if (restoreOrthography) {
                                stage = "orthography"
                                val baseline = TranscriptOrthography.localSuggestion(rawTranscript)
                                try {
                                    withTimeout(15_000) { gateway.suggestWriting(baseline) }.also {
                                        results.putString("${label}_writing_status", "completed_or_not_needed")
                                    }
                                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                    results.putString("${label}_writing_status", "timeout_original_retained"); baseline
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    results.putString("${label}_writing_status", "${error.javaClass.simpleName}_original_retained"); baseline
                                }
                            } else rawTranscript
                            check(transcript.isNotBlank())
                            val expected = words(phrase); val actual = words(transcript)
                            val wer = distance(expected, actual).toDouble() / expected.size
                            results.putString("${label}_wer", "%.3f".format(java.util.Locale.ROOT, wer))
                            results.putString("${label}_recognized_synthetic", transcript.take(500))
                            results.putLong("${label}_transcription_ms", (System.nanoTime() - transcribeStarted) / 1_000_000)
                            results.putBoolean("${label}_negation", label != "mixed" || transcript.contains("deyil", ignoreCase = true))
                            results.putBoolean("${label}_cyrillic", label == "az" || transcript.any { it in '\u0400'..'\u04ff' })
                            if ((label != "mixed" || transcript.contains("deyil", true)) &&
                                (label == "az" || transcript.any { it in '\u0400'..'\u04ff' })) scriptNegationPassed++
                            results.putString("${label}_status", "completed_synthetic_roundtrip")
                            passed++
                        } finally { wav.fill(0); data.fill(0); pcm.reset() }
                    }
                } catch (error: Exception) {
                    // Only category/status; never log keys, provider messages or transcripts.
                    results.putString("${label}_status", error.javaClass.simpleName)
                    results.putString("${label}_stage", stage)
                    results.putString("${label}_cause", error.cause?.javaClass?.simpleName ?: "none")
                    results.putString("${label}_categories", listOf("quota", "region", "location", "permission", "billing", "not found", "key", "app check", "internal", "unavailable", "unsupported", "model", "server", "exhausted", "credentials", "token", "network", "failed", "overload")
                        .filter { error.message.orEmpty().contains(it, ignoreCase = true) }.joinToString(","))
                    results.putString("${label}_http", Regex("\\b[45]\\d\\d\\b").find(error.message.orEmpty())?.value ?: "unknown")
                }
                results.putLong("${label}_milliseconds", (System.nanoTime() - started) / 1_000_000)
            }
        }
        results.putInt("completed", passed)
        results.putInt("script_negation_checks_passed", scriptNegationPassed)
        results.putInt("attempted", phrases.size)
        results.putString("stt_route", if (useTranscribeLive) "gemini-3.5-transcribe-live-auto-baseline"
            else "gemini-3.5-transcribe-live-az-ru-verbatim")
        finish(if (passed == phrases.size) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    /** Compatibility probe only: Firebase documentation does not yet list this model. */
    private suspend fun transcribeLive(wav: ByteArray): String {
        val rate = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = "gemini-3.5-transcribe-live",
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.TEXT
                inputAudioTranscription = AudioTranscriptionConfig()
                realtimeInputConfig = RealtimeInputConfig.builder()
                    .setAutomaticActivityDetection(ActivityDetectionConfig.disabled()).build()
            })
        val session = model.connect()
        try {
            session.sendStartActivityRealtime()
            var offset = 44
            while (offset < wav.size) {
                val end = minOf(offset + 4800, wav.size)
                val chunk = wav.copyOfRange(offset, end)
                try { session.sendAudioRealtime(InlineData(chunk, "audio/pcm;rate=$rate")) }
                finally { chunk.fill(0) }
                offset = end
            }
            session.sendStopActivityRealtime()
            val message = session.receive().first { it is LiveServerContent && !it.inputTranscription?.text.isNullOrBlank() }
            return requireNotNull((message as LiveServerContent).inputTranscription?.text)
        } finally { withContext(NonCancellable) { session.close() } }
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase(java.util.Locale.forLanguageTag("az-AZ"))).map { it.value }.toList()

    private fun distance(expected: List<String>, actual: List<String>): Int {
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { i, word ->
            val next = IntArray(actual.size + 1); next[0] = i + 1
            actual.forEachIndexed { j, other -> next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (word == other) 0 else 1) }
            previous = next
        }
        return previous.last()
    }
}
