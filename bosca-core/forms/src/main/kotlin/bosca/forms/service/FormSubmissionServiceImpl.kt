package bosca.forms.service

import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.annotation.ProviderName
import bosca.forms.events.FormSubmissionCreated
import bosca.forms.events.FormSubmissionStatusChanged
import bosca.forms.events.dispatch
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionInput
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.repository.FormSubmissionRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Handles form submission storage, status management, and event dispatch.
 * After persisting a submission the service invokes any registered
 * [FormSubmissionProcessor] whose [FormSubmissionProcessor.type]
 * matches the schema's type, enabling modules like Work Ops to react
 * to submissions without coupling the forms module to them.
 */
@ServiceImplementation
class FormSubmissionServiceImpl(
    private val formSubmissionRepository: FormSubmissionRepository,
    private val formSchemaService: FormSchemaService,
    @ProviderName("workops-form-submission-processor")
    private val workSubmissionProcessor: ObjectProvider<FormSubmissionProcessor>,
) : FormSubmissionService {

    override suspend fun getById(id: UUID): FormSubmission? =
        formSubmissionRepository.getById(id)

    override suspend fun getByFormSchema(formSchemaId: UUID, offset: Long, limit: Int): List<FormSubmission> =
        formSubmissionRepository.getByFormSchema(formSchemaId, offset, limit)

    override suspend fun getByProfile(profileId: UUID, offset: Long, limit: Int): List<FormSubmission> =
        formSubmissionRepository.getByProfile(profileId, offset, limit)

    override suspend fun submit(profileId: UUID, input: FormSubmissionInput): SubmittedForm {
        val formSchemaId = input.formSchemaId
            ?: error("formSchemaId must be resolved before submitting")
        val schema = formSchemaService.getById(formSchemaId)
            ?: error("form schema not found: $formSchemaId")

        when (schema.type) {
            FormSchemaType.WORK_OPS -> {
                return workSubmissionProcessor.get().process(profileId, input, schema)
            }
            else -> {
                val submission = transaction {
                    formSubmissionRepository.add(
                        formSchemaId = schema.id,
                        profileId = profileId,
                        attributes = input.attributes
                    )
                }
                FormSubmissionCreated(submission).dispatch()
                return SubmittedForm(submission.id, schema.type)
            }
        }
    }

    override suspend fun setStatus(id: UUID, status: FormSubmissionStatus) {
        val submission = transaction {
            val s = formSubmissionRepository.getById(id) ?: error("submission not found: $id")
            formSubmissionRepository.setStatus(id, status)
            s
        }
        FormSubmissionStatusChanged(
            id = submission.id,
            formSchemaId = submission.formSchemaId,
            profileId = submission.profileId,
            newStatus = status
        ).dispatch()
    }

    override suspend fun delete(id: UUID) =
        formSubmissionRepository.delete(id)
}
