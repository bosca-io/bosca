package bosca.forms.model

import kotlinx.serialization.Serializable

/**
 * Maps a single form field value to a profile attribute. When the form is
 * submitted anonymously, the value of [field] in the submitted data becomes
 * the [attributeKey] entry inside a profile attribute of type [typeId].
 *
 * For example, mapping the "email" form field to attribute type
 * "bosca.profiles.email" with attributeKey "email" produces:
 * `{ typeId: "bosca.profiles.email", attributes: { "email": "<submitted value>" } }`
 */
@Serializable
data class FormSchemaProfileMappingAttribute(
    val typeId: String,
    val field: String,
    val attributeKey: String
)
