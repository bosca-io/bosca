package bosca.forms.service

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmissionInput
import bosca.serialization.UUID

data class SubmittedForm(val id: UUID, val type: FormSchemaType)

/**
 * Extension point for modules that need to react to form submissions
 * of a specific [FormSchemaType]. Each processor handles exactly one
 * type; the forms service delegates purpose-specific submissions to it.
 */
interface FormSubmissionProcessor {

    /** The form schema type this processor handles. */
    val type: FormSchemaType

    /**
     * Processes a submission for the matching [type]. [profileId] identifies its reporter;
     * [principalId] identifies the authenticated caller and is null for anonymous intake.
     * Implementations return the created record, such as a WorkOps task.
     */
    suspend fun process(
        profileId: UUID,
        principalId: UUID?,
        submission: FormSubmissionInput,
        schema: FormSchema,
    ): SubmittedForm
}
