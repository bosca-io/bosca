package bosca.server

import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerCallTest {

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } returns future
        every { ctx.write(any()) } returns future
        return ctx
    }

    private fun createCall(ctx: ChannelHandlerContext = createMockCtx()): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        return ServerCall(request, response, Parameters.Empty, app)
    }

    @Serializable
    data class TestData(val name: String, val value: Int)

    @Test
    fun `attributes retain values after lazy initialization`() {
        val call = createCall()

        call.attributes["key"] = "value"

        assertEquals("value", call.attributes["key"])
    }

    // --- respond(status, message) with reified type ---

    @Test
    fun `respond with String sends plain text`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, "hello world")

        assertTrue(call.response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.OK, sent.status())
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("text/plain"))
    }

    @Test
    fun `respond with ByteArray sends octet-stream`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, byteArrayOf(1, 2, 3))

        assertTrue(call.response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("application/octet-stream"))
        assertEquals(3, sent.content().readableBytes())
    }

    @Test
    fun `respond with Unit commits with status only`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.NoContent, Unit)

        assertTrue(call.response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.valueOf(204), sent.status())
    }

    @Test
    fun `respond with serializable object sends JSON`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, TestData("test", 42))

        assertTrue(call.response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("application/json"))
        val body = ByteArray(sent.content().readableBytes())
        sent.content().readBytes(body)
        val json = String(body)
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"test\""))
        assertTrue(json.contains("42"))
    }

    @Test
    fun `respond with default status uses 200 OK`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond("hello")

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.OK, sent.status())
    }

    @Test
    fun `respond throws when already committed`() = runTest {
        val call = createCall()
        call.respond(HttpStatusCode.OK, "first")

        assertFailsWith<IllegalStateException> {
            call.respond(HttpStatusCode.OK, "second")
        }
    }

    // --- respond(status, message, serializer) ---

    @Test
    fun `respond with explicit serializer sends JSON`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, TestData("explicit", 99), TestData.serializer())

        assertTrue(call.response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("application/json"))
        val body = ByteArray(sent.content().readableBytes())
        sent.content().readBytes(body)
        assertTrue(String(body).contains("explicit"))
    }

    @Test
    fun `respond with String and null serializer sends text`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, "text response", null)

        val sent = captured.captured as DefaultFullHttpResponse
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("text/plain"))
    }

    @Test
    fun `respond with ByteArray and null serializer sends bytes`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, byteArrayOf(0xCA.toByte()), null)

        val sent = captured.captured as DefaultFullHttpResponse
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("application/octet-stream"))
    }

    @Test
    fun `respond with Unit and null serializer commits`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.Accepted, Unit, null)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.valueOf(202), sent.status())
    }

    @Test
    fun `respond with HttpStatusCode message commits with that status`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, HttpStatusCode.NotFound, null)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.NOT_FOUND, sent.status())
    }

    @Test
    fun `respond with null serializer for non-builtin type throws`() = runTest {
        val call = createCall()

        assertFailsWith<IllegalArgumentException> {
            call.respond(HttpStatusCode.OK, TestData("x", 1), null)
        }
    }

    @Test
    fun `respond with serializer and default status`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respond(TestData("default", 0), TestData.serializer())

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.OK, sent.status())
    }

    @Test
    fun `respond with explicit serializer already committed throws`() = runTest {
        val call = createCall()
        call.respond(HttpStatusCode.OK, "first", null)

        assertFailsWith<IllegalStateException> {
            call.respond(HttpStatusCode.OK, "second", null)
        }
    }

    // --- respondRedirect ---

    @Test
    fun `respondRedirect sends 302`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respondRedirect("https://example.com")

        assertTrue(call.response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.FOUND, sent.status())
        assertEquals("https://example.com", sent.headers().get("Location"))
    }

    @Test
    fun `respondRedirect permanent sends 301`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respondRedirect("https://example.com", permanent = true)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.MOVED_PERMANENTLY, sent.status())
    }

    // --- respondBytes ---

    @Test
    fun `respondBytes sends with status and content type`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respondBytes(byteArrayOf(1, 2), ContentType.Image.Png, HttpStatusCode.Created)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.valueOf(201), sent.status())
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("image/png"))
    }

    // --- receiveParameters ---

    @Test
    fun `receiveParameters delegates to request`() = runTest {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.contentType() } returns null
        io.mockk.coEvery { request.receiveFormParameters() } returns Parameters.Empty
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        val call = ServerCall(request, response, Parameters.Empty, app)

        val params = call.receiveParameters()
        assertTrue(params.isEmpty)
    }

    // --- receive throws for form data ---

    @Test
    fun `receive throws for form-urlencoded content type`() = runTest {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.contentType() } returns ContentType("application", "x-www-form-urlencoded")
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        val call = ServerCall(request, response, Parameters.Empty, app)

        assertFailsWith<UnsupportedOperationException> {
            call.receive<String>()
        }
    }

    // --- receive<T> for JSON ---

    @Test
    fun `receive deserializes JSON body`() = runTest {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.contentType() } returns ContentType("application", "json")
        io.mockk.coEvery { request.bodyText() } returns """{"name":"test","value":42}"""
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        val call = ServerCall(request, response, Parameters.Empty, app)

        val result = call.receive<TestData>()
        assertEquals("test", result.name)
        assertEquals(42, result.value)
    }

    // --- receiveMultipart ---

    @Test
    fun `receiveMultipart delegates to request`() = runTest {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        val multipart = bosca.server.content.MultiPartData(emptyList())
        io.mockk.coEvery { request.receiveMultipart() } returns multipart
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        val call = ServerCall(request, response, Parameters.Empty, app)

        val result = call.receiveMultipart()
        assertTrue(result.parts.isEmpty())
    }

    // --- respondStreaming ---

    @Test
    fun `respondStreaming sends chunked response`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } returns mockk(relaxed = true)
        every { ctx.write(capture(written)) } returns mockk(relaxed = true)
        every { ctx.flush() } returns ctx

        val call = createCall(ctx)
        call.respondStreaming(ContentType.Text.Plain) { stream ->
            stream.write("hello".toByteArray())
        }

        assertTrue(call.response.isCommitted)
        // First write should be the chunked header response
        val headerResponse = written.filterIsInstance<io.netty.handler.codec.http.DefaultHttpResponse>().first()
        assertEquals("chunked", headerResponse.headers().get("Transfer-Encoding"))
    }

    // --- ServerResponseContent ---

    @Test
    fun `respond with ServerResponseContent calls writeTo`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val writtenStatus = mutableListOf<HttpStatusCode>()
        val content = object : ServerResponseContent {
            override suspend fun writeTo(call: ServerCall, status: HttpStatusCode) {
                writtenStatus.add(status)
                call.response.status(status)
                call.response.commit()
            }
        }

        val call = createCall(ctx)
        call.respond(HttpStatusCode.Created, content)
        assertEquals(1, writtenStatus.size)
        assertEquals(HttpStatusCode.Created, writtenStatus[0])
    }

    @Test
    fun `respond with ServerResponseContent via explicit serializer`() = runTest {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val writtenStatus = mutableListOf<HttpStatusCode>()
        val content = object : ServerResponseContent {
            override suspend fun writeTo(call: ServerCall, status: HttpStatusCode) {
                writtenStatus.add(status)
                call.response.status(status)
                call.response.commit()
            }
        }

        val call = createCall(ctx)
        call.respond(HttpStatusCode.OK, content, null)
        assertEquals(1, writtenStatus.size)
    }

    // --- respondBytes on ServerCall ---

    @Test
    fun `respondBytes sends bytes with content type`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val call = createCall(ctx)
        call.respondBytes(byteArrayOf(1, 2, 3), ContentType.Application.OctetStream)

        assertTrue(call.response.isCommitted)
    }

    @Test
    fun `remaining response dispatch variants commit expected representations`() = runTest {
        val statusContext = createMockCtx()
        val statusSent = slot<Any>()
        every { statusContext.writeAndFlush(capture(statusSent)) } returns mockk(relaxed = true)
        createCall(statusContext).respond(HttpStatusCode.Accepted)
        assertEquals(HttpResponseStatus.valueOf(202), (statusSent.captured as DefaultFullHttpResponse).status())

        val reifiedContext = createMockCtx()
        val reifiedSent = slot<Any>()
        every { reifiedContext.writeAndFlush(capture(reifiedSent)) } returns mockk(relaxed = true)
        createCall(reifiedContext).respond(HttpStatusCode.OK, HttpStatusCode.Conflict)
        assertEquals(HttpResponseStatus.valueOf(409), (reifiedSent.captured as DefaultFullHttpResponse).status())

        val jsonContext = createMockCtx()
        val jsonSent = slot<Any>()
        every { jsonContext.writeAndFlush(capture(jsonSent)) } returns mockk(relaxed = true)
        createCall(jsonContext).respond(HttpStatusCode.Created, JsonPrimitive("value"), null)
        val jsonResponse = jsonSent.captured as DefaultFullHttpResponse
        assertTrue(jsonResponse.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("application/json"))

        assertFailsWith<IllegalArgumentException> {
            createCall().respond<Any?>(HttpStatusCode.OK, null, null)
        }
    }

    @Test
    fun `receive rejects multipart and empty bodies and cleanup is idempotent`() = runTest {
        fun callFor(request: ServerRequest): ServerCall {
            val config = mockk<ApplicationConfig>(relaxed = true)
            every { config.propertyOrNull(any()) } returns null
            return ServerCall(request, ServerResponse(createMockCtx()), Parameters.Empty, BoscaApplication(config))
        }

        val multipartRequest = mockk<ServerRequest>(relaxed = true)
        every { multipartRequest.contentType() } returns ContentType("multipart", "form-data")
        assertFailsWith<UnsupportedOperationException> { callFor(multipartRequest).receive<TestData>() }

        val emptyRequest = mockk<ServerRequest>(relaxed = true)
        every { emptyRequest.contentType() } returns ContentType.Application.Json
        every { emptyRequest.httpMethod } returns HttpMethod.Post
        io.mockk.coEvery { emptyRequest.bodyText() } returns ""
        assertFailsWith<IllegalStateException> { callFor(emptyRequest).receive<TestData>() }

        val data = mockk<bosca.server.content.MultiPartData>(relaxed = true)
        val cleanupRequest = mockk<ServerRequest>(relaxed = true)
        io.mockk.coEvery { cleanupRequest.receiveMultipart() } returns data
        val cleanupCall = callFor(cleanupRequest)
        assertEquals(data, cleanupCall.receiveMultipart())
        cleanupCall.cleanup()
        cleanupCall.cleanup()
        io.mockk.verify(exactly = 1) { data.close() }
    }
}
