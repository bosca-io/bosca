package bosca.pipelines.repository

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The persisted row for a named shape — [fields] is the shape's typed fields as a `jsonb` array. */
@Serializable
data class PipelineShapeRecord(
    val name: String,
    @Contextual
    val fields: JsonElement,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
)
