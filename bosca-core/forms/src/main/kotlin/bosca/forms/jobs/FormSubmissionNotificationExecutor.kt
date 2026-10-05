package bosca.forms.jobs

import bosca.forms.configuration.JobQueueNames
import bosca.forms.events.FormEmailField
import bosca.forms.events.FormSubmissionEmailRequested
import bosca.forms.events.dispatch
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.service.FormSchemaService
import bosca.forms.service.FormSubmissionService
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * Resolves a form submission into a self-contained email intent. Rendering, preference checks,
 * and delivery are owned by the triggered form email pipelines.
 */
@JobDefinition(FormSubmissionNotificationJob::class, JobQueueNames.formsJobQueue, "form-submission-notification")
class FormSubmissionNotificationExecutor(
    private val formSubmissionService: FormSubmissionService,
    private val formSchemaService: FormSchemaService,
    private val profileService: ProfileService,
) : AbstractJobExecutor<FormSubmissionNotificationJob>(FormSubmissionNotificationJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val submission = formSubmissionService.getById(job.id)
        if (submission == null) {
            log.error("submission not found for notification job: {}", job.id)
            return
        }
        val schema = formSchemaService.getById(job.formSchemaId)
        if (schema == null) {
            log.error("form schema not found for notification job: {}", job.formSchemaId)
            return
        }

        val config = schema.configuration as? JsonObject ?: JsonObject(emptyMap())
        val reviewerIds = when (val configuredRecipients = config["recipientProfileIds"]) {
            null -> emptySet()
            is JsonArray -> configuredRecipients.mapTo(linkedSetOf()) { value ->
                val raw = (value as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException(
                        "recipientProfileIds contains a non-string value for form schema ${job.formSchemaId}",
                    )
                runCatching { bosca.serialization.UUID.parse(raw) }.getOrElse {
                    throw IllegalArgumentException(
                        "recipientProfileIds contains invalid profile id '$raw' for form schema ${job.formSchemaId}",
                        it,
                    )
                }
            }
            else -> throw IllegalArgumentException(
                "recipientProfileIds must be an array for form schema ${job.formSchemaId}",
            )
        }
        val sendReceipt = (config["sendReceipt"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (reviewerIds.isEmpty() && !sendReceipt) {
            log.error("no reviewer recipients or submitter receipt configured for form schema: {}", job.formSchemaId)
            return
        }
        val submitter = profileService.getById(submission.profileId)
        val submitterEmail = profileService.getAttributes(submission.profileId)
            .getAttributeString("bosca.profiles.email", "email")
        FormSubmissionEmailRequested(
            submissionId = submission.id,
            formSchemaId = submission.formSchemaId,
            reviewerRecipientIds = reviewerIds,
            receiptRecipientIds = setOf(submission.profileId),
            sendReceipt = sendReceipt,
            formName = schema.name,
            subject = (config["notificationSubject"] as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            submitterName = submitter.name,
            submitterEmail = submitterEmail,
            submittedAt = submission.created.toString(),
            fields = displayFields(schema.schema as? JsonObject, submission.attributes as? JsonObject),
            reviewPath = "/forms/submissions?id=${submission.id}",
            nextSteps = (config["receiptNextSteps"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        ).dispatch()
        formSubmissionService.setStatus(submission.id, FormSubmissionStatus.PROCESSED)
    }

    private fun displayFields(schema: JsonObject?, attributes: JsonObject?): List<FormEmailField> {
        if (attributes == null) return emptyList()
        val properties = schema?.get("properties") as? JsonObject
        return attributes.map { (key, value) ->
            val property = properties?.get(key) as? JsonObject
            val label = (property?.get("title") as? JsonPrimitive)?.contentOrNull ?: key
            FormEmailField(label, displayValue(value))
        }
    }

    private fun displayValue(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { displayValue(it) }
        else -> value.toString()
    }

    companion object {
        private val log = LoggerFactory.getLogger(FormSubmissionNotificationExecutor::class.java)
    }
}
