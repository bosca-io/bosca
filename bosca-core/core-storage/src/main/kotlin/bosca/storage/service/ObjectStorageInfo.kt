package bosca.storage.service

import java.time.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A current object's metadata, without reading its payload.
 *
 * [etag] and [version] are opaque provider identifiers, when available. An entity tag is not
 * a SHA-256 digest; neither it nor [modified] establishes immutable source bytes or retention.
 */
@Serializable
data class ObjectStorageInfo(
    val length: Long,
    val etag: String? = null,
    val version: String? = null,
    @Contextual
    val modified: OffsetDateTime? = null,
) {
    init {
        require(length >= 0) { "Object length must not be negative" }
    }
}
