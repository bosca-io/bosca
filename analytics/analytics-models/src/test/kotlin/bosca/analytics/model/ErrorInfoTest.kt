package bosca.analytics.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorInfoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialize ErrorInfo with all fields`() {
        val error = ErrorInfo(
            message = "NullPointerException",
            type = "TypeError",
            stackTrace = "at com.example.Main.run(Main.kt:42)",
            fatal = true,
            code = "ERR_001"
        )
        val serialized = json.encodeToString(error)
        val deserialized = json.decodeFromString<ErrorInfo>(serialized)

        assertEquals(error, deserialized)
        assertEquals("NullPointerException", deserialized.message)
        assertEquals("TypeError", deserialized.type)
        assertEquals("at com.example.Main.run(Main.kt:42)", deserialized.stackTrace)
        assertTrue(deserialized.fatal)
        assertEquals("ERR_001", deserialized.code)
    }

    @Test
    fun `serialize ErrorInfo with only required fields`() {
        val error = ErrorInfo(message = "Something went wrong")
        val serialized = json.encodeToString(error)
        val deserialized = json.decodeFromString<ErrorInfo>(serialized)

        assertEquals("Something went wrong", deserialized.message)
        assertNull(deserialized.type)
        assertNull(deserialized.stackTrace)
        assertFalse(deserialized.fatal)
        assertNull(deserialized.code)
    }

    @Test
    fun `stackTrace serializes as snake_case stack_trace`() {
        val error = ErrorInfo(
            message = "error",
            stackTrace = "line1\nline2\nline3"
        )
        val serialized = json.encodeToString(error)

        assertTrue(serialized.contains("\"stack_trace\""))
        assertFalse(serialized.contains("\"stackTrace\""))
    }

    @Test
    fun `deserialize from JSON with snake_case stack_trace`() {
        val jsonStr = """{"message":"err","type":"ReferenceError","stack_trace":"at foo()","fatal":true,"code":"E42"}"""
        val error = json.decodeFromString<ErrorInfo>(jsonStr)

        assertEquals("err", error.message)
        assertEquals("ReferenceError", error.type)
        assertEquals("at foo()", error.stackTrace)
        assertTrue(error.fatal)
        assertEquals("E42", error.code)
    }

    @Test
    fun `deserialize from JSON with missing optional fields`() {
        val jsonStr = """{"message":"minimal error"}"""
        val error = json.decodeFromString<ErrorInfo>(jsonStr)

        assertEquals("minimal error", error.message)
        assertNull(error.type)
        assertNull(error.stackTrace)
        assertFalse(error.fatal)
        assertNull(error.code)
    }

    @Test
    fun `deserialize from JSON with explicit nulls`() {
        val jsonStr = """{"message":"null test","type":null,"stack_trace":null,"fatal":false,"code":null}"""
        val error = json.decodeFromString<ErrorInfo>(jsonStr)

        assertEquals("null test", error.message)
        assertNull(error.type)
        assertNull(error.stackTrace)
        assertFalse(error.fatal)
        assertNull(error.code)
    }

    @Test
    fun `fatal defaults to false`() {
        val error = ErrorInfo(message = "non-fatal")
        assertFalse(error.fatal)
    }

    @Test
    fun `fatal can be set to true`() {
        val error = ErrorInfo(message = "fatal", fatal = true)
        assertTrue(error.fatal)
    }

    @Test
    fun `data class equality works correctly`() {
        val error1 = ErrorInfo(message = "err", type = "A", stackTrace = "st", fatal = true, code = "1")
        val error2 = ErrorInfo(message = "err", type = "A", stackTrace = "st", fatal = true, code = "1")
        val error3 = ErrorInfo(message = "different")

        assertEquals(error1, error2)
        assertEquals(error1.hashCode(), error2.hashCode())
        assertFalse(error1 == error3)
    }

    @Test
    fun `copy preserves and overrides fields`() {
        val original = ErrorInfo(message = "original", type = "TypeError", fatal = true)
        val copied = original.copy(message = "copied", fatal = false)

        assertEquals("copied", copied.message)
        assertEquals("TypeError", copied.type)
        assertFalse(copied.fatal)
    }

    @Test
    fun `round-trip preserves multiline stack traces`() {
        val stackTrace = """Error: something failed
    at Object.run (app.js:10:15)
    at async main (index.js:5:3)
    at Module._compile (internal/modules/cjs/loader.js:999:30)"""

        val error = ErrorInfo(message = "failed", stackTrace = stackTrace)
        val serialized = json.encodeToString(error)
        val deserialized = json.decodeFromString<ErrorInfo>(serialized)

        assertEquals(stackTrace, deserialized.stackTrace)
    }
}
