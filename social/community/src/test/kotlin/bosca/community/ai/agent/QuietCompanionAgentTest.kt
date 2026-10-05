package bosca.community.ai.agent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuietCompanionAgentTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `QuietCompanionResponse deserializes with shouldRespond true`() {
        val raw = """{"shouldRespond":true,"response":"Hello!","reason":"They asked a question"}"""
        val response = json.decodeFromString<QuietCompanionResponse>(raw)
        assertTrue(response.shouldRespond)
        assertEquals("Hello!", response.response)
        assertEquals("They asked a question", response.reason)
    }

    @Test
    fun `QuietCompanionResponse deserializes with shouldRespond false`() {
        val raw = """{"shouldRespond":false}"""
        val response = json.decodeFromString<QuietCompanionResponse>(raw)
        assertFalse(response.shouldRespond)
        assertNull(response.response)
        assertNull(response.reason)
    }

    @Test
    fun `QuietCompanionResponse ignores unknown keys`() {
        val raw = """{"shouldRespond":true,"response":"Hi","unknownField":"ignored","reason":null}"""
        val response = json.decodeFromString<QuietCompanionResponse>(raw)
        assertTrue(response.shouldRespond)
        assertEquals("Hi", response.response)
        assertNull(response.reason)
    }

    @Test
    fun `QuietCompanionResponse defaults optional fields to null`() {
        val response = QuietCompanionResponse(shouldRespond = false)
        assertFalse(response.shouldRespond)
        assertNull(response.response)
        assertNull(response.reason)
    }

    @Test
    fun `QuietCompanionResponse equality and copy`() {
        val a = QuietCompanionResponse(shouldRespond = true, response = "Go", reason = "Addressed")
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `QuietCompanionResponse serialization round-trip`() {
        val original = QuietCompanionResponse(shouldRespond = true, response = "Be blessed", reason = "prayer")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<QuietCompanionResponse>(serialized)
        assertEquals(original, deserialized)
    }
}
