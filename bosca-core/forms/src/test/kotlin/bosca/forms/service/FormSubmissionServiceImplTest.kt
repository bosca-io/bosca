package bosca.forms.service

import bosca.di.ObjectProvider
import bosca.forms.events.FormSubmissionCreated
import bosca.forms.events.FormSubmissionStatusChanged
import bosca.forms.events.dispatch
import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionInput
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.repository.FormSubmissionRepository
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormSubmissionServiceImplTest {

    private val formSubmissionRepository = mockk<FormSubmissionRepository>()
    private val formSchemaService = mockk<FormSchemaService>()
    private val workSubmissionProcessor = mockk<ObjectProvider<FormSubmissionProcessor>>()

    private val service by lazy {
        FormSubmissionServiceImpl(formSubmissionRepository, formSchemaService, workSubmissionProcessor)
    }

    private val schemaId = UUID.random()
    private val profileId = UUID.random()

    private val schema = FormSchema(
        id = schemaId,
        key = "test-form",
        name = "Test Form",
        description = "A test form",
        schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
            }
        },
        uiSchema = buildJsonObject { put("version", 1) },
        version = 1,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        mockkStatic("bosca.forms.events.FormSubmissionCreatedExtKt")
        mockkStatic("bosca.forms.events.FormSubmissionStatusChangedExtKt")
        coEvery { any<FormSubmissionCreated>().dispatch() } just Runs
        coEvery { any<FormSubmissionStatusChanged>().dispatch() } just Runs
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    private fun stubSuccessfulAdd() {
        coEvery { formSubmissionRepository.add(any(), any(), any()) } answers {
            FormSubmission(
                id = UUID.random(),
                formSchemaId = arg(0),
                profileId = arg(1),
                attributes = arg(2),
                status = FormSubmissionStatus.PENDING,
                created = OffsetDateTime.now(),
                modified = OffsetDateTime.now()
            )
        }
    }

    // --- Schema resolution ---

    @Test
    fun `submit fails when formSchemaId is null`() = runTest {
        val input = FormSubmissionInput(
            formSchemaId = null,
            attributes = JsonObject(emptyMap())
        )
        val ex = assertFailsWith<IllegalStateException> {
            service.submit(profileId, input)
        }
        assertEquals("formSchemaId must be resolved before submitting", ex.message)
    }

    @Test
    fun `submit fails when schema not found`() = runTest {
        coEvery { formSchemaService.getById(schemaId) } returns null
        val input = FormSubmissionInput(
            formSchemaId = schemaId,
            attributes = JsonObject(emptyMap())
        )
        val ex = assertFailsWith<IllegalStateException> {
            service.submit(profileId, input)
        }
        assertEquals("form schema not found: $schemaId", ex.message)
    }

    // --- Successful submission ---

    @Test
    fun `submit stores submission and returns it`() = runTest {
        coEvery { formSchemaService.getById(schemaId) } returns schema
        stubSuccessfulAdd()
        val attrs = buildJsonObject { put("name", "Alice") }
        val input = FormSubmissionInput(
            formSchemaId = schemaId,
            attributes = attrs
        )
        service.submit(profileId, input)
        coVerify { formSubmissionRepository.add(schemaId, profileId, attrs) }
    }

    // --- Event dispatch ---

    @Test
    fun `submit dispatches FormSubmissionCreated event`() = runTest {
        coEvery { formSchemaService.getById(schemaId) } returns schema
        stubSuccessfulAdd()
        val input = FormSubmissionInput(
            formSchemaId = schemaId,
            attributes = buildJsonObject { put("name", "Alice") }
        )
        service.submit(profileId, input)
        coVerify { any<FormSubmissionCreated>().dispatch() }
    }

    // --- Status management ---

    @Test
    fun `setStatus updates status and dispatches event`() = runTest {
        val submissionId = UUID.random()
        val submission = FormSubmission(
            id = submissionId,
            formSchemaId = schemaId,
            profileId = profileId,
            attributes = JsonObject(emptyMap()),
            status = FormSubmissionStatus.PENDING,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now()
        )
        coEvery { formSubmissionRepository.getById(submissionId) } returns submission
        coEvery { formSubmissionRepository.setStatus(submissionId, FormSubmissionStatus.PROCESSED) } just Runs

        service.setStatus(submissionId, FormSubmissionStatus.PROCESSED)

        coVerify { formSubmissionRepository.setStatus(submissionId, FormSubmissionStatus.PROCESSED) }
        coVerify { any<FormSubmissionStatusChanged>().dispatch() }
    }

    @Test
    fun `setStatus fails when submission not found`() = runTest {
        val submissionId = UUID.random()
        coEvery { formSubmissionRepository.getById(submissionId) } returns null

        assertFailsWith<IllegalStateException> {
            service.setStatus(submissionId, FormSubmissionStatus.PROCESSED)
        }
    }

    // --- Delete ---

    @Test
    fun `delete delegates to repository`() = runTest {
        val submissionId = UUID.random()
        coEvery { formSubmissionRepository.delete(submissionId) } just Runs

        service.delete(submissionId)

        coVerify { formSubmissionRepository.delete(submissionId) }
    }

    // --- Query methods ---

    @Test
    fun `getByFormSchema delegates to repository`() = runTest {
        coEvery { formSubmissionRepository.getByFormSchema(schemaId, 0, 10) } returns emptyList()

        val result = service.getByFormSchema(schemaId, 0, 10)

        assertEquals(emptyList(), result)
        coVerify { formSubmissionRepository.getByFormSchema(schemaId, 0, 10) }
    }

    @Test
    fun `getById delegates to repository`() = runTest {
        val submissionId = UUID.random()
        coEvery { formSubmissionRepository.getById(submissionId) } returns null

        val result = service.getById(submissionId)

        assertEquals(null, result)
        coVerify { formSubmissionRepository.getById(submissionId) }
    }
}
