package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


@Serializable
class DocumentTemplateContainer(
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    val id: String,
    val name: String,
    val description: String,
    @ColumnName("supplementary_key")
    val supplementaryKey: String? = null,
    val sort: Int = 0,
    val type: ContainerType,
    @Contextual
    val tools: JsonElement? = null,
    @Contextual
    val renderers: JsonElement? = null,
    @Contextual
    val filters: JsonElement? = null
)
