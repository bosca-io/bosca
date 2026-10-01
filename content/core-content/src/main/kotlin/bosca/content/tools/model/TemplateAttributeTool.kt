package bosca.content.tools.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TemplateAttributeTool(
    @Contextual
    val id: UUID,
    val key: String,
    val name: String,
    val description: String? = null,
    val query: String,
    @ColumnName("result_path")
    val resultPath: String? = null,
    val configuration: JsonElement? = null,
)
