package ai.memora.app.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureImportReaderTest {
    @Test fun validUtf8IncludingAzerbaijaniIsPreserved() {
        val text = "Salam, dünya 🌍"
        assertEquals(CaptureFileRead.Ready(text), CaptureImportReader.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test fun invalidUtf8IsRejectedRatherThanReplaced() {
        assertEquals(CaptureFileRead.InvalidEncoding, CaptureImportReader.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))))
    }

    @Test fun readLimitIsEnforcedAtBoundary() {
        assertTrue(CaptureImportReader.read(ByteArrayInputStream(ByteArray(CaptureImportReader.MAX_BYTES) { 0x61 })) is CaptureFileRead.Ready)
        assertEquals(CaptureFileRead.TooLarge, CaptureImportReader.read(ByteArrayInputStream(ByteArray(CaptureImportReader.MAX_BYTES + 1) { 0x61 })))
    }

    @Test fun brokenOrZeroProgressStreamFailsWithoutLooping() {
        val failing = object : InputStream() { override fun read(): Int = throw IOException("synthetic") }
        val stalled = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertEquals(CaptureFileRead.Unavailable, CaptureImportReader.read(failing))
        assertEquals(CaptureFileRead.Unavailable, CaptureImportReader.read(stalled))
    }
}
