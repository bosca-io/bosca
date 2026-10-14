package bosca.content.metadata.model

import bosca.bible.IBibleLanguage
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
    override val iso: String,
    override val name: String,
    @ColumnName("name_local")
    override val nameLocal: String,
    override val script: String,
    @ColumnName("script_code")
    override val scriptCode: String,
    @ColumnName("script_direction")
    override val scriptDirection: String,
    val sort: Int
) : IBibleLanguage
