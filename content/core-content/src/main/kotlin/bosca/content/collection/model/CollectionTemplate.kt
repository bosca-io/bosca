package bosca.content.collection.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@BatchKey(type = CollectionTemplateCacheKeyId::class)
@Serializable
data class CollectionTemplate(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    val version: Int,
    @Contextual
    val configuration: JsonElement?,
    @Contextual
    @ColumnName("default_attributes")
    val defaultAttributes: JsonElement?,
    @Contextual
    val filters: JsonElement = JsonObject(emptyMap()),
    @Contextual
    val ordering: JsonElement?,
)