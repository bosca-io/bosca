@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.forms.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.forms.events.FormSubmissionEmailRequested
import bosca.forms.events.dispatch
import bosca.forms.model.FormSchema
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.service.FormSchemaService
import bosca.forms.service.FormSubmissionService
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormSubmissionNotificationExecutorTest {

    private val submissionService = mockk<FormSubmissionService>()
    private val schemaService = mockk<FormSchemaService>()
    private val profileService = mockk<ProfileService>()
    private val queue = mockk<JobQueue>()
    private val emailRequests = mutableListOf<FormSubmissionEmailRequested>()
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        emailRequests.clear()
        provides<Json> { json }
        mockkStatic("bosca.forms.events.FormSubmissionEmailRequestedExtKt")
        coEvery { any<FormSubmissionEmailRequested>().dispatch() } coAnswers {
            emailRequests += firstArg<FormSubmissionEmailRequested>()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.forms.events.FormSubmissionEmailRequestedExtKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `executor resolves notification data through services and dispatches a self-contained email event`() = runTest {
        val submissionId = UUID.random()
        val schemaId = UUID.random()
        val submitterId = UUID.random()
        val reviewerId = UUID.random()
        val created = OffsetDateTime.now()
        val submission = FormSubmission(
            id = submissionId,
            formSchemaId = schemaId,
            profileId = submitterId,
            attributes = buildJsonObject {
                put("firstName", "Ada")
                put("topics", buildJsonArray { add(JsonPrimitive("Pipelines")); add(JsonPrimitive("Forms")) })
            },
            status = FormSubmissionStatus.PENDING,
            created = created,
            modified = created,
        )
        val schema = FormSchema(
            id = schemaId,
            key = "contact",
            name = "Contact form",
            description = "Contact form",
            schema = buildJsonObject {
                putJsonObject("properties") {
                    putJsonObject("firstName") { put("title", "First name") }
                    putJsonObject("topics") { put("title", "Topics") }
                }
            },
            uiSchema = buildJsonObject {},
            configuration = buildJsonObject {
                put("recipientProfileIds", buildJsonArray { add(JsonPrimitive(reviewerId.toString())) })
                put("sendReceipt", true)
                put("receiptNextSteps", "We will respond shortly.")
                put("notificationSubject", "A custom form subject")
            },
            version = 1,
            created = created,
            modified = created,
        )
        val submitter = Profile(
            id = submitterId,
            type = ProfileType.GENERIC,
            name = "Ada Lovelace",
            visibility = ProfileVisibility.USER,
        )
        val emailAttribute = ProfileAttribute(
            profile = submitterId,
            typeId = "bosca.profiles.email",
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 1,
            source = "test",
            attributes = buildJsonObject { put("email", "ada@example.com") },
        )
        coEvery { submissionService.getById(submissionId) } returns submission
        coEvery { schemaService.getById(schemaId) } returns schema
        coEvery { profileService.getById(submitterId) } returns submitter
        coEvery { profileService.getAttributes(submitterId) } returns listOf(emailAttribute)
        coEvery { submissionService.setStatus(submissionId, FormSubmissionStatus.PROCESSED) } just Runs

        execute(submissionId, schemaId)

        val request = emailRequests.single()
        assertEquals(setOf(reviewerId), request.reviewerRecipientIds)
        assertEquals(setOf(submitterId), request.receiptRecipientIds)
        assertEquals(true, request.sendReceipt)
        assertEquals("Contact form", request.formName)
        assertEquals("A custom form subject", request.subject)
        assertEquals("Ada Lovelace", request.submitterName)
        assertEquals("ada@example.com", request.submitterEmail)
        assertEquals(listOf("First name" to "Ada", "Topics" to "Pipelines, Forms"), request.fields.map { it.label to it.value })
        assertEquals("/forms/submissions?id=$submissionId", request.reviewPath)
        assertEquals("We will respond shortly.", request.nextSteps)
        coVerify { submissionService.setStatus(submissionId, FormSubmissionStatus.PROCESSED) }
    }

    @Test
    fun `executor rejects malformed reviewer ids and leaves the submission pending`() = runTest {
        val submissionId = UUID.random()
        val schemaId = UUID.random()
        val submitterId = UUID.random()
        val created = OffsetDateTime.now()
        coEvery { submissionService.getById(submissionId) } returns FormSubmission(
            id = submissionId,
            formSchemaId = schemaId,
            profileId = submitterId,
            attributes = buildJsonObject {},
            status = FormSubmissionStatus.PENDING,
            created = created,
            modified = created,
        )
        coEvery { schemaService.getById(schemaId) } returns FormSchema(
            id = schemaId,
            key = "broken",
            name = "Broken form",
            description = "",
            schema = buildJsonObject {},
            uiSchema = buildJsonObject {},
            configuration = buildJsonObject {
                put("recipientProfileIds", buildJsonArray { add(JsonPrimitive("not-a-profile-id")) })
            },
            version = 1,
            created = created,
            modified = created,
        )

        assertFailsWith<IllegalArgumentException> { execute(submissionId, schemaId) }

        assertEquals(emptyList(), emailRequests)
        coVerify(exactly = 0) { submissionService.setStatus(any(), any()) }
    }

    @Test
    fun `executor does not process a submission when no email destination is configured`() = runTest {
        val submissionId = UUID.random()
        val schemaId = UUID.random()
        val submitterId = UUID.random()
        val created = OffsetDateTime.now()
        coEvery { submissionService.getById(submissionId) } returns FormSubmission(
            id = submissionId,
            formSchemaId = schemaId,
            profileId = submitterId,
            attributes = buildJsonObject {},
            status = FormSubmissionStatus.PENDING,
            created = created,
            modified = created,
        )
        coEvery { schemaService.getById(schemaId) } returns FormSchema(
            id = schemaId,
            key = "unconfigured",
            name = "Unconfigured form",
            description = "",
            schema = buildJsonObject {},
            uiSchema = buildJsonObject {},
            configuration = buildJsonObject {
                put("recipientProfileIds", buildJsonArray {})
                put("sendReceipt", false)
            },
            version = 1,
            created = created,
            modified = created,
        )

        execute(submissionId, schemaId)

        assertEquals(emptyList(), emailRequests)
        coVerify(exactly = 0) { submissionService.setStatus(any(), any()) }
        coVerify(exactly = 0) { profileService.getById(any()) }
    }

    private suspend fun execute(submissionId: UUID, schemaId: UUID) {
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(
                FormSubmissionNotificationJob.serializer(),
                FormSubmissionNotificationJob(submissionId, schemaId),
            ),
            executor = FormSubmissionNotificationExecutor::class,
        )
        withContext(queue.asCoroutineContext(job)) {
            FormSubmissionNotificationExecutor(submissionService, schemaService, profileService).execute()
        }
    }
}
