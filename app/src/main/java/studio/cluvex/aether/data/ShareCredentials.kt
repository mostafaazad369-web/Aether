package studio.cluvex.aether.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import studio.cluvex.aether.core.LanGuard
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.ShareLeakGuard

/**
 * Encrypted VPN-sharing credentials. Username/password are stored together as
 * one authenticated SecretStore value so a partial two-key update cannot leave
 * a mismatched credential pair behind.
 */
object ShareCredentials {
    const val USER = ShareBridge.DEFAULT_USER
    const val MIN_PASSWORD = 8

    sealed interface EnsureResult {
        data object Ready : EnsureResult
        data object StorageFailure : EnsureResult
    }

    enum class Validation { OK, BAD_USER, SHORT_PASSWORD, STORAGE_FAILURE }
    enum class OperationResult { SUCCESS, STORAGE_FAILURE }

    fun ensure(context: Context): EnsureResult {
        ShareBridge.attach(context)
        val prefs = prefs(context)
        val modeName = prefs.getString(PREF_MODE, null)
        val mode = ShareLeakGuard.Mode.entries.firstOrNull { it.name == modeName }
            ?: ShareLeakGuard.Mode.HOME_WIFI
        val store = SecretStore(context)

        val credentials = when (val result = store.readResult(KEY_CREDENTIALS)) {
            is SecretStore.ReadResult.Value -> decode(result.value) ?: return EnsureResult.StorageFailure
            SecretStore.ReadResult.Missing -> {
                val legacyUser = when (val value = store.readResult(KEY_USER)) {
                    SecretStore.ReadResult.Missing -> USER
                    is SecretStore.ReadResult.Value -> value.value.takeIf { it.isNotBlank() }
                        ?: return EnsureResult.StorageFailure
                    else -> return EnsureResult.StorageFailure
                }
                val legacyPassword = when (val value = store.readResult(KEY_PASSWORD)) {
                    SecretStore.ReadResult.Missing -> LanGuard.randomPassword()
                    is SecretStore.ReadResult.Value -> value.value.takeIf { it.isNotBlank() }
                        ?: return EnsureResult.StorageFailure
                    else -> return EnsureResult.StorageFailure
                }
                Credentials(legacyUser, legacyPassword).also {
                    if (store.writeResult(KEY_CREDENTIALS, encode(it)) != SecretStore.WriteResult.Saved) {
                        return EnsureResult.StorageFailure
                    }
                    // The new pair is durable before legacy values are removed.
                    if (store.clearResult(KEY_USER) != SecretStore.WriteResult.Cleared ||
                        store.clearResult(KEY_PASSWORD) != SecretStore.WriteResult.Cleared
                    ) return EnsureResult.StorageFailure
                }
            }
            else -> return EnsureResult.StorageFailure
        }

        ShareBridge.setMode(mode)
        ShareBridge.setCredentials(credentials.user, credentials.password)
        ShareBridge.setAuthRequired(prefs.getBoolean(PREF_AUTH, false))
        return EnsureResult.Ready
    }

    fun setMode(context: Context, mode: ShareLeakGuard.Mode) {
        prefs(context).edit().putString(PREF_MODE, mode.name).apply()
        ShareBridge.setMode(mode)
    }

    fun setAuthRequired(context: Context, required: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTH, required).apply()
        ShareBridge.setAuthRequired(required)
    }

    fun setCustom(context: Context, user: String, password: String): Validation {
        val u = user.trim()
        if (u.isEmpty() || u.length > 64 || u.any { it == ':' || it.code !in 0x21..0x7E }) {
            return Validation.BAD_USER
        }
        if (password.length < MIN_PASSWORD || password.length > 128 ||
            password.any { it.code !in 0x20..0x7E }
        ) return Validation.SHORT_PASSWORD

        val credentials = Credentials(u, password)
        if (SecretStore(context).writeResult(KEY_CREDENTIALS, encode(credentials)) != SecretStore.WriteResult.Saved) {
            return Validation.STORAGE_FAILURE
        }
        ShareBridge.setCredentials(u, password)
        return Validation.OK
    }

    fun rotate(context: Context): OperationResult {
        val store = SecretStore(context)
        val current = when (val result = store.readResult(KEY_CREDENTIALS)) {
            is SecretStore.ReadResult.Value -> decode(result.value) ?: return OperationResult.STORAGE_FAILURE
            else -> return OperationResult.STORAGE_FAILURE
        }
        val fresh = current.copy(password = LanGuard.randomPassword())
        if (store.writeResult(KEY_CREDENTIALS, encode(fresh)) != SecretStore.WriteResult.Saved) {
            return OperationResult.STORAGE_FAILURE
        }
        ShareBridge.setCredentials(fresh.user, fresh.password)
        return OperationResult.SUCCESS
    }

    private data class Credentials(val user: String, val password: String)

    private fun encode(value: Credentials): String = JSONObject()
        .put("user", value.user)
        .put("password", value.password)
        .toString()

    private fun decode(value: String): Credentials? = runCatching {
        val json = JSONObject(value)
        val user = json.getString("user")
        val password = json.getString("password")
        require(user.isNotBlank() && password.isNotBlank())
        Credentials(user, password)
    }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private const val KEY_CREDENTIALS = "share_proxy_credentials_v2"
    private const val KEY_PASSWORD = "share_proxy_password"
    private const val KEY_USER = "share_proxy_user"
    private const val PREFS_FILE = "aether_share"
    private const val PREF_MODE = "mode"
    private const val PREF_AUTH = "auth_required"
}
