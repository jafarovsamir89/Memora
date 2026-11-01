package ai.memora.app.agent

import java.security.MessageDigest

/** Contract v1 reference; not an authorization check and not connected to the app runtime. */
object AgentCanonical {
    fun bytes(value: Any?): ByteArray = buildString { appendValue(value) }.toByteArray(Charsets.UTF_8)

    fun commandDigest(envelope: Map<String, Any?>): String {
        val payload = envelope.filterKeys { it != "payloadDigest" && it != "confirmationId" }
        return sha256(payload)
    }

    /** Hashes the proposed deletion plan; does not validate completeness or authorize deletion. */
    fun deletionPreviewDigest(preview: Map<String, Any?>): String =
        sha256(preview.filterKeys { it != "planDigest" })

    private fun sha256(value: Any?): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes(value))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is String -> appendString(value)
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item -> if (index > 0) append(','); appendValue(item) }
                append(']')
            }
            is Map<*, *> -> {
                require(value.keys.all { it is String && it.all { ch -> ch.code < 128 } }) { "Object keys must be ASCII strings" }
                val keys = value.keys.map { it as String }.sorted()
                append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendString(key); append(':'); appendValue(value[key])
                }
                append('}')
            }
            else -> throw IllegalArgumentException("Unsupported contract value")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val c = value[index]
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> when {
                    c.code < 32 -> append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                    Character.isHighSurrogate(c) -> {
                        require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) { "Unpaired surrogate" }
                        append(c); append(value[++index])
                    }
                    Character.isLowSurrogate(c) -> throw IllegalArgumentException("Unpaired surrogate")
                    else -> append(c)
                }
            }
            index++
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}
