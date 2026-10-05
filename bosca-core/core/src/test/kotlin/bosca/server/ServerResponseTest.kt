package bosca.server

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerResponseTest {

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { channel.isWritable } returns true
        every { ctx.channel() } returns channel
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } returns future
        every { ctx.write(any()) } returns future
        return ctx
    }

    @Test
    fun `status starts as null`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        assertNull(response.status())
    }

    @Test
    fun `status can be set`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.status(HttpStatusCode.NotFound)
        assertEquals(HttpStatusCode.NotFound, response.status())
    }

    @Test
    fun `isCommitted starts as false`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        assertFalse(response.isCommitted)
    }

    @Test
    fun `commit sets isCommitted to true`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.commit()
        assertTrue(response.isCommitted)
    }

    @Test
    fun `closeChannelAfterWrite closes immediately before a write and attaches after a write`() {
        val immediateContext = createMockCtx()
        ServerResponse(immediateContext).closeChannelAfterWrite()
        verify(exactly = 1) { immediateContext.close() }

        val writtenContext = createMockCtx()
        val future = mockk<ChannelFuture>(relaxed = true)
        every { writtenContext.writeAndFlush(any()) } returns future
        val writtenResponse = ServerResponse(writtenContext)
        writtenResponse.commit()
        writtenResponse.closeChannelAfterWrite()

        verify(exactly = 1) { future.addListener(ChannelFutureListener.CLOSE) }
        verify(exactly = 0) { writtenContext.close() }
    }

    @Test
    fun `commit is idempotent`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.commit()
        response.commit()
        assertTrue(response.isCommitted)
        // writeAndFlush called only once
        verify(exactly = 1) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `markCommitted invokes before-write callback exactly once`() {
        val response = ServerResponse(createMockCtx())
        var callbackCount = 0
        response.onBeforeWrite { callbackCount++ }

        response.markCommitted()
        response.markCommitted()

        assertTrue(response.isCommitted)
        assertEquals(1, callbackCount)
    }

    @Test
    fun `failed before-write callback leaves response available for an error response`() {
        val ctx = createMockCtx()
        val written = slot<Any>()
        every { ctx.writeAndFlush(capture(written)) } returns mockk(relaxed = true)
        val response = ServerResponse(ctx)
        var callbackCount = 0
        response.onBeforeWrite {
            callbackCount++
            error("expected before-write failure")
        }

        assertFailsWith<IllegalStateException> { response.markCommitted() }

        assertFalse(response.isCommitted)
        response.respond(HttpStatusCode.InternalServerError)
        assertTrue(response.isCommitted)
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, (written.captured as DefaultFullHttpResponse).status())
        assertEquals(1, callbackCount, "A failed before-write callback must not poison the fallback response")
    }

    @Test
    fun `commit sends DefaultFullHttpResponse`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.status(HttpStatusCode.OK)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        response.commit()

        val sent = captured.captured
        assertTrue(sent is DefaultFullHttpResponse)
        assertEquals(HttpResponseStatus.OK, sent.status())
    }

    @Test
    fun `commit defaults to 200 OK when no status set`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.OK, sent.status())
    }

    @Test
    fun `commit includes custom headers`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.header("X-Custom", "value1")
        response.header("X-Custom", "value2")
        response.header("X-Other", "test")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(listOf("value1", "value2"), sent.headers().getAll("X-Custom"))
        assertEquals("test", sent.headers().get("X-Other"))
    }

    @Test
    fun `commit includes content length`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals("0", sent.headers().get(HttpHeaderNames.CONTENT_LENGTH))
    }

    @Test
    fun `commit includes cookies as Set-Cookie headers`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.cookies.append("session", "abc123")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val setCookieHeaders = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertTrue(setCookieHeaders.any { it.contains("session=abc123") })
    }

    @Test
    fun `applyHeadersTo copies sanitized headers and cookies`() {
        val response = ServerResponse(createMockCtx())
        response.header("X-Test", "safe\r\nvalue\tend")
        response.cookies.append("session", "value")
        val headers = io.netty.handler.codec.http.DefaultHttpHeaders()

        response.applyHeadersTo(headers)

        assertEquals("safevalue end", headers.get("x-test"))
        assertTrue(headers.getAll(HttpHeaderNames.SET_COOKIE).single().contains("session=value"))
    }

    @Test
    fun `markCommitted prevents normal response writes`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.markCommitted()

        response.respond(HttpStatusCode.Accepted)

        assertTrue(response.isCommitted)
        verify(exactly = 0) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `commit invokes before-write callback once`() {
        val response = ServerResponse(createMockCtx())
        var callbacks = 0
        response.onBeforeWrite { callbacks++ }

        response.commit()
        response.commit()

        assertEquals(1, callbacks)
    }

    @Test
    fun `second commit releases an unused body`() {
        val response = ServerResponse(createMockCtx())
        response.commit()
        val unused = Unpooled.buffer().writeByte(1)

        response.commit(unused)

        assertEquals(0, unused.refCnt())
    }

    @Test
    fun `failed before-write callback releases an uncommitted response body`() {
        val response = ServerResponse(createMockCtx())
        response.onBeforeWrite { error("expected before-write failure") }
        val body = Unpooled.buffer().writeByte(1)

        assertFailsWith<IllegalStateException> { response.commit(body) }

        assertEquals(0, body.refCnt())
        assertFalse(response.isCommitted)
    }

    @Test
    fun `failed before-write callback also restores an empty commit`() {
        val response = ServerResponse(createMockCtx())
        response.onBeforeWrite { error("expected before-write failure") }

        assertFailsWith<IllegalStateException> { response.commit() }

        assertFalse(response.isCommitted)
    }

    @Test
    fun `suppressed commit sends no body while retaining original content length`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.suppressBody = true
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.commit(Unpooled.copiedBuffer("body", Charsets.UTF_8))

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(0, sent.content().readableBytes())
        assertEquals("4", sent.headers().get(HttpHeaderNames.CONTENT_LENGTH))
    }

    @Test
    fun `suppressed empty commit and caller content length retain their headers`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.suppressBody = true
        response.header(HttpHeaders.ContentLength, "12")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.commit()

        assertEquals("12", (captured.captured as DefaultFullHttpResponse).headers().get(HttpHeaderNames.CONTENT_LENGTH))
    }

    @Test
    fun `respondText sends text with content type`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondText("Hello", ContentType.Text.Plain)

        assertTrue(response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.OK, sent.status())
        assertTrue(sent.headers().get(HttpHeaderNames.CONTENT_TYPE).contains("text/plain"))
        val body = ByteArray(sent.content().readableBytes())
        sent.content().readBytes(body)
        assertEquals("Hello", String(body))
    }

    @Test
    fun `respondText sanitizes generated content type header`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondText("Hello", ContentType("text", "plain", mapOf("name" to "safe\r\nvalue\tend")))

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals("text/plain; name=\"safevalue end\"", sent.headers().get(HttpHeaderNames.CONTENT_TYPE))
    }

    @Test
    fun `respondText with custom status`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondText("Created", ContentType.Text.Plain, HttpStatusCode.Created)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.valueOf(201), sent.status())
    }

    @Test
    fun `respondBytes sends bytes with content type`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val data = byteArrayOf(1, 2, 3, 4, 5)
        response.respondBytes(data, ContentType.Application.OctetStream)

        assertTrue(response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(5, sent.content().readableBytes())
    }

    @Test
    fun `respondBytes applies cookies and sends its body's own length over one the route set`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.cookies.append("session", "value")
        // Set for a download that then failed: the error body must not claim the download's size.
        response.header(HttpHeaders.ContentLength, "99")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondBytes("body".toByteArray(), ContentType.Text.Plain)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals("4", sent.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        assertTrue(sent.headers().contains(HttpHeaderNames.SET_COOKIE))
    }

    @Test
    fun `a bodiless response replaces a length the route set for a body it never sent`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.header(HttpHeaders.ContentLength, "99")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respond(HttpStatusCode.NotFound)

        assertEquals("0", (captured.captured as DefaultFullHttpResponse).headers().get(HttpHeaderNames.CONTENT_LENGTH))
    }

    @Test
    fun `respond without body commits requested status and exposes channel context`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        assertTrue(response.channelContext() === ctx)
        response.respond(HttpStatusCode.Accepted)

        assertEquals(HttpResponseStatus.ACCEPTED, (captured.captured as DefaultFullHttpResponse).status())
    }

    @Test
    fun `respondBytes suppresses HEAD body but reports its length and cookies`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.suppressBody = true
        response.cookies.append("head", "cookie")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondBytes("body".toByteArray(), ContentType.Text.Plain, HttpStatusCode.Accepted)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.ACCEPTED, sent.status())
        assertEquals(0, sent.content().readableBytes())
        assertEquals("4", sent.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        assertTrue(sent.headers().contains(HttpHeaderNames.SET_COOKIE))
    }

    @Test
    fun `respondBytes preserves caller content length when suppressing a HEAD body`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.suppressBody = true
        response.header(HttpHeaders.ContentLength, "99")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondBytes("body".toByteArray(), ContentType.Text.Plain)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(0, sent.content().readableBytes())
        assertEquals("99", sent.headers().get(HttpHeaderNames.CONTENT_LENGTH))
    }

    @Test
    fun `respondBytes releases its buffer when population fails`() {
        val ctx = createMockCtx()
        val allocator = mockk<ByteBufAllocator>()
        val buffer = mockk<io.netty.buffer.ByteBuf>(relaxed = true)
        every { ctx.alloc() } returns allocator
        every { allocator.buffer(4) } returns buffer
        every { buffer.writeBytes(any<ByteArray>()) } throws IllegalStateException("write failed")
        val response = ServerResponse(ctx)

        assertFailsWith<IllegalStateException> {
            response.respondBytes(byteArrayOf(1, 2, 3, 4), ContentType.Application.OctetStream)
        }

        verify(exactly = 1) { buffer.release() }
    }

    @Test
    fun `respondBytes and respond are no-ops after a prior commit`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.respondText("first")

        response.respondBytes(byteArrayOf(1), ContentType.Application.OctetStream)
        response.respond(HttpStatusCode.Accepted)

        verify(exactly = 1) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `respondRedirect sends 302 with Location header`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondRedirect("https://example.com")

        assertTrue(response.isCommitted)
        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.FOUND, sent.status())
        assertEquals("https://example.com", sent.headers().get("Location"))
    }

    @Test
    fun `respondRedirect permanent sends 301`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondRedirect("https://example.com", permanent = true)

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals(HttpResponseStatus.MOVED_PERMANENTLY, sent.status())
    }

    @Test
    fun `respondRedirect accepts a relative path`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondRedirect("/relative/path")

        assertEquals("/relative/path", (captured.captured as DefaultFullHttpResponse).headers().get(HttpHeaderNames.LOCATION))
    }

    @Test
    fun `respondRedirect accepts an absolute http URL`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondRedirect("http://example.com/path")

        assertEquals(
            "http://example.com/path",
            (captured.captured as DefaultFullHttpResponse).headers().get(HttpHeaderNames.LOCATION),
        )
    }

    @Test
    fun `respondRedirect accepts a colon inside an absolute relative path`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        response.respondRedirect("/documents/chapter:section")

        assertEquals(
            "/documents/chapter:section",
            (captured.captured as DefaultFullHttpResponse).headers().get(HttpHeaderNames.LOCATION),
        )
    }

    @Test
    fun `respondRedirect rejects unsafe schemes and skips after commit`() {
        val response = ServerResponse(createMockCtx())
        assertFailsWith<IllegalArgumentException> { response.respondRedirect("javascript:alert(1)") }

        response.commit()
        response.respondRedirect("data:text/plain,bad")
    }

    @Test
    fun `respondStreaming uses chunked transfer encoding`() = runTest {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)

        val written = mutableListOf<Any>()
        every { ctx.write(capture(written)) } returns mockk(relaxed = true)
        every { ctx.writeAndFlush(capture(written)) } returns mockk(relaxed = true)

        response.respondStreaming(ContentType.Application.Json) { stream ->
            stream.write("hello".toByteArray())
            stream.flush()
            stream.write(" world".toByteArray())
        }

        assertTrue(response.isCommitted)

        // First write should be the response headers
        val headerResponse = written[0]
        assertTrue(headerResponse is DefaultHttpResponse)
        assertEquals(
            HttpHeaderValues.CHUNKED.toString(),
            headerResponse.headers().get(HttpHeaderNames.TRANSFER_ENCODING)
        )

        // Content chunks should follow
        val contentChunks = written.filterIsInstance<DefaultHttpContent>()
        assertTrue(contentChunks.isNotEmpty())

        // Last write should be LastHttpContent
        val last = written.last()
        assertTrue(last is LastHttpContent)
    }

    @Test
    fun `respondStreaming ends a response that reaches its time limit`() = runTest {
        val response = ServerResponse(createMockCtx())

        assertFailsWith<StreamingTimeLimitException> {
            response.respondStreaming(ContentType.Text.Plain, HttpStatusCode.OK, 50.milliseconds) { awaitCancellation() }
        }
    }

    @Test
    fun `respondStreaming with no time limit streams for as long as the block runs`() = runTest {
        val response = ServerResponse(createMockCtx())
        var finished = false

        response.respondStreaming(ContentType.Text.Plain, HttpStatusCode.OK, null) {
            delay(24.hours)
            finished = true
        }

        assertTrue(finished)
    }

    @Test
    fun `respondStreaming passes the block's own timeout through unchanged`() = runTest {
        val response = ServerResponse(createMockCtx())

        // The block's own timeout, not the response's time limit (a different exception type).
        assertFailsWith<TimeoutCancellationException> {
            response.respondStreaming(ContentType.Text.Plain, HttpStatusCode.OK, 1.hours) {
                withTimeout(10) { awaitCancellation() }
            }
        }
    }

    @Test
    fun `respondStreaming rejects a time limit that is not positive`() = runTest {
        val response = ServerResponse(createMockCtx())
        assertFailsWith<IllegalArgumentException> {
            response.respondStreaming(ContentType.Text.Plain, HttpStatusCode.OK, Duration.ZERO) { }
        }
        assertFalse(response.isCommitted)
    }

    @Test
    fun `respondStreaming does not commit twice`() = runTest {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)

        response.respondText("first")
        response.respondStreaming(ContentType.Text.Plain) { stream ->
            stream.write("second".toByteArray())
        }

        // Only the first respondText should have committed
        verify(exactly = 1) { ctx.writeAndFlush(any()) }
    }

    @Test
    fun `respondStreaming suppresses body for HEAD requests`() = runTest {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.suppressBody = true
        response.cookies.append("head", "cookie")
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        var blockCalled = false

        response.respondStreaming(ContentType.Text.Plain, HttpStatusCode.Accepted) {
            blockCalled = true
        }

        val sent = captured.captured as DefaultFullHttpResponse
        assertFalse(blockCalled)
        assertEquals(HttpResponseStatus.ACCEPTED, sent.status())
        assertTrue(sent.headers().contains(HttpHeaderNames.SET_COOKIE))
    }

    @Test
    fun `respondStreaming preserves fixed content length`() = runTest {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        response.header(HttpHeaders.ContentLength, "0")
        val written = mutableListOf<Any>()
        every { ctx.writeAndFlush(capture(written)) } returns mockk(relaxed = true)

        response.respondStreaming(ContentType.Text.Plain) { }

        val headers = written.filterIsInstance<DefaultHttpResponse>().single().headers()
        assertEquals("0", headers.get(HttpHeaderNames.CONTENT_LENGTH))
        assertFalse(headers.contains(HttpHeaderNames.TRANSFER_ENCODING))
    }

    @Test
    fun `respondStreaming closes the channel when the producer fails`() = runTest {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)

        assertFailsWith<IllegalStateException> {
            response.respondStreaming(ContentType.Text.Plain) {
                throw IllegalStateException("producer failed")
            }
        }

        verify(exactly = 1) { ctx.close() }
    }

    @Test
    fun `channelContext returns the underlying context`() {
        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        assertEquals(ctx, response.channelContext())
    }
}
