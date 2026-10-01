package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Tracks a Git LFS object (large file) stored in Bosca's ObjectStorage.
 * The [oid] is the SHA-256 hash of the file content, which is the unique
 * identifier in the LFS protocol.
 */
@Serializable
data class LfsObject(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val oid: String,
    val size: Long,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("storage_path") val storagePath: String = "git-lfs/$repositoryId/$oid",
)
