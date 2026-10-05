package bosca.workops.model.attachment

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("storage_object_id")
    @Contextual
    val storageObjectId: UUID? = null,
    val filename: String,
    @ColumnName("content_type")
    val contentType: String,
    @ColumnName("size_bytes")
    val sizeBytes: Long,
    @ColumnName("thumbnail_storage_object_id")
    @Contextual
    val thumbnailStorageObjectId: UUID? = null,
    @ColumnName("uploaded_by_profile_id")
    @Contextual
    val uploadedByProfileId: UUID,
    @ColumnName("uploaded_at")
    @Contextual
    val uploadedAt: OffsetDateTime = OffsetDateTime.now(),
    val description: String? = null,
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
)

/**
 * R16 — what `requestAttachmentUpload` returns. The client uploads
 * directly to the storage system using the presigned URL, then
 * round-trips the `confirmationToken` back through
 * `confirmAttachmentUpload` to register the workops row.
 */
@Serializable
data class PresignedUpload(
    val uploadUrl: String,
    @Contextual
    val storageObjectId: UUID,
    val confirmationToken: String,
    @Contextual
    val expiresAt: OffsetDateTime,
)
