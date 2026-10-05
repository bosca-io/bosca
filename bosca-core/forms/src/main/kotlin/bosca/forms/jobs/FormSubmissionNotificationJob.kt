package bosca.forms.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Job payload for sending notification emails when a form submission is received.
 * Enqueued automatically by the FormSubmissionCreated event via KSP-generated dispatch.
 */
@Serializable
data class FormSubmissionNotificationJob(
    val id: UUID,
    val formSchemaId: UUID
) : IJobDefinition
