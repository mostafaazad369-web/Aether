package studio.cluvex.aether.data

import android.content.Context
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecretStoreTest {

    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: SecretStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("aether_secrets", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = SecretStore(context)
    }

    @Test
    fun missingSecretIsReportedAsMissing() {
        assertEquals(SecretStore.ReadResult.Missing, store.readResult(SecretStore.ACCESS_TOKEN))
    }

    @Test
    fun malformedBase64IsReportedAsCorruptAndPreserved() {
        preferences.edit().putString(SecretStore.ACCESS_SECRET, "%%%").commit()

        assertTrue(store.readResult(SecretStore.ACCESS_SECRET) is SecretStore.ReadResult.CorruptData)
        assertEquals("%%%", preferences.getString(SecretStore.ACCESS_SECRET, null))
    }

    @Test
    fun truncatedCiphertextIsReportedAsCorruptAndPreserved() {
        val encoded = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)
        preferences.edit().putString(SecretStore.ACCESS_TOKEN, encoded).commit()

        assertTrue(store.readResult(SecretStore.ACCESS_TOKEN) is SecretStore.ReadResult.CorruptData)
        assertEquals(encoded, preferences.getString(SecretStore.ACCESS_TOKEN, null))
    }

    @Test
    fun writeDoesNotOverwriteUnreadableCiphertext() {
        val encoded = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)
        preferences.edit().putString(SecretStore.GEMINI_KEY, encoded).commit()

        assertTrue(store.writeResult(SecretStore.GEMINI_KEY, "replacement") is SecretStore.WriteResult.CorruptData)
        assertEquals(encoded, preferences.getString(SecretStore.GEMINI_KEY, null))
    }

    @Test
    fun clearResultRemovesCorruptCiphertextExplicitly() {
        preferences.edit().putString(SecretStore.ACCESS_SECRET, "%%%").commit()

        assertEquals(SecretStore.WriteResult.Cleared, store.clearResult(SecretStore.ACCESS_SECRET))
        assertEquals(SecretStore.ReadResult.Missing, store.readResult(SecretStore.ACCESS_SECRET))
    }

    @Test
    fun clearAllResultRemovesEveryStoredSecret() {
        preferences.edit()
            .putString(SecretStore.ACCESS_SECRET, "one")
            .putString(SecretStore.ACCESS_TOKEN, "two")
            .commit()

        assertEquals(SecretStore.WriteResult.Cleared, store.clearAllResult())
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun blankWriteUsesExplicitClearResult() {
        preferences.edit().putString(SecretStore.GEMINI_KEY, "%%%").commit()

        assertEquals(SecretStore.WriteResult.Cleared, store.writeResult(SecretStore.GEMINI_KEY, "  "))
        assertEquals(SecretStore.ReadResult.Missing, store.readResult(SecretStore.GEMINI_KEY))
    }
}
