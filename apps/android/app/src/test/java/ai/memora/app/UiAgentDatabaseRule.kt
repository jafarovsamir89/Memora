package ai.memora.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.AgentDatabase
import org.junit.rules.ExternalResource

/** Only synthetic UI fixtures. SQLCipher encryption/migrations are verified by the device probe. */
class UiAgentDatabaseRule : ExternalResource() {
    internal lateinit var database: AgentDatabase
        private set

    override fun before() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    override fun after() { database.close() }
}
