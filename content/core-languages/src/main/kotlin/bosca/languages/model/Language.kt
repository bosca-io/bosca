package bosca.languages.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Language(
    val tag: String,
    val name: String,
    @ColumnName("localname")
    val localName: String,
    val attributes: JsonElement? = null
)