package studio.cluvex.aether.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeminiStoreTest {

    @Test
    fun preferenceWritesAreReportedAndVisibleInSettings() = runBlocking {
        val store = GeminiStore(RuntimeEnvironment.getApplication())

        assertEquals(GeminiStore.OperationResult.Success, store.saveAutoOptimize(true))
        assertEquals(GeminiStore.OperationResult.Success, store.saveAutoApply(true))
        assertEquals(GeminiStore.OperationResult.Success, store.saveShowHints(false))

        val result = store.settings.first()
        assertTrue(result is GeminiStore.SettingsResult.Available)

        val settings = (result as GeminiStore.SettingsResult.Available).settings
        assertTrue(settings.autoOptimize)
        assertTrue(settings.autoApply)
        assertFalse(settings.showHints)
    }
}
