package bosca.analytics.transform

import bosca.analytics.model.Device
import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.server.Headers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ErrorFingerprintTransformTest {

    private val transform = ErrorFingerprintTransform()

    private fun ctx() = EventPipelineContext(Headers.Empty)

    private fun events(appId: String, vararg events: Event): Events =
        Events(
            context = EventContext(
                appId = appId,
                appVersion = "1.0.0",
                device = Device(
                    installationId = "i",
                    manufacturer = "m",
                    model = "m",
                    platform = "p",
                    primaryLocale = "en",
                    systemName = "s",
                    timezone = "UTC",
                    type = "t",
                    version = "v",
                ),
                sessionId = "s",
            ),
            events = events.toList(),
            sent = 0L,
            sentMicros = 0L,
        )

    private fun errorEvent(error: ErrorInfo): Event =
        Event(created = 0L, type = EventType.Error, error = error)

    @Test
    fun `non-error events pass through unchanged`() = runTest {
        val original = events(
            "app-1",
            Event(created = 0L, type = EventType.Session),
            Event(created = 0L, type = EventType.Interaction),
        )
        val result = transform.transform(ctx(), original)
        assertEquals(original, result)
    }

    @Test
    fun `error events get a fingerprint assigned`() = runTest {
        val event = errorEvent(
            ErrorInfo(
                message = "boom",
                type = "java.lang.IllegalStateException",
                stackTrace = """
                    java.lang.IllegalStateException: boom
                        at com.example.Foo.bar(Foo.kt:42)
                        at com.example.Foo.baz(Foo.kt:17)
                """.trimIndent()
            )
        )
        val result = transform.transform(ctx(), events("app-1", event))
        assertNotNull(result.events.single().error?.fingerprint)
    }

    @Test
    fun `pre-existing fingerprint is preserved`() = runTest {
        val event = errorEvent(
            ErrorInfo(message = "x", type = "E", stackTrace = "at a.b()", fingerprint = "preset")
        )
        val result = transform.transform(ctx(), events("app-1", event))
        assertEquals("preset", result.events.single().error?.fingerprint)
    }

    @Test
    fun `same logical error produces same fingerprint despite line and lambda noise`() = runTest {
        val a = ErrorInfo(
            message = "boom",
            type = "java.lang.IllegalStateException",
            stackTrace = """
                java.lang.IllegalStateException: boom
                    at com.example.Foo${'$'}${'$'}Lambda${'$'}123/0x00007f8a8c0d2840.invoke(Foo.kt:42)
                    at com.example.Foo${'$'}1.run(Foo.kt:17)
                    at com.example.Bar.baz(Bar.kt:99)
            """.trimIndent()
        )
        val b = ErrorInfo(
            message = "boom — different message text",
            type = "java.lang.IllegalStateException",
            stackTrace = """
                java.lang.IllegalStateException: boom
                    at com.example.Foo${'$'}${'$'}Lambda${'$'}999/0x00007fff12345678.invoke(Foo.kt:51)
                    at com.example.Foo${'$'}2.run(Foo.kt:24)
                    at com.example.Bar.baz(Bar.kt:120)
            """.trimIndent()
        )
        val fpA = transform.fingerprint("app-1", a)
        val fpB = transform.fingerprint("app-1", b)
        assertEquals(fpA, fpB)
    }

    @Test
    fun `different exception types produce different fingerprints`() = runTest {
        val a = ErrorInfo(message = "x", type = "java.lang.IllegalStateException", stackTrace = "at com.example.Foo.bar(Foo.kt:1)")
        val b = ErrorInfo(message = "x", type = "java.lang.NullPointerException", stackTrace = "at com.example.Foo.bar(Foo.kt:1)")
        assertNotEquals(transform.fingerprint("app-1", a), transform.fingerprint("app-1", b))
    }

    @Test
    fun `different top frames produce different fingerprints`() = runTest {
        val a = ErrorInfo(message = "x", type = "E", stackTrace = "at com.example.Foo.bar(Foo.kt:1)")
        val b = ErrorInfo(message = "x", type = "E", stackTrace = "at com.example.Foo.qux(Foo.kt:1)")
        assertNotEquals(transform.fingerprint("app-1", a), transform.fingerprint("app-1", b))
    }

    @Test
    fun `different appIds produce different fingerprints for same error`() = runTest {
        val e = ErrorInfo(message = "x", type = "E", stackTrace = "at com.example.Foo.bar(Foo.kt:1)")
        assertNotEquals(transform.fingerprint("app-1", e), transform.fingerprint("app-2", e))
    }

    @Test
    fun `empty or null stack still yields a stable fingerprint`() = runTest {
        val a = ErrorInfo(message = "x", type = "E", stackTrace = null)
        val b = ErrorInfo(message = "x", type = "E", stackTrace = "")
        val fpA = transform.fingerprint("app-1", a)
        val fpB = transform.fingerprint("app-1", b)
        assertNotNull(fpA)
        assertEquals(fpA, fpB)
    }

    @Test
    fun `null type falls back to UnknownError bucket`() = runTest {
        val a = ErrorInfo(message = "x", type = null, stackTrace = "at com.example.Foo.bar(Foo.kt:1)")
        val b = ErrorInfo(message = "x", type = "  ", stackTrace = "at com.example.Foo.bar(Foo.kt:1)")
        assertEquals(transform.fingerprint("app-1", a), transform.fingerprint("app-1", b))
    }

    @Test
    fun `non-error events left untouched when error events also present`() = runTest {
        val nonError = Event(created = 0L, type = EventType.Session)
        val err = errorEvent(ErrorInfo(message = "x", type = "E", stackTrace = "at com.example.Foo.bar(Foo.kt:1)"))
        val result = transform.transform(ctx(), events("app-1", nonError, err))
        assertEquals(nonError, result.events[0])
        assertNotNull(result.events[1].error?.fingerprint)
        assertNull(result.events[0].error)
    }

    @Test
    fun `mixed errors cover missing context existing fingerprints and later transformations`() = runTest {
        val needsFingerprint = errorEvent(ErrorInfo(message = "new", type = "E", stackTrace = "at a.b(C.kt:1)"))
        val existing = errorEvent(ErrorInfo(message = "old", type = "E", fingerprint = "existing"))
        val input = Events(
            context = null,
            events = listOf(
                needsFingerprint,
                Event(created = 1, type = EventType.Session),
                Event(created = 2, type = EventType.Error, error = null),
                existing,
                needsFingerprint.copy(created = 3),
            ),
            sent = 0,
            sentMicros = 0,
        )

        val result = transform.transform(ctx(), input)
        assertNotNull(result.events.first().error?.fingerprint)
        assertEquals("existing", result.events[3].error?.fingerprint)
        assertNotNull(result.events.last().error?.fingerprint)
    }

    @Test
    fun `stack normalization ignores noise and caps the fingerprint frame set`() {
        val stack = buildString {
            appendLine("not a frame")
            appendLine()
            repeat(7) { appendLine("at example.Type.method$it(File.kt:${it + 1}:4)") }
        }
        val withExtraFrames = ErrorInfo(message = "x", type = "E", stackTrace = stack)
        val firstFive = ErrorInfo(
            message = "x",
            type = "E",
            stackTrace = (0 until 5).joinToString("\n") { "at example.Type.method$it(File.kt:${it + 20}:9)" },
        )
        assertEquals(transform.fingerprint("app", firstFive), transform.fingerprint("app", withExtraFrames))
    }
}
