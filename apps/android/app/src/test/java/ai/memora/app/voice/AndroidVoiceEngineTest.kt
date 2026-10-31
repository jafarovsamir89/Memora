package ai.memora.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidVoiceEngineTest {
    @Test fun olderAndroidDoesNotFallBackToNetworkRecognizer() {
        assertFalse(AndroidVoiceEngine(ApplicationProvider.getApplicationContext<Context>()).available())
    }
}
