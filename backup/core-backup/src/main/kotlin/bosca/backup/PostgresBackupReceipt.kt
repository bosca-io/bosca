package bosca.backup

import kotlinx.serialization.Serializable

/** Retained standalone database artifact selected by a completed backup request. */
@Serializable
data class PostgresBackupReceipt(
    val key: String,
    val storageKey: String,
    val path: String,
    val manifest: PostgresBackupManifest,
)
