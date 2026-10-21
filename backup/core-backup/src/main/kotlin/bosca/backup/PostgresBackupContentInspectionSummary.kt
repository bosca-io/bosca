package bosca.backup

import kotlinx.serialization.Serializable

/** Counts reference outcomes for these exact artifacts; this is not a complete recovery-set receipt. */
@Serializable
data class PostgresBackupContentInspectionSummary(
    val databaseId: String,
    val objectManifestId: String,
    val store: String,
    val counts: Map<PostgresBackupContentStatus, Long>,
)
