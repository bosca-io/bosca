package bosca.backup

import kotlinx.serialization.Serializable

/** A configured source storage identity and literal key prefix to observe. */
@Serializable
data class ObjectBackupSource(
    val storageKey: String,
    val prefix: String = "",
)
