package bosca.forms.graphql

import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.serialization.UUID
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class FormSubmissionTypeControllerTest {

    private val formSchemaService = mockk<FormSchemaService>()
    private val formSchemaPermissionEvaluator = mockk<FormSchemaPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>()

    private val controller = FormSubmissionTypeController(
        formSchemaService,
        formSchemaPermissionEvaluator,
        profileService,
        profilePermissionEvaluator
    )

    private val submissionId = UUID.random()
    private val schemaId = UUID.random()
    private val profileId = UUID.random()
    private val now = OffsetDateTime.now()
    private val attrs = buildJsonObject { put("field", JsonPrimitive("value")) }

    private val submission = FormSubmission(
        id = submissionId,
        formSchemaId = schemaId,
        profileId = profileId,
        attributes = attrs,
        status = FormSubmissionStatus.PENDING,
        created = now,
        modified = now
    )

    @Test
    fun `id returns submission ID`() {
        assertEquals(submissionId, controller.id(submission))
    }

    @Test
    fun `attributes returns submission attributes`() {
        assertEquals(attrs, controller.attributes(submission))
    }

    @Test
    fun `status returns submission status`() {
        assertEquals(FormSubmissionStatus.PENDING, controller.status(submission))
    }

    @Test
    fun `created returns the creation timestamp`() {
        assertEquals(now, controller.created(submission))
    }

    @Test
    fun `modified returns the modification timestamp`() {
        assertEquals(now, controller.modified(submission))
    }
}
