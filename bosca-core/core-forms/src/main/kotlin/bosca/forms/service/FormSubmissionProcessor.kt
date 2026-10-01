package bosca.forms.service

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionInput
import bosca.profile.model.Profile
import bosca.serialization.UUID

data class SubmittedForm(val id: UUID, val type: FormSchemaType)

/**
 * Extension point for modules that need to react to form submissions
 * of a specific [FormSchemaType]. Each processor handles exactly one
 * type; the forms service invokes the matching processor after
 * persisting the submission row.
 */
interface FormSubmissionProcessor {

    /** The form schema type this processor handles. */
    val type: FormSchemaType

    /**
     * Called after a submission for a form of the matching [type] has
     * been persisted. Implementations may create side effects such as
     * Work Ops task creation or external webhook dispatch.
     */
    suspend fun process(profileId: UUID, submission: FormSubmissionInput, schema: FormSchema): SubmittedForm
}
