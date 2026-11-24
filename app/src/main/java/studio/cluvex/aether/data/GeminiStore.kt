package studio.cluvex.aether.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.ai.AiModelPolicy

/**
 * Its own DataStore, deliberately not [ProfileStore]'s.
 *
 * The connection profile is handed to the engine on every connect and is wiped
 * by "reset all settings". The AI configuration is neither: it is an account
 * credential plus three preferences that have nothing to do with the tunnel, and
 * losing the key because somebody reset their MTU would be infuriating. Separate
 * file, separate lifetime - the same reasoning [OnboardingStore] uses.
 */
private val Context.aiDataStore by preferencesDataStore(name = "aether_ai")

/**
 * Everything the AI layer needs to know about how the user has configured it.
 *
 * [Immutable] for the same reason [studio.cluvex.aether.model.ConnectionProfile]
 * is: without it the Compose compiler infers the class as unstable because of the
 * `List` member, and then it cannot skip a single composable that takes it - so
 * one keystroke in the API-key field would recompose every AI row on the page.
 */
@Immutable
data class AiSettings(
    /** The user's Gemini API key. Read back from [SecretStore], never from prefs. */
    val apiKey: String = "",
    /** Bare model id the user picked, e.g. `gemini-2.0-flash`. Blank = not chosen. */
    val model: String = "",
    /** Model ids the last successful discovery found for THIS key. */
    val discoveredModels: List<String> = emptyList(),
    /** True when [model] was picked by the user, false when the app picked it. */
    val modelUserPicked: Boolean = false,
    /** Analyse the log on every connect - opt-in. */
    val autoOptimize: Boolean = false,
    /** Write a proposal straight into the profile instead of waiting for a tap. */
    val autoApply: Boolean = false,
    /** Show the AI icon next to individual options. */
    val showHints: Boolean = true,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    val effectiveModel: String
        get() = model.ifBlank {
            AiModelPolicy.pickDefaultId(discoveredModels)
                ?: discoveredModels.firstOrNull().orEmpty()
        }
}

/** Persists [AiSettings]; the key itself goes to the Keystore-sealed vault. */
class GeminiStore(private val context: Context) {

    enum class StorageError {
        KEY_UNAVAILABLE,
        CORRUPT_DATA,
        STORAGE_FAILURE,
        MISSING_KEY,
    }

    sealed interface SettingsResult {
        data class Available(val settings: AiSettings) : SettingsResult
        data class Failed(val error: StorageError) : SettingsResult
    }

    sealed interface OperationResult {
        data object Success : OperationResult
        data class Failed(val error: StorageError) : OperationResult
    }

    private object Keys {
        val model = stringPreferencesKey("model")
        val models = stringPreferencesKey("models")
        val modelUserPicked = booleanPreferencesKey("modelUserPicked")
        // New key name: the old key defaulted to ON for existing installs.
        val autoOptimize = booleanPreferencesKey("autoOptimize_v2")
        val autoApply = booleanPreferencesKey("autoApply")
        val showHints = booleanPreferencesKey("showHints")
    }

    private val secrets = SecretStore(context)

