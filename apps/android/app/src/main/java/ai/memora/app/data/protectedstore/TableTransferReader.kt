package ai.memora.app.data.protectedstore

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal sealed interface TableTransferRead {
    data class Ready(val json: String) : TableTransferRead
    data object TooLarge : TableTransferRead
    data object InvalidEncoding : TableTransferRead
    data object Unavailable : TableTransferRead
}

/** Reads one bounded SAF document without trusting the provider's declared size. */
internal object TableTransferReader {
    const val MAX_BYTES = 1_000_000

    fun read(input: InputStream): TableTransferRead {
        return try {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > MAX_BYTES) return TableTransferRead.TooLarge
                bytes.write(buffer, 0, count)
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            TableTransferRead.Ready(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString())
        } catch (_: java.nio.charset.CharacterCodingException) {
            TableTransferRead.InvalidEncoding
        } catch (_: Exception) {
            TableTransferRead.Unavailable
        }
    }
}
