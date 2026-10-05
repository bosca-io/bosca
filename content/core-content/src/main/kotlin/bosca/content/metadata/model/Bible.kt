package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Bible(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    @ColumnName("system_id")
    val systemId: String,
    val variant: String,
    @ColumnName("default_variant")
    val defaultVariant: Boolean,
    val enabled: Boolean = true,
    val name: String,
    @ColumnName("name_local")
    val nameLocal: String,
    val description: String,
    val abbreviation: String,
    @ColumnName("abbreviation_local")
    val abbreviationLocal: String,
    @Contextual
    val styles: JsonElement
)
