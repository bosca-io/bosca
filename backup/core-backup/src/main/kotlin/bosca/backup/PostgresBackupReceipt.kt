package bosca.backup

import kotlinx.serialization.Serializable

/**
 * Retained native artifact selected by a completed request, with an optional content evidence
 * report location. Request completion does not declare complete database/object recovery coverage.
 */
@Serializable
data class PostgresBackupReceipt(
    val key: String,
    val storageKey: String,
    val path: String,
    val manifest: PostgresBackupManifest,
    val contentReportPath: String? = null,
)
