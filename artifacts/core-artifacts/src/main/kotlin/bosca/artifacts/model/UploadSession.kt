package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Represents the lifecycle state of a chunked blob upload session.
 */
@Serializable
enum class UploadSessionState(val value: String) {
    ACTIVE("active"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    companion object {
        fun fromValue(value: String): UploadSessionState =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown upload session state: '$value'")
    }
}

/**
 * Tracks the state of a Docker chunked blob upload.
 *
 * The OCI Distribution spec requires a multi-step upload flow: POST to initiate,
 * PATCH to send chunks, PUT to finalize with a digest. This entity tracks the
 * accumulated byte offset, object-storage multipart upload, resumable digest state,
 * and session lifecycle.
 */
@Serializable
data class UploadSession(
    val id: UUID,
    @ColumnName("repository_id")
    val repositoryId: UUID,
    val state: UploadSessionState = UploadSessionState.ACTIVE,
    @ColumnName("byte_offset")
    val byteOffset: Long = 0,
    @ColumnName("chunk_count")
    val chunkCount: Int = 0,
    @ColumnName("storage_upload_id")
    val storageUploadId: String? = null,
    @ColumnName("digest_state")
    val digestState: ByteArray? = null,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val expires: OffsetDateTime? = null,
) {
    init {
        require(byteOffset >= 0) { "byteOffset must be non-negative: $byteOffset" }
        require(chunkCount >= 0) { "chunkCount must be non-negative: $chunkCount" }
    }
}
