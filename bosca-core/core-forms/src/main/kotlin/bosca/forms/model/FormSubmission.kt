package bosca.forms.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A submitted form entry linked to a profile, containing the
 * attribute values collected from the form and its processing status.
 */
@Serializable
data class FormSubmission(
    @Contextual
    val id: UUID,
    @Contextual
    @ColumnName("form_schema_id")
    val formSchemaId: UUID,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    val attributes: JsonElement,
    val status: FormSubmissionStatus,
    val created: OffsetDateTime,
    val modified: OffsetDateTime
)
