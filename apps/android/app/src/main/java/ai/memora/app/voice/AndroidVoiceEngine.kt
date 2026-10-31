package ai.memora.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Uses only Android's on-device recognizer; caller invokes methods on the main thread. */
class AndroidVoiceEngine(private val context: Context) : VoiceEngine {
    private var recognizer: SpeechRecognizer? = null

    override fun available(): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(language: String, listener: VoiceEngine.Listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw UnsupportedOperationException("On-device speech requires Android 12")
        val instance = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = instance
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.ready()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.ended()
            override fun onError(error: Int) = listener.error()
            override fun onResults(results: Bundle?) {
                listener.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        instance.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() {
        val current = recognizer
        recognizer = null
        if (current != null) try { current.cancel() } finally { current.destroy() }
    }
}
