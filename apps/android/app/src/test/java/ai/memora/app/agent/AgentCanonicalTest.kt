package ai.memora.app.agent

import org.junit.Assert.*
import org.junit.Test

class AgentCanonicalTest {
    private fun command(): Map<String, Any?> = mapOf(
        "protocolVersion" to 1, "commandId" to "00000000-0000-4000-8000-000000000001",
        "turnId" to "00000000-0000-4000-8000-000000000002", "stepId" to "00000000-0000-4000-8000-000000000003",
        "scopeId" to "00000000-0000-4000-8000-000000000004", "actorId" to "00000000-0000-4000-8000-000000000005",
        "toolName" to "create_note", "toolVersion" to 1,
        "arguments" to mapOf("noteId" to "00000000-0000-4000-8000-000000000006", "noteRevisionId" to "00000000-0000-4000-8000-000000000013", "title" to "Sınaq qeydi", "body" to "Filtri yoxla", "links" to emptyList<Any>()),
        "expectedVersions" to emptyList<Any>(), "sourceRevisionId" to "00000000-0000-4000-8000-000000000007",
        "evidenceRefs" to listOf("00000000-0000-4000-8000-000000000008"), "payloadDigest" to "ignored",
        "permissionSnapshotVersion" to 1, "confirmationId" to null,
    )

    @Test fun matchesIndependentPythonGoldenVector() {
        assertEquals("69657e6421bfe8cd44f32fe6827079efa296aff9df0427edb6f7c46ace3688c0", AgentCanonical.commandDigest(command()))
        assertEquals("{\"a\":\"\\n\",\"b\":\"ç\"}", AgentCanonical.bytes(mapOf("b" to "ç", "a" to "\n")).toString(Charsets.UTF_8))
    }

    @Test fun changingPayloadChangesDigestButConfirmationIsBoundSeparately() {
        val original = command()
        assertNotEquals(AgentCanonical.commandDigest(original), AgentCanonical.commandDigest(original + ("permissionSnapshotVersion" to 2)))
        assertEquals(AgentCanonical.commandDigest(original), AgentCanonical.commandDigest(original + ("confirmationId" to "00000000-0000-4000-8000-000000000009")))
    }

    @Test fun updateNoteMatchesIndependentPythonGoldenVector() {
        val updated = command() + mapOf(
            "commandId" to "00000000-0000-4000-8000-000000000011", "toolName" to "update_note",
            "arguments" to mapOf("noteId" to "00000000-0000-4000-8000-000000000006",
                "newRevisionId" to "00000000-0000-4000-8000-000000000012", "patch" to mapOf("body" to "Filtri dəyiş")),
            "expectedVersions" to listOf(mapOf("id" to "00000000-0000-4000-8000-000000000006", "version" to 1)),
        )
        assertEquals("b07a13fbe230114fb8ed25661e267e175c91abd55f9c5bc07a1e622698ec0537", AgentCanonical.commandDigest(updated))
    }

    @Test fun deletionPreviewMatchesIndependentPythonGoldenAndBindsEffects() {
        val preview = mapOf<String, Any?>(
            "kind" to "delete_preview", "previewId" to "00000000-0000-4000-8000-000000000030",
            "planDigest" to "ignored", "scopeId" to "00000000-0000-4000-8000-000000000004",
            "sourceRevisionId" to "00000000-0000-4000-8000-000000000007", "mode" to "TRASH",
            "targets" to listOf(mapOf("type" to "note", "id" to "00000000-0000-4000-8000-000000000006", "version" to 2)),
            "dependentLinks" to listOf(mapOf("id" to "00000000-0000-4000-8000-000000000023", "version" to 5)),
        )
        val digest = AgentCanonical.deletionPreviewDigest(preview)
        assertEquals("86db9e682c43bfd5b7974a00981aa6523a32c7ccecd9665a3a8c4130c357c23a", digest)
        assertEquals(digest, AgentCanonical.deletionPreviewDigest(preview + ("planDigest" to "different")))
        assertNotEquals(digest, AgentCanonical.deletionPreviewDigest(preview + ("dependentLinks" to emptyList<Any>())))
        assertNotEquals(digest, AgentCanonical.deletionPreviewDigest(preview + ("scopeId" to "other")))
    }

    @Test fun rejectsFloatingPointNonAsciiKeysAndUnpairedSurrogates() {
        for (bad in listOf(mapOf("x" to 1.0), mapOf("é" to 1), mapOf("x" to "\uD800"))) {
            try { AgentCanonical.bytes(bad); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun serializesControlsAndSupplementaryUnicodeWithoutChangingMeaning() {
        assertEquals("{\"x\":\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\🙂\"}",
            AgentCanonical.bytes(mapOf("x" to "\u0001\b\t\n\u000c\r\"\\🙂")).toString(Charsets.UTF_8))
    }
}
