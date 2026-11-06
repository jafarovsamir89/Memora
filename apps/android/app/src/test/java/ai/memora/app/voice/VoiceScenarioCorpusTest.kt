package ai.memora.app.voice

import ai.memora.app.agent.AgentToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Structural and safety check for a text-only evaluation corpus, not an STT/model accuracy claim. */
class VoiceScenarioCorpusTest {
    private val scenarios: List<Map<String, kotlinx.serialization.json.JsonElement>> by lazy {
        val corpus = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "docs/voice-evaluation/voice-scenarios-v1.jsonl") }
            .firstOrNull(File::isFile)
        val input = requireNotNull(corpus) { "voice_scenario_corpus_not_found" }
        input.inputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()
        }
    }

    @Test fun corpusContainsBalancedLanguagesCategoriesAndHeldOutSplit() {
        assertEquals(1_000, scenarios.size)
        assertEquals(1_000, scenarios.map { it.getValue("scenario_id").jsonPrimitive.content }.toSet().size)
        assertEquals(1_000, scenarios.map { it.getValue("utterance").jsonPrimitive.content }.toSet().size)
        assertEquals(mapOf("az" to 250, "ru" to 250, "en" to 250, "mixed" to 250),
            scenarios.groupingBy { it.getValue("language_group").jsonPrimitive.content }.eachCount())
        assertEquals(1_000, scenarios.count { it.getValue("schema_version").jsonPrimitive.content == "1" })
        assertEquals(800, scenarios.count { it.getValue("split").jsonPrimitive.content == "development" })
        assertEquals(200, scenarios.count { it.getValue("split").jsonPrimitive.content == "holdout" })
        assertEquals(mapOf(
            "chat_no_write" to 100, "note_save" to 100, "receipt" to 100, "expense" to 100,
            "debt_direction_ambiguous" to 100, "task_create" to 100, "calendar_create" to 100,
            "memory_search" to 100, "negated_transaction" to 100, "ambiguous_transaction" to 100,
        ), scenarios.groupingBy { it.getValue("category").jsonPrimitive.content }.eachCount())
        scenarios.groupBy {
            it.getValue("language_group").jsonPrimitive.content to it.getValue("category").jsonPrimitive.content
        }.values.forEach { cell ->
            assertEquals(25, cell.size)
            assertEquals(5, cell.count { it.getValue("split").jsonPrimitive.content == "holdout" })
        }
    }

    @Test fun everyCaseHasGroundTruthMetadataAndKnownAllowedToolContracts() {
        val knownTools = AgentToolCatalog.all().map { it.name }.toSet()
        scenarios.forEach { scenario ->
            val id = scenario.getValue("scenario_id").jsonPrimitive.content
            assertTrue(id.matches(Regex("VOICE-(AZ|RU|EN|MIXED)-[A-Z_]+-[0-9]{3}")))
            assertEquals("true", scenario.getValue("synthetic").jsonPrimitive.content)
            assertNull(scenario.getValue("audio_asset")?.jsonPrimitive?.contentOrNull)
            assertEquals(scenario.getValue("utterance").jsonPrimitive.content,
                scenario.getValue("reference_transcript").jsonPrimitive.content)
            assertTrue(scenario.getValue("utterance").jsonPrimitive.content.length <= 500)
            val tools = scenario.getValue("expected_tools").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("unknown tool in $id", knownTools.containsAll(tools))
            val action = scenario.getValue("expected_action").jsonPrimitive.content
            if (action in setOf("clarify_no_write", "no_write", "answer_only")) {
                assertTrue("unsafe expected write in $id", scenario.getValue("must_not_write").jsonPrimitive.content.toBoolean())
                assertTrue("unexpected tool in no-write case $id", tools.isEmpty())
            }
            val slots = scenario.getValue("expected_slots").jsonObject
            slots["amount"]?.let { amount ->
                val minorUnits = BigDecimal(amount.jsonPrimitive.content).movePointRight(2).longValueExact()
                assertTrue("non-positive amount in $id", minorUnits > 0)
            }
            slots["occurred_date"]?.let { LocalDate.parse(it.jsonPrimitive.content) }
            slots["due_local"]?.let { LocalDateTime.parse(it.jsonPrimitive.content) }
            slots["start_local"]?.let { LocalDateTime.parse(it.jsonPrimitive.content) }
        }
    }

    @Test fun eachScriptExercisesTheProductionLocaleAndTranscriptBuffer() {
        val expectedLocale = mapOf("az" to "az-AZ", "ru" to "ru-RU", "en" to "en-US", "mixed" to "az-AZ")
        scenarios.forEach { scenario ->
            val language = scenario.getValue("language_group").jsonPrimitive.content
            val utterance = scenario.getValue("utterance").jsonPrimitive.content
            assertEquals(expectedLocale.getValue(language), scenario.getValue("locale").jsonPrimitive.content)
            val tag = when (language) { "az", "mixed" -> "az"; "ru" -> "ru"; else -> "en" }
            assertEquals(expectedLocale.getValue(language), VoiceLanguagePolicy.locale(tag).toLanguageTag())
            val transcript = VoiceTranscriptBuffer(limit = 1_000)
            transcript.append(utterance, null)
            assertEquals(utterance, transcript.input)
            assertTrue(transcript.output.isEmpty())
            assertFalse(transcript.overflow)
            assertNotNull(scenario["category"])
        }
    }
}