    /**
     * A failed Keystore read is a failed settings load, never an empty/missing key.
     * The caller can therefore fail closed instead of attempting an AI request.
     */
    val settings: Flow<SettingsResult> = context.aiDataStore.data
        .map { prefs ->
            when (val key = secrets.readResult(SecretStore.GEMINI_KEY)) {
                SecretStore.ReadResult.Missing -> SettingsResult.Available(
                    prefs.toAiSettings(apiKey = ""),
                )
                is SecretStore.ReadResult.Value -> SettingsResult.Available(
                    prefs.toAiSettings(apiKey = key.value),
                )
                is SecretStore.ReadResult.KeyUnavailable ->
                    SettingsResult.Failed(StorageError.KEY_UNAVAILABLE)
                is SecretStore.ReadResult.CorruptData ->
                    SettingsResult.Failed(StorageError.CORRUPT_DATA)
                is SecretStore.ReadResult.StorageFailure ->
                    SettingsResult.Failed(StorageError.STORAGE_FAILURE)
            }
        }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(SettingsResult.Failed(StorageError.STORAGE_FAILURE))
        }

    /**
     * Save the secret first. No model preference is touched unless the encrypted
     * write/clear succeeds. A changed key invalidates only its model selection/cache.
     */
    suspend fun saveKey(key: String): OperationResult {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return forget()

        val previous = when (val result = secrets.readResult(SecretStore.GEMINI_KEY)) {
            SecretStore.ReadResult.Missing -> ""
            is SecretStore.ReadResult.Value -> result.value
            is SecretStore.ReadResult.KeyUnavailable ->
                return OperationResult.Failed(StorageError.KEY_UNAVAILABLE)
            is SecretStore.ReadResult.CorruptData ->
                return OperationResult.Failed(StorageError.CORRUPT_DATA)
            is SecretStore.ReadResult.StorageFailure ->
                return OperationResult.Failed(StorageError.STORAGE_FAILURE)
        }

        when (val result = secrets.writeResult(SecretStore.GEMINI_KEY, trimmed)) {
            SecretStore.WriteResult.Saved -> Unit
            SecretStore.WriteResult.Cleared -> return OperationResult.Failed(StorageError.STORAGE_FAILURE)
            is SecretStore.WriteResult.KeyUnavailable ->
                return OperationResult.Failed(StorageError.KEY_UNAVAILABLE)
            is SecretStore.WriteResult.CorruptData ->
                return OperationResult.Failed(StorageError.CORRUPT_DATA)
            is SecretStore.WriteResult.StorageFailure ->
                return OperationResult.Failed(StorageError.STORAGE_FAILURE)
        }

        return try {
            if (trimmed != previous) {
                context.aiDataStore.edit { prefs ->
                    prefs[Keys.model] = ""
                    prefs[Keys.models] = ""
                    prefs[Keys.modelUserPicked] = false
                }
            } else {
                // Touch DataStore so settings collectors can observe the secret update.
                context.aiDataStore.edit { prefs ->
                    prefs[Keys.model] = prefs[Keys.model] ?: ""
                }
            }
            OperationResult.Success
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            OperationResult.Failed(StorageError.STORAGE_FAILURE)
        }
    }

    private suspend fun requireReadableKey(): OperationResult? =
        when (val result = secrets.readResult(SecretStore.GEMINI_KEY)) {
            is SecretStore.ReadResult.Value ->
                if (result.value.isNotBlank()) null
                else OperationResult.Failed(StorageError.MISSING_KEY)
            SecretStore.ReadResult.Missing -> OperationResult.Failed(StorageError.MISSING_KEY)
            is SecretStore.ReadResult.KeyUnavailable ->
                OperationResult.Failed(StorageError.KEY_UNAVAILABLE)
            is SecretStore.ReadResult.CorruptData ->
                OperationResult.Failed(StorageError.CORRUPT_DATA)
            is SecretStore.ReadResult.StorageFailure ->
                OperationResult.Failed(StorageError.STORAGE_FAILURE)
        }

    /**
     * @param byUser true when the user tapped the model in the picker; false for
     *   the app's own default pick.
     */
    suspend fun saveModel(model: String, byUser: Boolean = true): OperationResult {
        requireReadableKey()?.let { return it }
        return editPreferences {
            it[Keys.model] = model
            it[Keys.modelUserPicked] = byUser && model.isNotBlank()
        }
    }

    /** Reset the selected model and cached discovery list after a successful key write. */
    suspend fun resetModelChoice(): OperationResult {
        requireReadableKey()?.let { return it }
        return editPreferences { prefs ->
            prefs[Keys.model] = ""
            prefs[Keys.models] = ""
            prefs[Keys.modelUserPicked] = false
        }
    }

    suspend fun saveDiscovered(models: List<String>): OperationResult {
        requireReadableKey()?.let { return it }
        return editPreferences { it[Keys.models] = models.joinToString(",") }
    }

    suspend fun saveAutoOptimize(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.autoOptimize] = enabled }
    }

    suspend fun saveAutoApply(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.autoApply] = enabled }
    }

    suspend fun saveShowHints(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.showHints] = enabled }
    }

    /**
     * The key is cleared first. Model preferences are erased only after the
     * encrypted vault confirms the clear, so a failed clear preserves the config.
     */
    suspend fun forget(): OperationResult {
        when (val result = secrets.clearResult(SecretStore.GEMINI_KEY)) {
            SecretStore.WriteResult.Cleared -> Unit
            SecretStore.WriteResult.Saved -> return OperationResult.Failed(StorageError.STORAGE_FAILURE)
            is SecretStore.WriteResult.KeyUnavailable ->
                return OperationResult.Failed(StorageError.KEY_UNAVAILABLE)
            is SecretStore.WriteResult.CorruptData ->
                return OperationResult.Failed(StorageError.CORRUPT_DATA)
            is SecretStore.WriteResult.StorageFailure ->
                return OperationResult.Failed(StorageError.STORAGE_FAILURE)
        }
        return try {
            context.aiDataStore.edit { prefs ->
                prefs[Keys.model] = ""
                prefs[Keys.models] = ""
                prefs[Keys.modelUserPicked] = false
            }
            OperationResult.Success
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            OperationResult.Failed(StorageError.STORAGE_FAILURE)
        }
    }

    private suspend fun editPreferences(
        block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ): OperationResult = try {
        context.aiDataStore.edit(block)
        OperationResult.Success
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        OperationResult.Failed(StorageError.STORAGE_FAILURE)
    }

    private fun androidx.datastore.preferences.core.Preferences.toAiSettings(
        apiKey: String,
    ) = AiSettings(
        apiKey = apiKey,
        model = this[Keys.model] ?: "",
        discoveredModels = this[Keys.models]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        modelUserPicked = this[Keys.modelUserPicked] ?: false,
        autoOptimize = this[Keys.autoOptimize] ?: false,
        autoApply = this[Keys.autoApply] ?: false,
        showHints = this[Keys.showHints] ?: true,
    )
}
