package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class BibleBook(
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val variant: String,
    val usfm: String,
    @ColumnName("name_short")
    val nameShort: String?,
    @ColumnName("name_long")
    val nameLong: String?,
    val abbreviation: String,
    val sort: Int
)