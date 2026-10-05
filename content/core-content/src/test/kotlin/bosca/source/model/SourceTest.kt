package bosca.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class SourceTest {

    @Test
    fun `stores all fields`() {
        val id = Uuid.random()
        val config = JsonObject(mapOf("url" to JsonPrimitive("https://example.com")))
        val source = Source(
            id = id,
            name = "S3 Source",
            description = "AWS S3 bucket",
            configuration = config
        )
        assertEquals(id, source.id)
        assertEquals("S3 Source", source.name)
        assertEquals("AWS S3 bucket", source.description)
        assertEquals(config, source.configuration)
    }

    @Test
    fun `id defaults to NIL`() {
        val config = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val source = Source(
            name = "Default",
            description = "desc",
            configuration = config
        )
        assertEquals(Uuid.NIL, source.id)
    }

    @Test
    fun `data class equality`() {
        val id = Uuid.random()
        val config = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val a = Source(id = id, name = "n", description = "d", configuration = config)
        val b = Source(id = id, name = "n", description = "d", configuration = config)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different name`() {
        val id = Uuid.random()
        val config = JsonObject(emptyMap())
        val a = Source(id = id, name = "a", description = "d", configuration = config)
        val b = Source(id = id, name = "b", description = "d", configuration = config)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies name`() {
        val config = JsonObject(emptyMap())
        val original = Source(name = "original", description = "d", configuration = config)
        val copied = original.copy(name = "updated")
        assertEquals("updated", copied.name)
        assertEquals("d", copied.description)
    }
}
