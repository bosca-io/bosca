package bosca.backup

import bosca.content.model.ContentStorageReference
import kotlinx.serialization.Serializable

/**
 * Inspection of one saved reference against a selected object manifest. Null [path] means its
 * recorded identity cannot resolve an existing content path. [payload] is set only for a single
 * selected candidate. [status] describes retained integrity and selection, never proof that these
 * bytes belonged to the database view. Pending/unknown upload evidence remains on [reference].
 */
@Serializable
data class PostgresBackupContentInspection(
    val reference: ContentStorageReference,
    val path: String?,
    val status: PostgresBackupContentStatus,
    val payload: BackupObject?,
)
