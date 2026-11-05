package ai.memora.app.data.protectedstore

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TableTransferReaderTest {
    @Test fun readerAcceptsBoundedUtf8Json() {
        assertEquals(TableTransferRead.Ready("{\"name\":\"Əli\"}"),
            TableTransferReader.read(ByteArrayInputStream("{\"name\":\"Əli\"}".toByteArray(Charsets.UTF_8))))
    }

    @Test fun readerRefusesOversizedAndMalformedUtf8Documents() {
        assertEquals(TableTransferRead.TooLarge,
            TableTransferReader.read(ByteArrayInputStream(ByteArray(TableTransferReader.MAX_BYTES + 1))))
        assertEquals(TableTransferRead.InvalidEncoding,
            TableTransferReader.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))))
    }
}
