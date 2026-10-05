package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class BibleLanguage(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val variant: String,
    val iso: String,
    val name: String,
    @ColumnName("name_local")
    val nameLocal: String,
    val script: String,
    @ColumnName("script_code")
    val scriptCode: String,
    @ColumnName("script_direction")
    val scriptDirection: String,
    val sort: Int
)