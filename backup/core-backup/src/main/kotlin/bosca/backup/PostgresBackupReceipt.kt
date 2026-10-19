package bosca.backup

import kotlinx.serialization.Serializable

/** The independently retained database artifact selected by one completed backup request. */
@Serializable
data class PostgresBackupReceipt(
    val key: String,
    val storageKey: String,
    val path: String,
    val manifest: PostgresBackupManifest,
)
