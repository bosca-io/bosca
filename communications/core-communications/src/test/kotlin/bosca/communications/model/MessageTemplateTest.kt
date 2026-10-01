package bosca.communications.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MessageTemplateTest {

    private val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val attrs = JsonObject(mapOf("template" to JsonPrimitive("welcome")))

    @Test
    fun fieldsArePreserved() {
        val template = MessageTemplate(id = id, key = "welcome", title = "Welcome Email", attributes = attrs)
        assertEquals(id, template.id)
        assertEquals("welcome", template.key)
        assertEquals("Welcome Email", template.title)
        assertEquals(attrs, template.attributes)
    }

    @Test
    fun dataClassEquality() {
        val a = MessageTemplate(id = id, key = "k", title = "t", attributes = attrs)
        val b = MessageTemplate(id = id, key = "k", title = "t", attributes = attrs)
        assertEquals(a, b)
    }
}
