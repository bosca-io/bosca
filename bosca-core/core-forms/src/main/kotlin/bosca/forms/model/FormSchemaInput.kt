package bosca.forms.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for creating or updating a form schema definition.
 * The key acts as the upsert identifier — if a schema with the
 * same key already exists, it is updated and its version incremented.
 */
@Serializable
data class FormSchemaInput(
    val type: FormSchemaType = FormSchemaType.INTERNAL,
    val key: String,
    val name: String,
    val description: String? = null,
    @Contextual
    val schema: JsonElement,
    @Contextual
    val uiSchema: JsonElement,
    @Contextual
    val configuration: JsonElement? = null,
    val profileMapping: FormSchemaProfileMapping? = null,
    val public: Boolean = false
) {
    init {
        require(key.isNotBlank()) { "Form schema key must not be blank" }
        require(name.isNotBlank()) { "Form schema name must not be blank" }
    }
}
