package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.documents.Content
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class DocumentTemplate(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    val schema: JsonElement? = null,
    @Contextual
    val content: Content? = null,
    @Contextual
    @ColumnName("default_attributes")
    val defaultAttributes: JsonElement? = null
)