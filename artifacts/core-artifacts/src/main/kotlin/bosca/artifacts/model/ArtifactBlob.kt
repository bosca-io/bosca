package bosca.artifacts.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Metadata for a content-addressable blob stored in the artifact registry.
 *
 * Blobs are identified by their cryptographic digest (e.g., `sha256:abcdef...`) and
 * stored in [ObjectStorageService] at either a digest-derived path or the explicit
 * [storagePath] where a multipart upload was completed. The [refCount] tracks how many
 * artifact versions reference this blob, enabling safe garbage collection when no versions remain.
 */
@Serializable
data class ArtifactBlob(
    /** Content digest in `algorithm:hex` format (e.g., `sha256:abcdef1234567890...`). */
    val digest: String,
    /** Size of the blob in bytes. */
    val size: Long,
    /** Number of artifact versions currently referencing this blob. */
    @ColumnName("ref_count")
    val refCount: Int = 0,
    /** Explicit object-storage path for uploads completed in place, or `null` for the digest-derived path. */
    @ColumnName("storage_path")
    val storagePath: String? = null,
    /** Timestamp when this blob was first stored. */
    @Contextual
    val created: OffsetDateTime? = null,
)
