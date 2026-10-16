package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * A private FUMS usage token from one provider chapter response. It is retained until its
 * original expiry, independently of the chapter text or recording it arrived with.
 */
data class BibleFumsToken(
    @ColumnName("metadata_id") val metadataId: UUID,
    val version: Int,
    val variant: String,
    val usfm: String,
    @ColumnName("source_id") val sourceId: UUID,
    val audio: Boolean,
    val token: String,
    val created: OffsetDateTime,
    val expires: OffsetDateTime,
) {
    override fun toString(): String = "BibleFumsToken(metadataId=$metadataId, variant=$variant, usfm=$usfm, sourceId=$sourceId)"
}
