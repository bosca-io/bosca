package bosca.forms.model

import bosca.profile.model.ProfileVisibility
import kotlinx.serialization.Serializable

/**
 * Defines how form field values are mapped to profile attributes when
 * an anonymous user submits a form. Stored as a structured JSONB column
 * on the form_schemas table.
 *
 * When a public form is submitted without authentication, BoscaForm reads
 * this mapping to construct a [ProfileInput] from the submitted field values,
 * enabling the backend to create a profile for the anonymous submitter.
 */
@Serializable
data class FormSchemaProfileMapping(
    val nameField: String,
    val visibility: ProfileVisibility,
    val attributes: List<FormSchemaProfileMappingAttribute> = emptyList()
)
