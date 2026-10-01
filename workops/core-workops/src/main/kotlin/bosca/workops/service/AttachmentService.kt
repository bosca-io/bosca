package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.attachment.Attachment
import bosca.workops.model.attachment.PresignedUpload

interface AttachmentService : Service {

    /**
     * R16 — produces a presigned upload destination plus a
     * confirmation token the client redeems through
     * [confirmUpload]. Throws [WorkOpsValidationException] when
     * the size or content type fails the project's policy.
     */
    suspend fun requestUpload(
        taskId: UUID,
        profileId: UUID,
        filename: String,
        contentType: String,
        sizeBytes: Long,
    ): PresignedUpload

    /**
     * Verifies the HMAC token, looks up the (cached) descriptor
     * the request stamped, and writes the attachment row. The
     * `core-storage` registration happens elsewhere and lands the
     * `storage_object_id` on the row.
     */
    suspend fun confirmUpload(token: String, description: String?): Attachment

    suspend fun listForTask(taskId: UUID): List<Attachment>

    /**
     * Retrieves a single non-deleted attachment by its primary key,
     * returning `null` when no matching row exists.
     */
    suspend fun getById(id: UUID): Attachment?

    suspend fun delete(id: UUID): Boolean
}

/**
 * HMAC-based signing surface for attachment confirmation tokens,
 * including an in-memory descriptor cache so the `confirmUpload`
 * path can recover the metadata that produced the token without
 * re-querying the storage system.
 */
interface AttachmentSigningSecret : Service {

    /** Produces an HMAC-SHA256 hex signature of [payload]. */
    fun sign(payload: String): String

    /** Stashes [descriptor] keyed by [token] for later retrieval via [verify]. */
    fun remember(token: String, descriptor: AttachmentDescriptor)

    /** Removes and returns the descriptor previously stashed for [token], or `null` if absent or expired. */
    fun verify(token: String): AttachmentDescriptor?
}

data class AttachmentDescriptor(
    val taskId: UUID,
    val profileId: UUID,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val storageObjectId: UUID,
    val expiresAt: OffsetDateTime,
)
