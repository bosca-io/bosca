package bosca.forms.model

import bosca.profile.profile.model.ProfileInput
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input for submitting a form. Contains the form schema to submit against,
 * the attribute values, and an optional profile for anonymous submissions.
 * Exactly one of [formSchemaId] or [formSchemaKey] must be provided.
 */
@Serializable
data class FormSubmissionInput(
    @Contextual
    val formSchemaId: UUID? = null,
    val formSchemaKey: String? = null,
    @Contextual
    val attributes: JsonElement,
    val profile: ProfileInput? = null
)
