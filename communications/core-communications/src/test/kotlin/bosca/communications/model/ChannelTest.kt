package bosca.communications.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ChannelTest {

    private val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val config = JsonObject(mapOf("smtp" to JsonPrimitive("localhost")))

    @Test
    fun fieldsArePreserved() {
        val channel = Channel(id = id, key = "email", name = "Email Channel", configuration = config)
        assertEquals(id, channel.id)
        assertEquals("email", channel.key)
        assertEquals("Email Channel", channel.name)
        assertEquals(config, channel.configuration)
    }

    @Test
    fun dataClassEquality() {
        val a = Channel(id = id, key = "email", name = "Email", configuration = config)
        val b = Channel(id = id, key = "email", name = "Email", configuration = config)
        assertEquals(a, b)
    }
}
