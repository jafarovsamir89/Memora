package ai.memora.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

interface AudioNoteGateway {
    suspend fun transcribe(wav: ByteArray): String
    suspend fun suggestWriting(raw: String): String = raw
}

/** Dedicated verbatim STT; existing consent/review remains mandatory before local storage. */
class FirebaseAudioNoteGateway(private val context: Context) : AudioNoteGateway {
    override suspend fun transcribe(wav: ByteArray): String = FirebaseTranscribeTransport(context).transcribe(wav)
    override suspend fun suggestWriting(raw: String): String = TranscriptOrthography().suggest(raw)
}

interface AudioNoteRecorder {
    fun prepare() = Unit
    suspend fun record(): ByteArray
    fun stop()
}

/** At most 25s of mono 16kHz PCM in RAM; no unencrypted temporary audio file. */
class AndroidAudioNoteRecorder : AudioNoteRecorder {
    private val stopping = AtomicBoolean(false)
    override fun prepare() { stopping.set(false) }
    override fun stop() { stopping.set(true) }

    @SuppressLint("MissingPermission") // Caller obtains permission after the separate cloud consent.
    override suspend fun record(): ByteArray = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 6400)).build()
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(3200)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            while (!stopping.get() && bytes.size() < 800_000) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, minOf(buffer.size, 800_000 - bytes.size()))
                check(count > 0) { "Audio recording failed" }
                bytes.write(buffer, 0, count)
            }
            currentCoroutineContext().ensureActive()
            check(bytes.size() >= 3200) { "No audio" }
            pcmToWav(bytes.toByteArray())
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            buffer.fill(0)
            bytes.reset()
        }
    }
}

internal fun pcmToWav(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 800_000)
    return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}
