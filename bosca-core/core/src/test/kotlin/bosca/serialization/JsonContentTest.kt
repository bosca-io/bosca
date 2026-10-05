package bosca.serialization

import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonContentTest {

    @Serializable
    private data class Payload(val name: String, val count: Int)

    private val scope = mockk<Scope>(relaxed = true)
    private val span = mockk<Span>(relaxed = true)
    private val builder = mockk<SpanBuilder>()
    private val tracer = mockk<Tracer>()

    init {
        every { tracer.spanBuilder("JsonContent.writeTo") } returns builder
        every { builder.setParent(any()) } returns builder
        every { builder.startSpan() } returns span
        every { span.makeCurrent() } returns scope
    }

    @Test
    fun `content exposes JSON metadata and writes serialized UTF-8 bytes`() = runTest {
        val content = JsonContent(tracer, Json, Payload("bosca", 7), Payload.serializer())
        val element = content.asJsonElement().jsonObject
        assertEquals("bosca", element.getValue("name").jsonPrimitive.content)
        assertEquals(7, element.getValue("count").jsonPrimitive.content.toInt())
        assertEquals(ContentType.Application.Json, content.contentType)

        val call = mockk<ServerCall>()
        coEvery { call.respondBytes(any(), any(), any()) } returns Unit
        content.writeTo(call, HttpStatusCode.Created)

        coVerify {
            call.respondBytes(
                match { String(it) == "{\"name\":\"bosca\",\"count\":7}" },
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }
        verify { span.end() }
    }

    @Test
    fun `write ends span when response fails`() = runTest {
        val content = JsonContent(tracer, Json, Payload("failure", 1), Payload.serializer())
        val call = mockk<ServerCall>()
        coEvery { call.respondBytes(any(), any(), any()) } throws IllegalStateException("closed")

        assertFailsWith<IllegalStateException> { content.writeTo(call, HttpStatusCode.OK) }
        verify { span.end() }
        verify { scope.close() }
    }
}
