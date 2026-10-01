package bosca.forms.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class FormSubmissionInputTest {

    @Test
    fun `FormSubmissionInput with formSchemaId`() {
        val id = Uuid.random()
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = FormSubmissionInput(
            formSchemaId = id,
            attributes = attrs
        )
        assertEquals(id, input.formSchemaId)
        assertNull(input.formSchemaKey)
        assertEquals(attrs, input.attributes)
        assertNull(input.profile)
    }

    @Test
    fun `FormSubmissionInput with formSchemaKey`() {
        val attrs = JsonObject(mapOf("name" to JsonPrimitive("test")))
        val input = FormSubmissionInput(
            formSchemaKey = "my-form",
            attributes = attrs
        )
        assertNull(input.formSchemaId)
        assertEquals("my-form", input.formSchemaKey)
    }

    @Test
    fun `FormSubmissionInput formSchemaId defaults to null`() {
        val input = FormSubmissionInput(attributes = JsonPrimitive("test"))
        assertNull(input.formSchemaId)
    }

    @Test
    fun `FormSubmissionInput formSchemaKey defaults to null`() {
        val input = FormSubmissionInput(attributes = JsonPrimitive("test"))
        assertNull(input.formSchemaKey)
    }

    @Test
    fun `FormSubmissionInput profile defaults to null`() {
        val input = FormSubmissionInput(attributes = JsonPrimitive("test"))
        assertNull(input.profile)
    }

    @Test
    fun `FormSubmissionInput data class equality`() {
        val id = Uuid.random()
        val attrs = JsonPrimitive("test")
        val input1 = FormSubmissionInput(formSchemaId = id, attributes = attrs)
        val input2 = FormSubmissionInput(formSchemaId = id, attributes = attrs)
        assertEquals(input1, input2)
    }
}
