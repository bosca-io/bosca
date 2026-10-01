package bosca.analytics.server

import bosca.analytics.model.EventType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThrowableErrorInfoMapperTest {

    @Test
    fun `toErrorInfo populates type message stack and fatal flag`() {
        val cause = IllegalStateException("boom")
        val info = ThrowableErrorInfoMapper.toErrorInfo(cause, fatal = true)
        assertEquals("java.lang.IllegalStateException", info.type)
        assertEquals("boom", info.message)
        assertNotNull(info.stackTrace)
        assertTrue(info.stackTrace!!.contains("IllegalStateException"))
        assertEquals(true, info.fatal)
        // Fingerprint is server-side only.
        assertNull(info.fingerprint)
    }

    @Test
    fun `toErrorInfo falls back to type when message is null`() {
        val cause = NullPointerException()
        val info = ThrowableErrorInfoMapper.toErrorInfo(cause, fatal = false)
        assertEquals("java.lang.NullPointerException", info.message)
        assertEquals(false, info.fatal)
    }

    @Test
    fun `toErrorInfo supports throwables without a qualified Kotlin name`() {
        class LocalThrowable : Throwable()

        val info = ThrowableErrorInfoMapper.toErrorInfo(LocalThrowable(), fatal = false)
        val type = assertNotNull(info.type)

        assertTrue(type.endsWith("LocalThrowable"))
        assertEquals(type, info.message)
    }

    @Test
    fun `toErrorInfo truncates extremely long stack traces`() {
        // Synthesize a throwable whose stack trace is far beyond the cap
        // by injecting many synthetic frames. This avoids the native JVM
        // stack depth limit while still exercising the truncation path.
        val cause = RuntimeException("deep")
        val frames = Array(10_000) { StackTraceElement("com.example.Foo", "bar", "Foo.kt", it) }
        cause.stackTrace = frames
        val info = ThrowableErrorInfoMapper.toErrorInfo(cause, fatal = false)
        assertNotNull(info.stackTrace)
        assertTrue(info.stackTrace!!.length <= 32_000, "stack trace must be capped at MAX_STACK_CHARS")
    }

    @Test
    fun `toEvent builds an Error event with the throwable info`() {
        val cause = IllegalArgumentException("bad input")
        val event = ThrowableErrorInfoMapper.toEvent(cause, fatal = false, clientId = "client-1")
        assertEquals(EventType.Error, event.type)
        assertEquals("client-1", event.clientId)
        assertNotNull(event.error)
        assertEquals("java.lang.IllegalArgumentException", event.error!!.type)
        assertEquals("bad input", event.error!!.message)
        assertTrue(event.created > 0)
    }

    @Test
    fun `toErrorInfo omits contextJson when the context is empty`() {
        val info = ThrowableErrorInfoMapper.toErrorInfo(RuntimeException("x"), fatal = false)
        assertNull(info.contextJson)
    }

    @Test
    fun `toErrorInfo encodes primitive context values inline`() {
        val info = ThrowableErrorInfoMapper.toErrorInfo(
            RuntimeException("x"),
            fatal = false,
            context = mapOf(
                "user_id" to "user-1",
                "attempt" to 3,
                "retry" to true,
                "missing" to null,
                "json" to JsonPrimitive("value"),
            ),
        )
        val json = info.contextJson!!
        assertTrue(json.contains("\"user_id\":\"user-1\""))
        assertTrue(json.contains("\"attempt\":3"))
        assertTrue(json.contains("\"retry\":true"))
        assertTrue(json.contains("\"missing\":null"))
        assertTrue(json.contains("\"json\":\"value\""))
    }

    @Test
    fun `toErrorInfo coerces non-primitive context values via toString`() {
        data class Holder(val id: String) { override fun toString() = "holder:$id" }
        val info = ThrowableErrorInfoMapper.toErrorInfo(
            RuntimeException("x"),
            fatal = false,
            context = mapOf("holder" to Holder("42")),
        )
        assertTrue(info.contextJson!!.contains("\"holder\":\"holder:42\""))
    }
}
