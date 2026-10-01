package bosca.content.metadata.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Tracks the lifecycle of content sourced from an external URL.
 *
 * When metadata is created via an import operation, this status reflects
 * whether the external content has been downloaded into local storage,
 * is still in transit, or remains at the original location for on-demand access.
 */
@DbMapper(SourceStatusMapper::class)
@Serializable
enum class SourceStatus {
    PENDING,
    IMPORTING,
    IMPORTED,
    FAILED,
    EXTERNAL
}

object SourceStatusMapper : EnumMapper<SourceStatus>({ SourceStatus.valueOf(it.uppercase()) })
