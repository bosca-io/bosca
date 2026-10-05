package bosca.forms.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FormSubmissionTest {

    private val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val schemaId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")
    private val profileId = Uuid.parse("770e8400-e29b-41d4-a716-446655440002")
    private val attrs = JsonObject(mapOf("field" to JsonPrimitive("value")))
    private val now = java.time.OffsetDateTime.now()

    @Test
    fun fieldsArePreserved() {
        val submission = FormSubmission(
            id = id,
            formSchemaId = schemaId,
            profileId = profileId,
            attributes = attrs,
            status = FormSubmissionStatus.PENDING,
            created = now,
            modified = now
        )
        assertEquals(id, submission.id)
        assertEquals(schemaId, submission.formSchemaId)
        assertEquals(profileId, submission.profileId)
        assertEquals(attrs, submission.attributes)
        assertEquals(FormSubmissionStatus.PENDING, submission.status)
    }

    @Test
    fun dataClassEquality() {
        val a = FormSubmission(id = id, formSchemaId = schemaId, profileId = profileId, attributes = attrs, status = FormSubmissionStatus.PENDING, created = now, modified = now)
        val b = FormSubmission(id = id, formSchemaId = schemaId, profileId = profileId, attributes = attrs, status = FormSubmissionStatus.PENDING, created = now, modified = now)
        assertEquals(a, b)
    }
}
