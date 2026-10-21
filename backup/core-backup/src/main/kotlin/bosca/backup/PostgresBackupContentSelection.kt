package bosca.backup

import bosca.content.model.ContentStorageReference
import bosca.storage.service.ObjectStorageEntry
import kotlinx.serialization.Serializable

/** One saved reference and its source metadata observation, including unresolved/missing evidence. */
@Serializable
data class PostgresBackupContentSelection(
    val reference: ContentStorageReference,
    val path: String?,
    val entry: ObjectStorageEntry?,
    val status: PostgresBackupContentSelectionStatus,
)
