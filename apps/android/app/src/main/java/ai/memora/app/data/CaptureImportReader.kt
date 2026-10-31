package ai.memora.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed interface CaptureFileRead {
    data class Ready(val text: String) : CaptureFileRead
    data object TooLarge : CaptureFileRead
    data object InvalidEncoding : CaptureFileRead
    data object Unavailable : CaptureFileRead
}

/** Reads at most one byte beyond the fixed limit; no content is logged. */
object CaptureImportReader {
    const val MAX_BYTES = 2_097_152

    fun read(input: InputStream): CaptureFileRead {
        return try {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - total))
            if (read < 0) break
            if (read == 0) return CaptureFileRead.Unavailable
            total += read
            if (total > MAX_BYTES) return CaptureFileRead.TooLarge
            bytes.write(buffer, 0, read)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        CaptureFileRead.Ready(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString())
        } catch (_: CharacterCodingException) { CaptureFileRead.InvalidEncoding }
        catch (_: Exception) { CaptureFileRead.Unavailable }
    }
}
