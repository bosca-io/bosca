package bosca.content.metadata.model

import bosca.content.supplementary.SupplementaryIdObject
import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

@Serializable
data class MetadataSupplementary(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val key: String,
    val name: String,
    @ColumnName("plan_id")
    @Contextual
    val planId: UUID? = null,
    @ColumnName("job_id")
    @Contextual
    val jobId: UUID? = null,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime,
    @Contextual
    val modified: OffsetDateTime,
    @Contextual
    val uploaded: OffsetDateTime? = null,
    @ColumnName("content_type")
    val contentType: String? = null,
    @ColumnName("content_length")
    val contentLength: Long? = null,
    @ColumnName("source_id")
    val sourceId: UUID? = null,
    @ColumnName("source_identifier")
    val sourceIdentifier: String? = null,
) {

    fun toId() = SupplementaryIdObject(
        contentId = metadataId,
        id = id,
        key = key,
        planId = planId,
        jobId = jobId
    )
}