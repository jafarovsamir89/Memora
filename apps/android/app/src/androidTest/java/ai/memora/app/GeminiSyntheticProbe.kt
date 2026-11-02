@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package ai.memora.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import ai.memora.app.data.protectedstore.DatabaseStorageProbe
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.RoomAgentRunJournal
import ai.memora.app.agent.*
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
import com.google.firebase.appcheck.FirebaseAppCheck
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.UUID

/** Manual, opt-in API probe. No microphone, UI, app storage reads or generated test fixtures in memory. */
class GeminiSyntheticProbe : Instrumentation() {
    private var permitted = false
    private var storageProbe = false
    private var migrationProbe = false
    private var envelopeWriteFailureProbe = false
    private var useTranscribeLive = false
    private var restoreOrthography = false
    private var orthographyOnly = false
    private var intentOnly = false
    private var functionCallingOnly = false
    private var simpleGenerationOnly = false
    private var appCheckTokenOnly = false
    private var syntheticModelName = "gemini-3.5-flash-lite"
    private var agentE2ePhase: String? = null
    private var syntheticAgentE2eAllowed = false
    private var syntheticRunId: String? = null
    private var processDeathProbe: String? = null
    private var processDeathPermitted = false
    private var speechOnly = false
    private var playbackOnly = false
    private var onlyCase: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        permitted = arguments?.getString("allowSyntheticGemini") == "true"
        storageProbe = arguments?.getString("allowSyntheticStorageProbe") == "true"
        migrationProbe = arguments?.getString("allowSyntheticMigrationProbe") == "true"
        envelopeWriteFailureProbe = arguments?.getString("allowSyntheticEnvelopeWriteFailureProbe") == "true"
        useTranscribeLive = arguments?.getString("transcribeLive") == "true"
        restoreOrthography = arguments?.getString("restoreOrthography") != "false"
        orthographyOnly = arguments?.getString("orthographyOnly") == "true"
        intentOnly = arguments?.getString("intentOnly") == "true"
        functionCallingOnly = arguments?.getString("functionCallingOnly") == "true"
        simpleGenerationOnly = arguments?.getString("simpleGenerationOnly") == "true"
        appCheckTokenOnly = arguments?.getString("appCheckTokenOnly") == "true"
        syntheticModelName = arguments?.getString("syntheticModelName")
            ?.takeIf { it in setOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.8-flash") }
            ?: "gemini-3.5-flash-lite"
        agentE2ePhase = arguments?.getString("agentE2ePhase")?.takeIf { it in setOf("mutate", "verify", "cleanup") }
        syntheticAgentE2eAllowed = arguments?.getString("allowSyntheticAgentE2E") == "true"
        syntheticRunId = arguments?.getString("syntheticRunId")?.takeIf {
            it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        }
        processDeathProbe = arguments?.getString("processDeathProbe")?.takeIf {
            it in setOf("prepare", "verify", "rotation-prepare", "rotation-verify")
        }
        processDeathPermitted = arguments?.getString("allowSyntheticProcessDeathProbe") == "true"
        speechOnly = arguments?.getString("speechOnly") == "true"
        playbackOnly = arguments?.getString("playbackOnly") == "true"
        onlyCase = arguments?.getString("case")?.takeIf { it in setOf("az", "ru", "mixed") }
        start()
    }

