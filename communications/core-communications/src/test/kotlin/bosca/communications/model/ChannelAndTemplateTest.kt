package bosca.communications.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ChannelAndTemplateTest {

    @Test
    fun `Channel stores all fields`() {
        val id = Uuid.random()
        val config = JsonObject(mapOf("smtp" to JsonPrimitive("mail.example.com")))
        val channel = Channel(id = id, key = "email", name = "Email Channel", configuration = config)
        assertEquals(id, channel.id)
        assertEquals("email", channel.key)
        assertEquals("Email Channel", channel.name)
        assertEquals(config, channel.configuration)
    }

    @Test
    fun `Channel data class equality`() {
        val id = Uuid.random()
        val config = JsonObject(emptyMap())
        val c1 = Channel(id = id, key = "push", name = "Push", configuration = config)
        val c2 = Channel(id = id, key = "push", name = "Push", configuration = config)
        assertEquals(c1, c2)
    }

    @Test
    fun `MessageTemplate stores all fields`() {
        val id = Uuid.random()
        val attrs = JsonObject(mapOf("subject" to JsonPrimitive("Welcome")))
        val template = MessageTemplate(id = id, key = "welcome", title = "Welcome Email", attributes = attrs)
        assertEquals(id, template.id)
        assertEquals("welcome", template.key)
        assertEquals("Welcome Email", template.title)
        assertEquals(attrs, template.attributes)
    }

    @Test
    fun `MessageTemplate data class equality`() {
        val id = Uuid.random()
        val attrs = JsonObject(emptyMap())
        val t1 = MessageTemplate(id = id, key = "test", title = "Test", attributes = attrs)
        val t2 = MessageTemplate(id = id, key = "test", title = "Test", attributes = attrs)
        assertEquals(t1, t2)
    }
}
