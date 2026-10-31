package ai.memora.app.ai

import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import android.content.Context
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend

interface AssistantGateway {
    suspend fun answer(text: String): String
}

/** This gateway has no access to stored memories, tasks or tools. */
class FirebaseAssistantGateway(private val context: Context) : AssistantGateway {
    override suspend fun answer(text: String): String {
        check(FirebaseApp.getApps(context).isNotEmpty()) { "Firebase is not configured" }
        val result = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-3.1-flash-lite")
            .generateContent(text)
        return result.text?.takeIf { it.isNotBlank() } ?: error("Empty response")
    }
}
