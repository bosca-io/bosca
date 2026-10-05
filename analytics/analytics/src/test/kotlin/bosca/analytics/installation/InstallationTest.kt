package bosca.analytics.installation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class InstallationTest {

    @Test
    fun `Installation stores id`() {
        val installation = Installation(id = "01ARZ3NDEKTSV4RRFFQ69G5FAV")
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", installation.id)
    }

    @Test
    fun `Installation equality based on id`() {
        val a = Installation(id = "ABC123")
        val b = Installation(id = "ABC123")
        assertEquals(a, b)
    }

    @Test
    fun `Installation inequality for different ids`() {
        val a = Installation(id = "ABC123")
        val b = Installation(id = "DEF456")
        assertNotEquals(a, b)
    }

    @Test
    fun `Installation copy preserves id`() {
        val original = Installation(id = "ORIGINAL")
        val copy = original.copy()
        assertEquals(original, copy)
    }

    @Test
    fun `Installation copy with modified id`() {
        val original = Installation(id = "ORIGINAL")
        val modified = original.copy(id = "MODIFIED")
        assertEquals("MODIFIED", modified.id)
    }

    @Test
    fun `Installation new generates non-empty id`() = runBlocking {
        val installation = Installation.new()
        assertTrue(installation.id.isNotEmpty())
    }

    @Test
    fun `Installation new generates unique ids`() = runBlocking {
        val ids = (1..10).map { Installation.new().id }.toSet()
        assertEquals(10, ids.size)
    }

    @Test
    fun `Installation hashCode is consistent for equal objects`() {
        val a = Installation(id = "SAME")
        val b = Installation(id = "SAME")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Installation serializes and reads the configured pod node`() = runBlocking {
        val original = System.getProperty("POD_IP")
        val cached = Installation::class.java.getDeclaredField("cachedNodeId").apply { isAccessible = true }
        try {
            cached.set(null, null)
            System.setProperty("POD_IP", "10.0.0.42")
            val installation = Installation.new()
            assertEquals(26, installation.id.length)
            assertEquals(
                installation,
                Json.decodeFromString(
                    Installation.serializer(),
                    Json.encodeToString(Installation.serializer(), installation),
                ),
            )
        } finally {
            cached.set(null, null)
            if (original == null) System.clearProperty("POD_IP") else System.setProperty("POD_IP", original)
        }
    }

    @Test
    fun `Installation serializer rejects a missing id and ignores configured unknown fields`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(Installation.serializer(), "{}") }
        assertEquals(
            "id",
            Json { ignoreUnknownKeys = true }
                .decodeFromString(Installation.serializer(), "{\"id\":\"id\",\"unknown\":1}")
                .id,
        )
        assertEquals("environment", Installation.resolvePodIp("environment", "property"))
        assertEquals("property", Installation.resolvePodIp(null, "property"))
        assertEquals("127.0.0.1", Installation.resolvePodIp(null, null))
    }
}
