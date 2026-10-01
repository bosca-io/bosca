package bosca.docs.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiDocumentation(
    @ColumnName("qualified_name")
    val qualifiedName: String,
    @Contextual
    val content: JsonElement,
    @Contextual
    @ColumnName("indexed_at")
    val indexedAt: OffsetDateTime? = null,
)
