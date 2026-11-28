package studio.cluvex.aether.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM secret vault backed by a non-exportable Android Keystore key.
 *
 * Hardware backing is device-dependent. This protects ciphertext at rest; it
 * does not protect plaintext while in use or guarantee safety on a compromised
 * running device.
 *
 * Layout: Base64( iv(12) || ciphertext||tag ).
 */
class SecretStore(context: Context) {

    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Value(val value: String) : ReadResult
        data class KeyUnavailable(val cause: Throwable? = null) : ReadResult
        data class CorruptData(val cause: Throwable? = null) : ReadResult
        data class StorageFailure(val cause: Throwable? = null) : ReadResult
    }

    sealed interface WriteResult {
        data object Saved : WriteResult
        data object Cleared : WriteResult
        data class KeyUnavailable(val cause: Throwable? = null) : WriteResult
        data class CorruptData(val cause: Throwable? = null) : WriteResult
        data class StorageFailure(val cause: Throwable? = null) : WriteResult
    }

    private val prefs =
        context.applicationContext.getSharedPreferences("aether_secrets", Context.MODE_PRIVATE)

    /**
     * Reads a value without creating a replacement key.
     *
     * If ciphertext exists but its original Keystore key is missing or
     * inaccessible, the ciphertext is retained and KeyUnavailable is returned.
     * Authentication/format failures are reported as CorruptData and are never
     * silently deleted.
     */
    fun readResult(name: String): ReadResult {
        val stored = try {
            prefs.getString(name, null)
        } catch (t: Throwable) {
            return ReadResult.StorageFailure(t)
        } ?: return ReadResult.Missing

        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (t: IllegalArgumentException) {
            return ReadResult.CorruptData(t)
        } catch (t: Throwable) {
            return ReadResult.StorageFailure(t)
        }
        if (blob.size <= IV_LEN + TAG_LEN_BYTES) return ReadResult.CorruptData()

        val secret = try {
            existingKey()
        } catch (t: Throwable) {
            return ReadResult.KeyUnavailable(t)
        } ?: return ReadResult.KeyUnavailable()

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)),
            )
            ReadResult.Value(String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8))
        } catch (t: AEADBadTagException) {
            ReadResult.CorruptData(t)
        } catch (t: java.security.GeneralSecurityException) {
            ReadResult.KeyUnavailable(t)
        } catch (t: Throwable) {
            ReadResult.StorageFailure(t)
        }
    }

    /**
     * Encrypts and commits [value]. Existing unreadable ciphertext is never
     * overwritten: callers must resolve the storage error explicitly first.
     */
    fun writeResult(name: String, value: String): WriteResult {
        if (value.isBlank()) return clearResult(name)

        when (val existing = readResult(name)) {
            ReadResult.Missing, is ReadResult.Value -> Unit
            is ReadResult.KeyUnavailable -> return WriteResult.KeyUnavailable(existing.cause)
            is ReadResult.CorruptData -> return WriteResult.CorruptData(existing.cause)
            is ReadResult.StorageFailure -> return WriteResult.StorageFailure(existing.cause)
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
            val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            if (prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
                WriteResult.Saved
            } else {
                WriteResult.StorageFailure()
            }
        } catch (t: java.security.GeneralSecurityException) {
            WriteResult.KeyUnavailable(t)
        } catch (t: Throwable) {
            WriteResult.StorageFailure(t)
        }
    }

    /** Explicitly removes one value; this is a destructive caller-requested action. */
    fun clearResult(name: String): WriteResult = try {
        if (prefs.edit().remove(name).commit()) WriteResult.Cleared else WriteResult.StorageFailure()
    } catch (t: Throwable) {
        WriteResult.StorageFailure(t)
    }

    /** Explicitly wipes every sealed secret (used by a user-confirmed reset). */
    fun clearAllResult(): WriteResult = try {
        if (prefs.edit().clear().commit()) WriteResult.Cleared else WriteResult.StorageFailure()
    } catch (t: Throwable) {
        WriteResult.StorageFailure(t)
    }

    /**
     * Legacy compatibility API. Migrate callers to [readResult] so missing,
     * corrupt, and unavailable secrets are not conflated.
     */
    @Deprecated("Use readResult and handle every failure state explicitly")
    fun read(name: String): String = (readResult(name) as? ReadResult.Value)?.value.orEmpty()

    /** Legacy compatibility API; new callers must inspect [writeResult]. */
    @Deprecated("Use writeResult and handle its result")
    fun write(name: String, value: String) {
        writeResult(name, value)
    }

    /** Legacy compatibility API; new callers must inspect [clearAllResult]. */
    @Deprecated("Use clearAllResult and handle its result")
    fun clear() {
        clearAllResult()
    }

    /** Existing-key-only lookup. It deliberately never creates a new key. */
    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** Creates a key only when a new value is being written. */
    private fun keyForWrite(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT user-authentication bound: unattended VPN reconnect is required.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ACCESS_SECRET = "access_client_secret"
        const val ACCESS_TOKEN = "access_token"
        const val ACCESS_SIGNIN = "access_signin"

        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "aether_secret_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val TAG_LEN_BYTES = TAG_BITS / 8
    }
}
