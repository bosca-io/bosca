package bosca.server.netty

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannel
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutor
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressionOffloadHandlerTest {

    @Test
    fun `identity response stays on synchronous pass-through path`() = withHandler { channel, _ ->
        writeRequest(channel, "identity")
        val content = Unpooled.copiedBuffer("plaintext", StandardCharsets.UTF_8)
        val response = fullResponse(content, "text/plain")

        assertTrue(channel.writeOutbound(response))

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertNull(written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        assertEquals(setOf("accept-encoding"), written.varyTokens())
        assertEquals("plaintext", written.content().toString(StandardCharsets.UTF_8))
        written.release()
    }

    @Test
    fun `gzip full response is encoded on ordered executor`() = withHandler { channel, executor ->
        val body = "compressible-content-".repeat(128)
        writeRequest(channel, "gzip")
        val content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
        val response = fullResponse(content, "text/plain")
        response.headers().set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN)

        writeAndDrainOffload(channel, executor, response)

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertEquals("gzip", written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        assertEquals(setOf("origin", "accept-encoding"), written.varyTokens())
        assertEquals(written.content().readableBytes(), written.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
        assertEquals(body, gunzip(written.content().copyBytes()))
        written.release()
    }

    @Test
    fun `streaming response preserves chunks trailers and gzip stream`() = withHandler { channel, executor ->
        writeRequest(channel, "gzip")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
        val first = DefaultHttpContent(Unpooled.copiedBuffer("first-", StandardCharsets.UTF_8))
        val last = LastHttpContent.EMPTY_LAST_CONTENT.replace(
            Unpooled.copiedBuffer("second", StandardCharsets.UTF_8),
        )
        last.trailingHeaders().set("x-stream-complete", "true")

        val responseWrite = channel.writeOneOutbound(response)
        val firstWrite = channel.writeOneOutbound(first)
        val lastWrite = channel.writeOneOutbound(last)
        channel.flushOutbound()
        drainOffload(channel, executor)

        assertTrue(responseWrite.isSuccess, responseWrite.cause()?.message)
        assertTrue(firstWrite.isSuccess, firstWrite.cause()?.message)
        assertTrue(lastWrite.isSuccess, lastWrite.cause()?.message)

        val writtenResponse = channel.readOutbound<HttpResponse>()
        assertEquals("gzip", writtenResponse.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        val encoded = ArrayList<Byte>()
        var writtenLast: LastHttpContent? = null
        while (true) {
            val message = channel.readOutbound<Any>() ?: break
            if (message is HttpContent) {
                val bytes = message.content().copyBytes()
                bytes.forEach(encoded::add)
                if (message is LastHttpContent) writtenLast = message
            }
            if (message !== writtenLast) ReferenceCountUtil.safeRelease(message)
        }

        assertEquals("first-second", gunzip(encoded.toByteArray()))
        assertEquals("true", writtenLast?.trailingHeaders()?.get("x-stream-complete"))
        ReferenceCountUtil.safeRelease(writtenLast)
    }

    @Test
    fun `incompressible response stays on synchronous pass-through path`() = withHandler { channel, _ ->
        writeRequest(channel, "gzip")
        val content = Unpooled.wrappedBuffer(ByteArray(4096) { it.toByte() })
        val response = fullResponse(content, "application/octet-stream")

        assertTrue(channel.writeOutbound(response))

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertNull(written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        assertTrue(written.varyTokens().isEmpty())
        assertEquals(4096, written.content().readableBytes())
        written.release()
    }

    @Test
    fun `existing vary wildcard or accept encoding is not duplicated`() = withHandler { channel, _ ->
        writeRequest(channel, "identity")
        writeRequest(channel, "identity")
        val wildcard = fullResponse(Unpooled.copiedBuffer("wildcard", StandardCharsets.UTF_8), "text/plain")
        wildcard.headers().set(HttpHeaderNames.VARY, "*")
        val explicit = fullResponse(Unpooled.copiedBuffer("explicit", StandardCharsets.UTF_8), "text/plain")
        explicit.headers().set(HttpHeaderNames.VARY, "Origin, ACCEPT-ENCODING")

        assertTrue(channel.writeOutbound(wildcard))
        assertTrue(channel.writeOutbound(explicit))

        val writtenWildcard = channel.readOutbound<DefaultFullHttpResponse>()
        val writtenExplicit = channel.readOutbound<DefaultFullHttpResponse>()
        assertEquals(listOf("*"), writtenWildcard.headers().getAll(HttpHeaderNames.VARY))
        assertEquals(listOf("Origin, ACCEPT-ENCODING"), writtenExplicit.headers().getAll(HttpHeaderNames.VARY))
        writtenWildcard.release()
        writtenExplicit.release()
    }

    @Test
    fun `later pass-through response cannot overtake compressed response`() = withHandler { channel, executor ->
        writeRequest(channel, "gzip")
        writeRequest(channel, "identity")
        val firstContent = Unpooled.copiedBuffer("first".repeat(256), StandardCharsets.UTF_8)
        val secondContent = Unpooled.copiedBuffer("second", StandardCharsets.UTF_8)

        channel.writeOutbound(fullResponse(firstContent, "text/plain", HttpResponseStatus.OK))
        channel.writeOutbound(fullResponse(secondContent, "text/plain", HttpResponseStatus.CREATED))
        drainOffload(channel, executor)

        val first = channel.readOutbound<DefaultFullHttpResponse>()
        val second = channel.readOutbound<DefaultFullHttpResponse>()
        assertEquals(HttpResponseStatus.OK, first.status())
        assertEquals("gzip", first.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        assertEquals(HttpResponseStatus.CREATED, second.status())
        assertNull(second.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        first.release()
        second.release()
    }

    @Test
    fun `queued compression participates in channel writability`() {
        val group = DefaultEventExecutorGroup(1)
        val executor = group.next()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        val channel = EmbeddedChannel(CompressionOffloadHandler(executor))
        channel.config().writeBufferWaterMark = WriteBufferWaterMark(256, 512)
        try {
            writeRequest(channel, "gzip")
            val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
            channel.writeOneOutbound(response)
            channel.writeOneOutbound(
                DefaultLastHttpContent(Unpooled.wrappedBuffer(ByteArray(1024) { 'a'.code.toByte() })),
            )

            assertFalse(channel.isWritable, "queued uncompressed content must apply backpressure")

            releaseBlocker.countDown()
            drainOffload(channel, executor)
            assertTrue(channel.isWritable, "draining the compression queue must restore writability")
        } finally {
            releaseBlocker.countDown()
            drainOffload(channel, executor)
            channel.finishAndReleaseAll()
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `removing handler while compression is queued restores channel writability`() {
        val group = DefaultEventExecutorGroup(1)
        val executor = group.next()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        val handler = CompressionOffloadHandler(executor)
        val channel = EmbeddedChannel(handler)
        channel.config().writeBufferWaterMark = WriteBufferWaterMark(256, 512)
        try {
            writeRequest(channel, "gzip")
            val response = fullResponse(
                Unpooled.wrappedBuffer(ByteArray(1024) { 'a'.code.toByte() }),
                "text/plain",
            )
            assertFalse(channel.writeOutbound(response))
            assertFalse(channel.isWritable)

            channel.pipeline().remove(handler)

            assertTrue(channel.isWritable, "handler removal must release its writability reservation")
        } finally {
            releaseBlocker.countDown()
            drainOffload(channel, executor)
            channel.finishAndReleaseAll()
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `queued compression participates in real HTTP2 stream writability`() {
        val ioGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val compressionGroup = DefaultEventExecutorGroup(1)
        val compressionExecutor = compressionGroup.next()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        compressionExecutor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        val server = ServerBootstrap()
            .group(ioGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forServer().build())
                    ch.pipeline().addLast(Http2MultiplexHandler(emptyChannelInitializer()))
                }
            })
            .bind("127.0.0.1", 0)
            .syncUninterruptibly()
            .channel()
        val connection = Bootstrap()
            .group(ioGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build())
                    ch.pipeline().addLast(Http2MultiplexHandler(emptyChannelInitializer()))
                }
            })
            .connect(server.localAddress())
            .syncUninterruptibly()
            .channel()
        val stream = Http2StreamChannelBootstrap(connection)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(object : ChannelOutboundHandlerAdapter() {
                        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                            ReferenceCountUtil.release(msg)
                            promise.trySuccess()
                        }
                    })
                    ch.pipeline().addLast(CompressionOffloadHandler(compressionExecutor))
                }
            })
            .open()
            .syncUninterruptibly()
            .getNow() as Http2StreamChannel
        stream.config().writeBufferWaterMark = WriteBufferWaterMark(256, 512)

        try {
            stream.eventLoop().submit {
                val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
                request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
                stream.pipeline().fireChannelRead(request)
            }.syncUninterruptibly()

            val response = fullResponse(
                Unpooled.wrappedBuffer(ByteArray(1024) { 'a'.code.toByte() }),
                "text/plain",
            )
            val write = stream.writeAndFlush(response)
            stream.eventLoop().submit {}.syncUninterruptibly()

            assertFalse(stream.isWritable, "queued compression must apply HTTP/2 stream backpressure")

            releaseBlocker.countDown()
            write.syncUninterruptibly()
            stream.eventLoop().submit {}.syncUninterruptibly()
            assertTrue(stream.isWritable, "draining compression must restore HTTP/2 stream writability")
        } finally {
            releaseBlocker.countDown()
            stream.close().syncUninterruptibly()
            connection.close().syncUninterruptibly()
            server.close().syncUninterruptibly()
            compressionGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
            ioGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `responses without an encodable body pass through synchronously`() = withHandler { channel, _ ->
        data class Case(
            val method: HttpMethod,
            val status: HttpResponseStatus,
            val version: HttpVersion = HttpVersion.HTTP_1_1,
            val body: String = "body",
        )

        val cases = listOf(
            Case(HttpMethod.GET, HttpResponseStatus.NO_CONTENT),
            Case(HttpMethod.GET, HttpResponseStatus.NOT_MODIFIED),
            Case(HttpMethod.GET, HttpResponseStatus.OK, HttpVersion.HTTP_1_0),
            Case(HttpMethod.HEAD, HttpResponseStatus.OK),
            Case(HttpMethod.CONNECT, HttpResponseStatus.OK),
            Case(HttpMethod.GET, HttpResponseStatus.OK, body = ""),
        )

        cases.forEach { case ->
            writeRequest(channel, "gzip", case.method)
            val response = fullResponse(
                Unpooled.copiedBuffer(case.body, StandardCharsets.UTF_8),
                "text/plain",
                case.status,
                case.version,
            )

            assertTrue(channel.writeOutbound(response), "${case.method} ${case.status} should pass through")
            val written = channel.readOutbound<DefaultFullHttpResponse>()
            assertNull(written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
            assertTrue(written.varyTokens().isEmpty())
            written.release()
        }

        val responseWithoutRequest = fullResponse(
            Unpooled.copiedBuffer("orphan", StandardCharsets.UTF_8),
            "text/plain",
        )
        assertTrue(channel.writeOutbound(responseWithoutRequest))
        channel.readOutbound<DefaultFullHttpResponse>().release()
    }

    @Test
    fun `informational response does not consume request encoding`() = withHandler { channel, executor ->
        writeRequest(channel, "gzip")

        assertTrue(
            channel.writeOutbound(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)),
        )
        channel.readOutbound<DefaultFullHttpResponse>().release()

        val body = "final-response".repeat(64)
        writeAndDrainOffload(
            channel,
            executor,
            fullResponse(Unpooled.copiedBuffer(body, StandardCharsets.UTF_8), "text/plain"),
        )

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertEquals("gzip", written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        assertEquals(body, gunzip(written.content().copyBytes()))
        written.release()
    }

    @Test
    fun `CONNECT response other than 200 may be compressed`() = withHandler { channel, executor ->
        writeRequest(channel, "gzip", HttpMethod.CONNECT)
        val body = "connect-response".repeat(64)

        writeAndDrainOffload(
            channel,
            executor,
            fullResponse(
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8),
                "text/plain",
                HttpResponseStatus.CREATED,
            ),
        )

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertEquals("gzip", written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
        assertEquals(body, gunzip(written.content().copyBytes()))
        written.release()
    }

    @Test
    fun `missing accept encoding and unrelated outbound messages use pass-through path`() =
        withHandler { channel, _ ->
            writeRequest(channel, null)
            val response = fullResponse(
                Unpooled.copiedBuffer("identity", StandardCharsets.UTF_8),
                "text/plain",
            )
            assertTrue(channel.writeOutbound(response))
            val writtenResponse = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(setOf("accept-encoding"), writtenResponse.varyTokens())
            writtenResponse.release()

            val buffer = Unpooled.copiedBuffer("raw", StandardCharsets.UTF_8)
            assertTrue(channel.writeOutbound(buffer))
            val written = channel.readOutbound<io.netty.buffer.ByteBuf>()
            assertEquals("raw", written.toString(StandardCharsets.UTF_8))
            written.release()
        }

    @Test
    fun `multiple accept encoding headers are combined for negotiation`() = withHandler { channel, executor ->
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
        request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, "deflate")
        request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
        assertTrue(channel.writeInbound(request))
        ReferenceCountUtil.safeRelease(channel.readInbound<Any>())

        writeAndDrainOffload(
            channel,
            executor,
            fullResponse(Unpooled.copiedBuffer("content".repeat(64), StandardCharsets.UTF_8), "text/plain"),
        )

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertTrue(written.headers().contains(HttpHeaderNames.CONTENT_ENCODING))
        written.release()
    }

    @Test
    fun `compressed full response without content length remains chunked`() = withHandler { channel, executor ->
        writeRequest(channel, "gzip")
        val response = fullResponse(
            Unpooled.copiedBuffer("chunked".repeat(128), StandardCharsets.UTF_8),
            "text/plain",
        )
        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH)

        writeAndDrainOffload(channel, executor, response)

        val written = channel.readOutbound<DefaultFullHttpResponse>()
        assertEquals(HttpHeaderValues.CHUNKED.toString(), written.headers().get(HttpHeaderNames.TRANSFER_ENCODING))
        written.release()
    }

    @Test
    fun `identity streaming content stays on synchronous pass-through path`() = withHandler { channel, _ ->
        writeRequest(channel, "identity")
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
        val first = DefaultHttpContent(Unpooled.copiedBuffer("first", StandardCharsets.UTF_8))
        val last = DefaultLastHttpContent(Unpooled.copiedBuffer("last", StandardCharsets.UTF_8))

        assertTrue(channel.writeOutbound(response))
        assertTrue(channel.writeOutbound(first))
        assertTrue(channel.writeOutbound(last))

        val writtenResponse = channel.readOutbound<HttpResponse>()
        assertEquals(setOf("accept-encoding"), writtenResponse.varyTokens())
        ReferenceCountUtil.safeRelease(writtenResponse)
        ReferenceCountUtil.safeRelease(channel.readOutbound<Any>())
        ReferenceCountUtil.safeRelease(channel.readOutbound<Any>())
    }

    @Test
    fun `overlapping streaming responses fail the later response`() {
        val group = DefaultEventExecutorGroup(1)
        val executor = group.next()
        val exceptions = ExceptionCapture()
        val channel = EmbeddedChannel(CompressionOffloadHandler(executor), exceptions)
        try {
            writeRequest(channel, "gzip")
            writeRequest(channel, "gzip")
            val first = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            first.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
            val second = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            second.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")

            channel.writeOneOutbound(first)
            val secondWrite = channel.writeOneOutbound(second)
            drainOffload(channel, executor)
            channel.flushOutbound()

            assertFalse(secondWrite.isSuccess)
            assertEquals(1, exceptions.causes.size)
            assertIs<IllegalStateException>(exceptions.causes.single())
            val written = channel.readOutbound<HttpResponse>()
            assertEquals("gzip", written.headers().get(HttpHeaderNames.CONTENT_ENCODING))
            ReferenceCountUtil.safeRelease(written)
        } finally {
            drainOffload(channel, executor)
            channel.finishAndReleaseAll()
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `full response eligibility change fails the compressed write`() {
        val group = DefaultEventExecutorGroup(1)
        val executor = group.next()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        val exceptions = ExceptionCapture()
        val channel = EmbeddedChannel(CompressionOffloadHandler(executor), exceptions)
        try {
            writeRequest(channel, "gzip")
            val response = fullResponse(
                Unpooled.copiedBuffer("body".repeat(64), StandardCharsets.UTF_8),
                "text/plain",
            )
            val write = channel.writeOneOutbound(response)

            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.IDENTITY)
            releaseBlocker.countDown()
            drainOffload(channel, executor)

            assertFalse(write.isSuccess)
            assertEquals(1, exceptions.causes.size)
            assertIs<IllegalStateException>(exceptions.causes.single())
            assertEquals(0, response.refCnt())
        } finally {
            releaseBlocker.countDown()
            drainOffload(channel, executor)
            channel.finishAndReleaseAll()
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `compression state failures fail their writes and propagate exceptions`() {
        val group = DefaultEventExecutorGroup(1)
        val executor = group.next()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        val exceptions = ExceptionCapture()
        val channel = EmbeddedChannel(CompressionOffloadHandler(executor), exceptions)
        try {
            writeRequest(channel, "gzip")
            val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
            val responseWrite = channel.writeOneOutbound(response)
            val content = DefaultLastHttpContent(Unpooled.copiedBuffer("body", StandardCharsets.UTF_8))
            val contentWrite = channel.writeOneOutbound(content)

            // The outbound message belongs to the pipeline after writeOneOutbound. Mutating it here
            // deliberately exercises the guard against eligibility changing before offloaded work runs.
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.IDENTITY)
            releaseBlocker.countDown()
            drainOffload(channel, executor)

            assertFalse(responseWrite.isSuccess)
            assertFalse(contentWrite.isSuccess)
            assertEquals(2, exceptions.causes.size)
            exceptions.causes.forEach { assertIs<IllegalStateException>(it) }
            assertEquals(0, content.refCnt())
        } finally {
            releaseBlocker.countDown()
            drainOffload(channel, executor)
            channel.finishAndReleaseAll()
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    private fun withHandler(block: (EmbeddedChannel, EventExecutor) -> Unit) {
        val group = DefaultEventExecutorGroup(1)
        val executor = group.next()
        val channel = EmbeddedChannel(CompressionOffloadHandler(executor))
        try {
            block(channel, executor)
        } finally {
            drainOffload(channel, executor)
            channel.finishAndReleaseAll()
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    private fun writeRequest(
        channel: EmbeddedChannel,
        acceptEncoding: String?,
        method: HttpMethod = HttpMethod.GET,
    ) {
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/")
        if (acceptEncoding != null) {
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, acceptEncoding)
        }
        assertTrue(channel.writeInbound(request))
        ReferenceCountUtil.safeRelease(channel.readInbound<Any>())
    }

    private fun fullResponse(
        content: io.netty.buffer.ByteBuf,
        contentType: String,
        status: HttpResponseStatus = HttpResponseStatus.OK,
        version: HttpVersion = HttpVersion.HTTP_1_1,
    ): DefaultFullHttpResponse {
        val response = DefaultFullHttpResponse(version, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        return response
    }

    private fun drainOffload(channel: EmbeddedChannel, executor: EventExecutor) {
        executor.submit {}.syncUninterruptibly()
        channel.runPendingTasks()
        executor.submit {}.syncUninterruptibly()
        channel.runPendingTasks()
    }

    private fun writeAndDrainOffload(channel: EmbeddedChannel, executor: EventExecutor, message: Any) {
        val write = channel.writeOneOutbound(message)
        channel.flushOutbound()
        drainOffload(channel, executor)
        assertTrue(write.isSuccess, write.cause()?.message)
    }

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private fun io.netty.buffer.ByteBuf.copyBytes(): ByteArray =
        ByteArray(readableBytes()).also { getBytes(readerIndex(), it) }

    private fun HttpResponse.varyTokens(): Set<String> =
        headers().getAll(HttpHeaderNames.VARY)
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun emptyChannelInitializer(): ChannelInitializer<Channel> = object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) = Unit
    }

    private class ExceptionCapture : ChannelInboundHandlerAdapter() {
        val causes = mutableListOf<Throwable>()

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            causes += cause
        }
    }
}