    override fun onStart() {
        if (storageProbe) {
            val results = DatabaseStorageProbe.run(targetContext, permitted = true)
            finish(if (results.getString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
        if (migrationProbe) {
            val migrationResults = DatabaseStorageProbe.runMigration(targetContext, permitted = true)
            finish(if (migrationResults.getString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, migrationResults)
            return
        }
        if (envelopeWriteFailureProbe) {
            val failureResults = DatabaseStorageProbe.runEnvelopeWriteFailureProbe(targetContext, permitted = true)
            finish(if (failureResults.getString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, failureResults)
            return
        }
        if (processDeathProbe != null) {
            if (!processDeathPermitted) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("status", "explicit_opt_in_required") })
                return
            }
            val crashResults = when (processDeathProbe) {
                "prepare" -> DatabaseStorageProbe.prepareProcessDeathProbe(targetContext, permitted = true)
                "verify" -> DatabaseStorageProbe.verifyProcessDeathProbe(targetContext, permitted = true)
                "rotation-prepare" -> DatabaseStorageProbe.prepareKeyRotationProcessDeathProbe(targetContext, permitted = true)
                else -> DatabaseStorageProbe.verifyKeyRotationProcessDeathProbe(targetContext, permitted = true)
            }
            finish(if (crashResults.getString("status") == "passed" || crashResults.getString("status") == "prepared") Activity.RESULT_OK else Activity.RESULT_CANCELED, crashResults)
            return
        }
        if (!permitted) { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("status", "explicit_opt_in_required") }); return }
        val results = Bundle()
        FirebaseApp.initializeApp(targetContext)
        configureAppCheck(targetContext)
        if (appCheckTokenOnly) {
            val started = System.nanoTime()
            try {
                val token = runBlocking {
                    withTimeout(10_000) {
                        FirebaseAppCheck.getInstance(FirebaseApp.getInstance()).getAppCheckToken(true).await()
                    }
                }
                results.putString("status", "app_check_token_acquired")
                results.putBoolean("token_nonblank", token.token.isNotBlank())
            } catch (error: Exception) {
                results.putString("status", "app_check_token_failed")
                results.putString("exception_type", error.javaClass.simpleName.take(64))
                safeHttpStatus(error)?.let { results.putString("http_status", it) }
                results.putString("code", safeProbeCode(error))
            }
            results.putLong("app_check_ms", (System.nanoTime() - started) / 1_000_000)
            finish(if (results.getString("status") == "app_check_token_acquired" && results.getBoolean("token_nonblank"))
                Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
        if (simpleGenerationOnly) {
            val started = System.nanoTime()
            results.putString("model_id", syntheticModelName)
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    syntheticModelName,
                    generationConfig = generationConfig { temperature = 0f; maxOutputTokens = 32 },
                )
                val response = runBlocking {
                    withTimeout(30_000) { model.generateContent("Reply exactly SYNTHETIC-GEMINI-READY and nothing else.") }
                }
                val matched = response.text.orEmpty().contains("SYNTHETIC-GEMINI-READY")
                results.putString("status", if (matched) "synthetic_text_generation_passed" else "synthetic_text_generation_unexpected")
                results.putBoolean("response_marker_matched", matched)
            } catch (error: Exception) {
                results.putString("status", "synthetic_text_generation_failed")
                results.putString("exception_type", error.javaClass.simpleName.take(64))
                safeHttpStatus(error)?.let { results.putString("http_status", it) }
                results.putString("code", safeProbeCode(error))
            }
            results.putLong("generation_ms", (System.nanoTime() - started) / 1_000_000)
            finish(if (results.getString("status") == "synthetic_text_generation_passed")
                Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
        if (agentE2ePhase != null) {
            runSyntheticAgentE2E(agentE2ePhase!!)
            return
        }
        if (functionCallingOnly) {
            val started = System.nanoTime()
            results.putString("model_id", syntheticModelName)
            val functionName = "lookup_synthetic_marker"
            val declaration = FunctionDeclaration(
                functionName,
                "Return the fixed synthetic marker used by this test. This function reads no app data.",
                emptyMap(),
            )
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    syntheticModelName,
                    generationConfig = generationConfig { temperature = 0f; maxOutputTokens = 128 },
                    tools = listOf(Tool.functionDeclarations(listOf(declaration))),
                    systemInstruction = content { text("For every user request, call $functionName exactly once. Never claim a value that is not returned by that function.") },
                )
                runBlocking {
                    val chat = model.startChat()
                    val proposed = withTimeout(30_000) { chat.sendMessage("Mənə sintetik test nişanını göstər.") }
                    val calls = proposed.functionCalls
                    results.putInt("function_call_count", calls.size)
                    check(calls.size == 1 && calls.single().name == functionName && calls.single().args.isEmpty())
                    val response = FunctionResponsePart(functionName,
                        JsonObject(mapOf("marker" to JsonPrimitive("SYNTHETIC-CARD-42"))))
                    val answer = withTimeout(30_000) { chat.sendMessage(content("user") { part(response) }) }
                    val finalText = answer.text.orEmpty()
                    check(finalText.contains("SYNTHETIC-CARD-42"))
                    results.putString("status", "synthetic_function_chain_passed")
                    results.putBoolean("model_called_allowlisted_function", true)
                    results.putBoolean("function_result_was_returned_to_model", true)
                    results.putBoolean("final_answer_matches_function_result", true)
                }
            } catch (error: Exception) {
                results.putString("status", "synthetic_function_chain_failed")
                results.putString("error_type", error.javaClass.simpleName)
                val safeCauseMessages = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { it.message }.joinToString(" ")
                Regex("\\b([45][0-9]{2})\\b").find(safeCauseMessages)?.groupValues?.get(1)?.let {
                    results.putString("provider_http_status", it)
                }
                results.putBoolean("app_check_rejection_hint", safeCauseMessages.contains("app check", ignoreCase = true))
                results.putBoolean("model_called_allowlisted_function", false)
                results.putBoolean("function_result_was_returned_to_model", false)
                results.putBoolean("final_answer_matches_function_result", false)
            }
            results.putLong("function_chain_ms", (System.nanoTime() - started) / 1_000_000)
            finish(if (results.getString("status") == "synthetic_function_chain_passed") Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }
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

    private fun runSyntheticAgentE2E(phase: String) {
        val results = Bundle().apply { putString("phase", phase) }
        val runId = syntheticRunId
        if (!permitted || !syntheticAgentE2eAllowed || runId == null) {
            results.putString("status", "explicit_synthetic_opt_in_required")
            finish(Activity.RESULT_CANCELED, results)
            return
        }
        val databaseName = "memora_synthetic_e2e_${runId.replace("-", "")}.db"
        require(databaseName.matches(Regex("memora_synthetic_e2e_[0-9a-f]{32}\\.db")))
        results.putString("trace_id", runId)
        if (phase == "cleanup") {
            results.putString("status", if (targetContext.deleteDatabase(databaseName)) "synthetic_database_cleaned" else "synthetic_database_cleanup_failed")
            finish(if (results.getString("status") == "synthetic_database_cleaned") Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
            return
        }

        val key = MessageDigest.getInstance("SHA-256").digest("Memora synthetic Gemini E2E v1:$runId".toByteArray(Charsets.UTF_8))
        var database: AgentDatabase? = null
        var stage = "database_open"
        var passed = false
        try {
            database = AgentDatabase.openWithKeyForSyntheticProbe(targetContext, databaseName, key)
            runBlocking {
                val db = requireNotNull(database)
                val repository = ProtectedDomainRepository(db)
                val conversations = ProtectedConversationRepository(db)
                val journal = RoomAgentRunJournal(db)
                var memoryConsent = true
                val policy = ConsentAgentRuntimePolicy(cloudConsent = { true }, memoryConsent = { memoryConsent })
                val languages = onlyCase?.takeIf { it in setOf("az", "ru") }?.let { listOf(it) } ?: listOf("az", "ru")

                if (phase == "mutate") {
                    languages.forEach { language ->
                        check(repository.allFinanceEvents(syntheticUuid("$runId:$language:scope")).isEmpty())
                        runSyntheticFinanceFlow(language, runId, db, repository, conversations, journal, policy, results)
                    }

                    stage = "revoked_consent_gate"
                    results.putString("active_stage", stage)
                    memoryConsent = false
                    val scope = syntheticUuid("$runId:${languages.first()}:scope")
                    val history = conversations.recentContext(scope, 12).map { AgentDialogueMessage(it.role, it.message.takeLast(4_000)) }
                    val result = AgentOrchestrator(GeminiAgentModelGateway(), DefaultAgentToolRegistry(
                        LocalMemoryTools(repository, ProtectedMemoryRepository(db), conversations = conversations).handlers()),
                        policy, journal).run(AgentTurnRequest(UUID.randomUUID().toString(), scope,
                        syntheticUuid("$runId:${languages.first()}:actor"), null, "az-AZ", "synthetic revoked-consent probe",
                        AgentInputChannel.TEXT, history))
                    check(result.status == AgentRunResultStatus.FAILED && result.code == "memory_consent_required" && result.toolResults.isEmpty()) {
                        "synthetic_revoked_consent_gate_failed"
                    }
                    results.putString("revoked_consent", "blocked_before_model_and_tools")
                } else {
                    stage = "database_readback_after_restart"
                    languages.forEach { language ->
                        verifySyntheticFinanceFlow(language, runId, db, repository, conversations, journal, policy, results)
                    }
                    results.putString("restart_readback", "verified")
                    results.putString("restart_search", "passed")
                }
            }
            passed = true
            results.putString("status", "synthetic_agent_e2e_${phase}_passed")
        } catch (error: Exception) {
            results.putString("status", "synthetic_agent_e2e_failed")
            results.putString("stage", results.getString("active_stage") ?: stage)
            results.putString("code", safeProbeCode(error))
            results.putString("exception_type", error.javaClass.simpleName.take(64))
            safeHttpStatus(error)?.let { results.putString("http_status", it) }
        } finally {
            runCatching { database?.close() }
            key.fill(0)
        }
        if (phase == "verify" && passed) {
            results.putString("synthetic_db_cleanup", if (targetContext.deleteDatabase(databaseName)) "completed" else "failed")
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private suspend fun runSyntheticFinanceFlow(language: String, runId: String, db: AgentDatabase, repository: ProtectedDomainRepository,
        conversations: ProtectedConversationRepository, journal: RoomAgentRunJournal, policy: ConsentAgentRuntimePolicy,
        results: Bundle) {
        val scope = syntheticUuid("$runId:$language:scope")
        val actor = syntheticUuid("$runId:$language:actor")
        val az = language == "az"
        val locale = if (az) "az-AZ" else "ru-RU"
        val steps = if (az) listOf(
            "Samir mənə 100 manat verdi." to setOf("record_money_event"),
            "Bu, onun əvvəlki borcunun qismən qaytarılması idi." to setOf("search_money", "annotate_money_event"),
            "Bu qaytarmadan əvvəl Samirin mənə ümumilikdə 350 manat borcu var idi. Artıq yazılmış qaytarmanı həmin borcla əlaqələndir." to setOf("record_money_event", "settle_debt"),
            "Əvvəlki qaytarma məbləğini 80 manat olaraq düzəlt." to setOf("correct_money_event"),
            "Samirin mənə nə qədər borcu qalıb?" to setOf("search_money"),
        ) else listOf(
            "Самир дал мне 100 манат." to setOf("record_money_event"),
            "Это была частичная выплата его прежнего долга." to setOf("search_money", "annotate_money_event"),
            "До этого возврата Самир был должен мне всего 350 манат. Свяжи уже записанный возврат с этим долгом." to setOf("record_money_event", "settle_debt"),
            "Исправь сумму предыдущего возврата: вместо 100 было 80 манат." to setOf("correct_money_event"),
            "Сколько осталось?" to setOf("search_money"),
        )
        steps.forEachIndexed { index, (utterance, expectedTools) ->
            results.putString("active_stage", "${language}_step_${index + 1}")
            val channel = if ((index + if (az) 0 else 1) % 2 == 0) AgentInputChannel.VOICE else AgentInputChannel.TEXT
            val result = runSyntheticTurn(language, index, utterance, expectedTools, scope, actor, locale, db,
                repository, conversations, journal, policy, channel, results)
            results.putString("${language}_step_${index + 1}", "${result.status.name.lowercase()}_expected_tools_applied")
            if (index == 1) {
                val receipts = repository.allFinanceEvents(scope).filter { it.kind == "receipt" && !repository.isFinanceEventVoided(scope, it.eventId) }
                val annotations = repository.financeAdjustments(scope).filter { it.kind == "annotation" }
                check(receipts.size == 1 && receipts.single().minorUnits == 10_000L && annotations.size == 1 &&
                    annotations.single().reversesEventId == receipts.single().eventId && annotations.single().minorUnits == 0L) {
                    "synthetic_${language}_clarification_annotation_readback_failed"
                }
            }
            if (index == 2) verifyFinanceState(repository, scope, expectedReceipts = listOf(10_000),
                expectedApplied = 10_000, expectedRemaining = 25_000, code = "synthetic_pre_correction_readback_failed")
            if (index == 3) verifyFinanceState(repository, scope, expectedReceipts = listOf(8_000),
                expectedApplied = 8_000, expectedRemaining = 27_000, code = "synthetic_correction_readback_failed")
        }
    }

    private suspend fun verifySyntheticFinanceFlow(language: String, runId: String, db: AgentDatabase, repository: ProtectedDomainRepository,
        conversations: ProtectedConversationRepository, journal: RoomAgentRunJournal, policy: ConsentAgentRuntimePolicy,
        results: Bundle) {
        val scope = syntheticUuid("$runId:$language:scope")
        val actor = syntheticUuid("$runId:$language:actor")
        results.putString("active_stage", "${language}_restart_readback")
        verifyFinanceState(repository, scope, expectedReceipts = listOf(8_000), expectedApplied = 8_000,
            expectedRemaining = 27_000, code = "synthetic_restart_readback_failed")
        val annotations = repository.financeAdjustments(scope).filter { it.kind == "annotation" }
        check(annotations.size == 1 && annotations.single().reversesEventId in repository.allFinanceEvents(scope)
            .filter { it.kind == "receipt" }.map { it.eventId }) { "synthetic_restart_annotation_readback_failed" }
        results.putString("${language}_restart_readback", "verified")
        val locale = if (language == "az") "az-AZ" else "ru-RU"
        val query = if (language == "az") "Samirin mənə nə qədər borcu qalıb?" else "Сколько Самир ещё должен?"
        results.putString("active_stage", "${language}_restart_search")
        val result = runSyntheticTurn(language, 6, query, setOf("search_money"), scope, actor, locale, db,
            repository, conversations, journal, policy, AgentInputChannel.TEXT, results)
        val search = result.toolResults.singleOrNull { it.name == "search_money" && it.status == ToolObservationStatus.APPLIED }
        check(search != null) {
            "synthetic_restart_search_failed"
        }
        val summary = search.modelData["financial_summary"] as? Map<*, *>
        check(summary?.get("outstanding_debt_minor_units") == 27_000L) { "synthetic_restart_balance_answer_evidence_failed" }
        results.putString("${language}_restart_search", "passed")
    }

    private suspend fun runSyntheticTurn(language: String, index: Int, utterance: String, expectedTools: Set<String>,
        scope: String, actor: String, locale: String, db: AgentDatabase, repository: ProtectedDomainRepository,
        conversations: ProtectedConversationRepository, journal: RoomAgentRunJournal,
        policy: ConsentAgentRuntimePolicy, channel: AgentInputChannel, results: Bundle): AgentRunResult {
        val handlers = LocalMemoryTools(repository, ProtectedMemoryRepository(db),
            conversations = conversations).handlers()
        val history = conversations.recentContext(scope, 12).mapNotNull { row ->
            row.message.takeLast(4_000).takeIf(String::isNotBlank)?.let { AgentDialogueMessage(row.role, it) }
        }
        val turnId = UUID.randomUUID().toString()
        val turn = AgentTurnRequest(turnId, scope, actor, null, locale, utterance, channel, history)
        val prefix = "${language}_step_${index + 1}"
        val delegate = GeminiAgentModelGateway()
        var modelRound = 0
        val gateway = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply {
                val key = "${prefix}_model_${++modelRound}"
                return try {
                    delegate.complete(request).also { reply ->
                        results.putString(key, when (reply) {
                            is AgentModelReply.Calls -> "function_calls"
                            is AgentModelReply.Answer -> "answer"
                            is AgentModelReply.Clarification -> "clarification"
                        })
                    }
                } catch (error: AgentGatewayFailure) {
                    results.putString(key, "failed:${error.safeCode.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "model_request_failed"}")
                    throw error
                }
            }
        }
        val started = System.nanoTime()
        val result = AgentOrchestrator(gateway, DefaultAgentToolRegistry(handlers), policy, journal).run(turn)
        results.putLong("${prefix}_duration_ms", (System.nanoTime() - started) / 1_000_000)
        results.putString("${prefix}_trace_id", turnId)
        results.putString("${prefix}_runtime_status", result.status.name.lowercase())
        results.putString("${prefix}_runtime_code", result.code ?: "none")
        result.toolResults.take(16).forEachIndexed { toolIndex, observation ->
            // Only allowlisted tool metadata: never arguments, modelData, utterance or answer text.
            val safeName = observation.name.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: "unknown"
            val safeCode = observation.code.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "tool_error"
            results.putString("${prefix}_tool_${toolIndex + 1}", "$safeName:${observation.status.name.lowercase()}:$safeCode")
        }
        if (result.status in setOf(AgentRunResultStatus.COMPLETED, AgentRunResultStatus.PARTIAL,
                AgentRunResultStatus.AWAITING_CLARIFICATION) && !result.answer.isNullOrBlank())
            conversations.appendTurn(scope, turnId, utterance, requireNotNull(result.answer), System.currentTimeMillis())
        check(result.status == AgentRunResultStatus.COMPLETED && !result.answer.isNullOrBlank()) {
            "synthetic_${language}_step_${index + 1}_turn_failed_${result.code ?: result.status.name.lowercase()}"
        }
        expectedTools.forEach { expectedTool ->
            check(result.toolResults.any { it.name == expectedTool && it.status == ToolObservationStatus.APPLIED }) {
                val observation = result.toolResults.lastOrNull { it.name == expectedTool }
                "synthetic_${language}_step_${index + 1}_${expectedTool}_${observation?.status?.name?.lowercase() ?: "not_selected"}"
            }
        }
        return result
    }

    private suspend fun verifyFinanceState(repository: ProtectedDomainRepository, scope: String, expectedReceipts: List<Long>,
        expectedApplied: Long, expectedRemaining: Long, code: String) {
        val people = repository.financePeople(scope)
        check(people.size == 1) { code }
        val history = repository.financeHistory(scope, people.single().displayName) ?: error(code)
        val active = history.events.filter { it.eventId !in history.voidedEventIds }
        val receipts = active.filter { it.kind == "receipt" }
        val debts = active.filter { it.kind == "debt" }
        check(debts.size == 1) { code }
        check(receipts.size == expectedReceipts.size && receipts.map { it.minorUnits }.sorted() == expectedReceipts.sorted()) { code }
        val allocations = repository.financeAdjustments(scope).filter { it.kind == "settlement" }
        check(allocations.size == 1 && allocations.single().obligationId == debts.single().eventId) { code }
        val linkedReceipt = receipts.singleOrNull { it.eventId == allocations.single().reversesEventId } ?: error(code)
        // A previously captured receipt may be linked only after the user later records the debt context.
        // The provenance link, not insertion order, is the invariant for this late-context path.
        check(repository.appliedToDebtMinor(scope, linkedReceipt.eventId) == expectedApplied) { code }
        check(receipts.filter { it.eventId != linkedReceipt.eventId }.all { repository.appliedToDebtMinor(scope, it.eventId) == 0L }) { code }
        check(history.remainingDebtMinorByEvent[debts.single().eventId] == expectedRemaining) { code }
    }

    private fun syntheticUuid(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    private fun safeProbeCode(error: Exception): String {
        val value = error.message.orEmpty()
        return value.takeIf { it.matches(Regex("synthetic_[a-z0-9_]{1,64}")) } ?: when (error) {
            is kotlinx.coroutines.TimeoutCancellationException -> "synthetic_timeout"
            is IllegalArgumentException -> "synthetic_invalid_response"
            else -> "synthetic_e2e_failed"
        }
    }

    private fun safeHttpStatus(error: Exception): String? = generateSequence(error as Throwable?) { it.cause }
        .mapNotNull { it.message }.firstNotNullOfOrNull { Regex("(?<!\\d)(400|401|403|404|408|429|500|502|503|504)(?!\\d)").find(it)?.value }

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
