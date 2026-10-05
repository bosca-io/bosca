package bosca.forms.service

import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionInput
import bosca.forms.model.FormSubmissionStatus
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing form submissions. Handles storing submitted form data,
 * querying submissions by form schema or profile, and updating submission status.
 */
interface FormSubmissionService : Service {

    /**
     * Retrieves a form submission by its unique identifier.
     *
     * @param id the submission identifier
     * @return the form submission, or null if not found
     */
    suspend fun getById(id: UUID): FormSubmission?

    /**
     * Retrieves submissions for a specific form schema.
     *
     * @param formSchemaId the form schema identifier
     * @param offset the pagination offset
     * @param limit the maximum number of submissions to return
     * @return the list of form submissions
     */
    suspend fun getByFormSchema(formSchemaId: UUID, offset: Long, limit: Int): List<FormSubmission>

    /**
     * Retrieves submissions made by a specific profile.
     *
     * @param profileId the profile identifier
     * @param offset the pagination offset
     * @param limit the maximum number of submissions to return
     * @return the list of form submissions
     */
    suspend fun getByProfile(profileId: UUID, offset: Long, limit: Int): List<FormSubmission>

    /**
     * Creates a new form submission linked to the specified profile.
     * Stores the submission and dispatches the FormSubmissionCreated event.
     *
     * @param profileId the profile to associate with this submission
     * @param input the submission data including form schema ID and attributes
     * @return the created form submission
     */
    suspend fun submit(profileId: UUID, input: FormSubmissionInput): SubmittedForm

    /**
     * Updates the processing status of a form submission.
     *
     * @param id the submission identifier
     * @param status the new status to set
     */
    suspend fun setStatus(id: UUID, status: FormSubmissionStatus)

    /**
     * Deletes a form submission by its identifier.
     *
     * @param id the submission identifier
     */
    suspend fun delete(id: UUID)
}
