package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.core.TeamSignInRecord

/** Keystore-backed storage for the pre-connect Zero Trust sign-in. */
class TeamSignInStore(context: Context) {
    private val secrets = SecretStore(context.applicationContext)

    sealed interface LoadResult {
        data object Missing : LoadResult
        data class Record(val value: TeamSignInRecord) : LoadResult
        data class KeyUnavailable(val cause: Throwable? = null) : LoadResult
        data class CorruptData(val cause: Throwable? = null) : LoadResult
        data class StorageFailure(val cause: Throwable? = null) : LoadResult
    }

    fun loadResult(): LoadResult = when (val result = secrets.readResult(SecretStore.ACCESS_SIGNIN)) {
        SecretStore.ReadResult.Missing -> LoadResult.Missing
        is SecretStore.ReadResult.Value -> TeamSignInRecord.decode(result.value)
            ?.let(LoadResult::Record) ?: LoadResult.CorruptData()
        is SecretStore.ReadResult.KeyUnavailable -> LoadResult.KeyUnavailable(result.cause)
        is SecretStore.ReadResult.CorruptData -> LoadResult.CorruptData(result.cause)
        is SecretStore.ReadResult.StorageFailure -> LoadResult.StorageFailure(result.cause)
    }

    @Deprecated("Use loadResult and handle storage failures explicitly")
    fun load(): TeamSignInRecord? = (loadResult() as? LoadResult.Record)?.value

    fun save(record: TeamSignInRecord): SecretStore.WriteResult =
        secrets.writeResult(SecretStore.ACCESS_SIGNIN, record.encode())

    fun clear(): SecretStore.WriteResult = secrets.clearResult(SecretStore.ACCESS_SIGNIN)
}
