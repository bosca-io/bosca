package bosca.forms.events

import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FormSubmissionEventTest {

    private val id = Uuid.random()
    private val formSchemaId = Uuid.random()
    private val profileId = Uuid.random()

    @Test
    fun `FormSubmissionCreated stores all fields`() {
        val event = FormSubmissionCreated(
            id = id,
            formSchemaId = formSchemaId,
            profileId = profileId
        )
        assertEquals(id, event.id)
        assertEquals(formSchemaId, event.formSchemaId)
        assertEquals(profileId, event.profileId)
    }

    @Test
    fun `FormSubmissionCreated secondary constructor extracts fields from FormSubmission`() {
        val submission = FormSubmission(
            id = id,
            formSchemaId = formSchemaId,
            profileId = profileId,
            attributes = kotlinx.serialization.json.JsonObject(emptyMap()),
            status = FormSubmissionStatus.PENDING,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now()
        )
        val event = FormSubmissionCreated(submission)
        assertEquals(submission.id, event.id)
        assertEquals(submission.formSchemaId, event.formSchemaId)
        assertEquals(submission.profileId, event.profileId)
    }

    @Test
    fun `FormSubmissionStatusChanged stores all fields including newStatus`() {
        val event = FormSubmissionStatusChanged(
            id = id,
            formSchemaId = formSchemaId,
            profileId = profileId,
            newStatus = FormSubmissionStatus.PROCESSED
        )
        assertEquals(id, event.id)
        assertEquals(formSchemaId, event.formSchemaId)
        assertEquals(profileId, event.profileId)
        assertEquals(FormSubmissionStatus.PROCESSED, event.newStatus)
    }

    @Test
    fun `FormSubmissionStatusChanged newStatus can be any FormSubmissionStatus`() {
        for (status in FormSubmissionStatus.entries) {
            val event = FormSubmissionStatusChanged(
                id = id,
                formSchemaId = formSchemaId,
                profileId = profileId,
                newStatus = status
            )
            assertEquals(status, event.newStatus)
        }
    }

    @Test
    fun `channel constants have expected values`() {
        assertEquals("bosca.forms.submission.created", FORM_SUBMISSION_CREATED_CHANNEL)
        assertEquals("bosca.forms.submission.status", FORM_SUBMISSION_STATUS_CHANNEL)
    }

    @Test
    fun `email request serializes all pipeline inputs`() {
        val event = FormSubmissionEmailRequested(
            submissionId = id,
            formSchemaId = formSchemaId,
            reviewerRecipientIds = setOf(profileId),
            receiptRecipientIds = setOf(profileId),
            sendReceipt = true,
            formName = "Contact",
            subject = "Contact form submission",
            submitterName = "Ada",
            submitterEmail = "ada@example.com",
            submittedAt = OffsetDateTime.now().toString(),
            fields = listOf(FormEmailField("Name", "Ada")),
            reviewPath = "/forms/submissions?id=$id",
            nextSteps = "We will respond.",
        )

        val decoded = Json.decodeFromString(
            FormSubmissionEmailRequested.serializer(),
            Json.encodeToString(FormSubmissionEmailRequested.serializer(), event),
        )

        assertEquals(event, decoded)
    }
}
