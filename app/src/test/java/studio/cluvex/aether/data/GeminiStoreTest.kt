package studio.cluvex.aether.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    @Test
    fun preferenceWriteFailureIsReportedAsStorageFailure() = runBlocking {
        val failingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences {
                throw IOException("simulated DataStore write failure")
            }
        }
        val store = GeminiStore(RuntimeEnvironment.getApplication(), failingDataStore)

        assertEquals(
            GeminiStore.OperationResult.Failed(GeminiStore.StorageError.STORAGE_FAILURE),
            store.saveAutoOptimize(true),
        )
    }

}
