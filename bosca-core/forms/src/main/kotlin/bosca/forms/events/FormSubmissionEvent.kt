package bosca.forms.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.forms.jobs.FormSubmissionNotificationJob
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A display-ready form field included in form notification email payloads. */
@Serializable
data class FormEmailField(
    val label: String,
    val value: String,
)

interface FormSubmissionEvent : Event {
    val id: UUID
    val formSchemaId: UUID
    val profileId: UUID
}

const val FORM_SUBMISSION_CREATED_CHANNEL = "bosca.forms.submission.created"
const val FORM_SUBMISSION_STATUS_CHANNEL = "bosca.forms.submission.status"

@JobEvent(
    jobs = [FormSubmissionNotificationJob::class],
    pubsubChannel = FORM_SUBMISSION_CREATED_CHANNEL
)
@Serializable
class FormSubmissionCreated(
    override val id: UUID,
    override val formSchemaId: UUID,
    override val profileId: UUID
) : FormSubmissionEvent {
    constructor(submission: FormSubmission) : this(
        id = submission.id,
        formSchemaId = submission.formSchemaId,
        profileId = submission.profileId
    )
}

@JobEvent(jobs = [], pubsubChannel = FORM_SUBMISSION_STATUS_CHANNEL)
@Serializable
class FormSubmissionStatusChanged(
    override val id: UUID,
    override val formSchemaId: UUID,
    override val profileId: UUID,
    val newStatus: FormSubmissionStatus
) : FormSubmissionEvent

/**
 * Durable, self-contained email intent assembled by the form notification job. Two editable
 * pipelines consume it: one for configured reviewers and one optional submitter receipt.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.forms.submission.email_requested")
@Serializable
data class FormSubmissionEmailRequested(
    @Contextual val submissionId: UUID,
    @Contextual val formSchemaId: UUID,
    val reviewerRecipientIds: Set<@Contextual UUID>,
    val receiptRecipientIds: Set<@Contextual UUID>,
    val sendReceipt: Boolean,
    val formName: String,
    val subject: String? = null,
    val submitterName: String? = null,
    val submitterEmail: String? = null,
    val submittedAt: String,
    val fields: List<FormEmailField> = emptyList(),
    val reviewPath: String,
    val nextSteps: String = "",
) : Event
