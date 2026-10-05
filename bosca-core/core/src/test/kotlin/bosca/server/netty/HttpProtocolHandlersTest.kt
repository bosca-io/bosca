package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CallMiddleware
import bosca.server.middleware.HandlerMiddleware
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerKeepAliveHandler
import io.netty.handler.codec.http.HttpServerUpgradeHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultEventExecutorGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpProtocolHandlersTest {

    @Test
    fun `drain handler passes requests through before draining`() {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ready")
        val channel = EmbeddedChannel(DrainingHttpHandler(isDraining = { false }, markConnectionClose = true))
        try {
            assertTrue(channel.writeInbound(request))
            assertSame(request, channel.readInbound<FullHttpRequest>())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP1 drain handler rejects a new request and closes the connection`() {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/late")
        val channel = EmbeddedChannel(DrainingHttpHandler(isDraining = { true }, markConnectionClose = true))
        try {
            assertFalse(channel.writeInbound(request))

            val response = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status())
            assertEquals(HttpHeaderValues.CLOSE.toString(), response.headers()[HttpHeaderNames.CONNECTION])
            assertEquals("503 Service Unavailable", response.content().toString(Charsets.UTF_8))
            response.release()
            assertEquals(0, request.refCnt())
            assertFalse(channel.isActive)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `drain handler preserves HEAD content length without a body`() {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/late")
        val channel = EmbeddedChannel(DrainingHttpHandler(isDraining = { true }, markConnectionClose = true))
        try {
            assertFalse(channel.writeInbound(request))

            val response = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status())
            assertEquals("23", response.headers()[HttpHeaderNames.CONTENT_LENGTH])
            assertFalse(response.content().isReadable)
            response.release()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `drain handler discards the remaining content of a rejected streaming request`() {
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/late")
        val content = DefaultHttpContent(Unpooled.copiedBuffer("part", Charsets.UTF_8))
        val last = DefaultLastHttpContent(Unpooled.copiedBuffer("end", Charsets.UTF_8))
        val handler = DrainingHttpHandler(isDraining = { true }, markConnectionClose = false)
        val channel = EmbeddedChannel(handler)
        try {
            val context = channel.pipeline().context(handler)
            handler.channelRead(context, request)
            // Exercise content that Netty decoded before the asynchronous close completed.
            handler.channelRead(context, content)
            handler.channelRead(context, last)

            val response = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status())
            assertNull(response.headers()[HttpHeaderNames.CONNECTION])
            response.release()
            assertEquals(0, content.refCnt())
            assertEquals(0, last.refCnt())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP1 drain handler marks an in-flight response to close`() {
        val channel = EmbeddedChannel(DrainingHttpHandler(isDraining = { true }, markConnectionClose = true))
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        try {
            assertTrue(channel.writeOutbound(response))

            val written = channel.readOutbound<DefaultHttpResponse>()
            assertSame(response, written)
            assertEquals(HttpHeaderValues.CLOSE.toString(), written.headers()[HttpHeaderNames.CONNECTION])
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP1 drain closes after an in-flight keep-alive response completes`() {
        var draining = false
        val channel = EmbeddedChannel(
            DrainingHttpHandler(isDraining = { draining }, markConnectionClose = true),
            HttpServerKeepAliveHandler(),
        )
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/in-flight")
        val responseBody = Unpooled.copiedBuffer("complete", Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, responseBody)
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.readableBytes())
        try {
            assertTrue(channel.writeInbound(request))
            ReferenceCountUtil.safeRelease(channel.readInbound<FullHttpRequest>())

            draining = true
            assertTrue(channel.writeOutbound(response))

            val written = channel.readOutbound<DefaultFullHttpResponse>()
            assertEquals(HttpHeaderValues.CLOSE.toString(), written.headers()[HttpHeaderNames.CONNECTION])
            written.release()
            assertFalse(channel.isActive, "Connection: close must close after the completed response")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `bodyless h2c request still enters upgrade negotiation`() {
        val codec = HttpServerCodec()
        var requestedProtocol: String? = null
        val upgradeHandler = BodylessH2cUpgradeHandler(codec) { protocol ->
            requestedProtocol = protocol.toString()
            null
        }
        val channel = EmbeddedChannel(upgradeHandler)
        try {
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
            request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade, HTTP2-Settings")
            request.headers().set(HttpHeaderNames.UPGRADE, "h2c")
            request.headers().set("HTTP2-Settings", "AAMAAABk")

            assertFalse(channel.writeInbound(request))
            assertTrue(channel.writeInbound(DefaultLastHttpContent(Unpooled.EMPTY_BUFFER)))
            assertEquals("h2c", requestedProtocol)
            val forwarded = channel.readInbound<FullHttpRequest>()
            assertEquals(HttpMethod.GET, forwarded.method())
            assertEquals("/", forwarded.uri())
            forwarded.release()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `full bodyless h2c request is eligible for upgrade negotiation`() {
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/",
            Unpooled.EMPTY_BUFFER,
        )
        try {
            assertTrue(isBodylessH2cUpgradeRequest(request))
        } finally {
            request.release()
        }
    }

    @Test
    fun `full h2c request with inline payload is ineligible for upgrade negotiation`() {
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            "/graphql",
            Unpooled.copiedBuffer("{}", Charsets.UTF_8),
        )
        try {
            request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes())
            assertFalse(isBodylessH2cUpgradeRequest(request))
        } finally {
            request.release()
        }
    }

    @Test
    fun `body-bearing h2c upgrade falls back to streaming HTTP1`() {
        val codec = HttpServerCodec()
        val upgradeHandler = BodylessH2cUpgradeHandler(codec) { null }
        val channel = EmbeddedChannel(upgradeHandler)
        try {
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/graphql")
            request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade, HTTP2-Settings")
            request.headers().set(HttpHeaderNames.UPGRADE, "h2c")
            request.headers().set("HTTP2-Settings", "AAMAAABk")
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 2)
            val content = DefaultLastHttpContent(Unpooled.copiedBuffer("{}", Charsets.UTF_8))

            assertTrue(channel.writeInbound(request))
            assertTrue(channel.writeInbound(content))
            assertSame(request, channel.readInbound<HttpRequest>())
            val receivedContent = channel.readInbound<DefaultLastHttpContent>()
            assertSame(content, receivedContent)
            receivedContent.release()
            assertNull(channel.readOutbound<Any>(), "fallback must not emit the aggregator's 413 response")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `transfer-encoded h2c request falls back before aggregation`() {
        val codec = HttpServerCodec()
        var negotiationAttempted = false
        val upgradeHandler = BodylessH2cUpgradeHandler(codec) {
            negotiationAttempted = true
            null
        }
        val channel = EmbeddedChannel(upgradeHandler)
        try {
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/stream")
            request.headers().set(HttpHeaderNames.UPGRADE, "h2c")
            request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked")

            assertTrue(channel.writeInbound(request))
            assertFalse(negotiationAttempted)
            assertSame(request, channel.readInbound<HttpRequest>())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP1 selection succeeds when upgrade handler is already absent`() {
        val codec = HttpServerCodec()
        val upgradeHandler = HttpServerUpgradeHandler(codec) { null }
        val received = mutableListOf<String>()
        val channel = EmbeddedChannel(
            Http1ProtocolSelectionHandler(upgradeHandler),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is DefaultHttpRequest) received += msg.uri()
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            channel.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/selected"))

            assertEquals(listOf("/selected"), received)
            assertNull(channel.pipeline().get(Http1ProtocolSelectionHandler::class.java))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 stream initializer respects compression setting`() {
        listOf(false, true).forEach { compressionEnabled ->
            val codecGroup = DefaultEventExecutorGroup(1)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
            val httpHandler = NettyHttpHandler(application, scope, 1024)
            val channel = EmbeddedChannel(
                Http2StreamInitializer(compressionEnabled, codecGroup, httpHandler, 30, isDraining = { false }),
            )
            try {
                assertNotNull(channel.pipeline().get(PipelineHandlerNames.HTTP2_PROTOCOL_SELECTOR))
                assertNull(channel.pipeline().get(NettyServerEngine.COMPRESSOR_HANDLER_NAME))
                channel.writeInbound(
                    DefaultHttp2HeadersFrame(
                        DefaultHttp2Headers()
                            .method(HttpMethod.GET.asciiName())
                            .scheme("http")
                            .authority("localhost")
                            .path("/ordinary"),
                        true,
                    ),
                )

                if (compressionEnabled) {
                    assertNotNull(channel.pipeline().get(NettyServerEngine.COMPRESSOR_HANDLER_NAME))
                } else {
                    assertNull(channel.pipeline().get(NettyServerEngine.COMPRESSOR_HANDLER_NAME))
                }
                assertNotNull(channel.pipeline().get(NettyServerEngine.CHUNKED_WRITER_HANDLER_NAME))
                assertNotNull(channel.pipeline().get(NettyServerEngine.HTTP_HANDLER_NAME))
            } finally {
                channel.finishAndReleaseAll()
                scope.cancel()
                codecGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
            }
        }
    }

    @Test
    fun `HTTP2 selector preserves extended CONNECT pseudo headers for WebSocket handshake`() {
        val codecGroup = DefaultEventExecutorGroup(1)
        var request: HttpRequest? = null
        val channel = EmbeddedChannel(
            Http2StreamProtocolSelector(false, codecGroup, 30),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is HttpRequest) request = msg
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            val headers = extendedConnectHeaders(scheme = "https").also { frame ->
                frame.headers().set(HttpHeaderNames.HOST, "localhost")
                frame.headers().set(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS)
                frame.headers().add(HttpHeaderNames.COOKIE, "first=one")
                frame.headers().add(HttpHeaderNames.COOKIE, "second=two")
            }
            channel.writeInbound(headers)

            assertNull(channel.pipeline().get(Http2StreamProtocolSelector::class.java))
            assertNotNull(channel.pipeline().get(Http2WebSocketStreamHandler::class.java))
            assertEquals(HttpMethod.CONNECT, request?.method())
            assertEquals("/graphqlws", request?.uri())
            assertEquals("localhost", request?.headers()?.get(HttpHeaderNames.HOST))
            assertEquals(HttpHeaderValues.TRAILERS.toString(), request?.headers()?.get(HttpHeaderNames.TE))
            assertEquals(listOf("first=one; second=two"), request?.headers()?.getAll(HttpHeaderNames.COOKIE))
            assertEquals("graphql-transport-ws", request?.headers()?.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL))
        } finally {
            channel.finishAndReleaseAll()
            codecGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport rejects malformed HTTP2 request headers`() {
        val malformedRequests = listOf(
            extendedConnectHeaders().also {
                it.headers().set(HttpHeaderNames.HOST, "conflicts.example")
            },
            extendedConnectHeaders().also {
                it.headers().set(HttpHeaderNames.CONNECTION, "keep-alive")
            },
            extendedConnectHeaders().also {
                it.headers().set("keep-alive", "timeout=5")
            },
            extendedConnectHeaders().also {
                it.headers().set("proxy-connection", "keep-alive")
            },
            extendedConnectHeaders().also {
                it.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            },
            extendedConnectHeaders().also {
                it.headers().set(HttpHeaderNames.UPGRADE, "websocket")
            },
            extendedConnectHeaders().also {
                it.headers().set(HttpHeaderNames.TE, "trailers, gzip")
            },
        )

        malformedRequests.forEach { request ->
            val channel = EmbeddedChannel(Http2WebSocketStreamHandler(0))
            try {
                channel.writeInbound(request)
                channel.runPendingTasks()

                val response = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
                try {
                    assertEquals(HttpResponseStatus.BAD_REQUEST.codeAsText(), response.headers().status())
                    assertTrue(response.isEndStream)
                } finally {
                    ReferenceCountUtil.release(response)
                }
                assertNull(channel.readInbound<Any>())
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `HTTP2 WebSocket transport accepts with 200 and decodes masked DATA frames`() {
        val transport = Http2WebSocketStreamHandler(0)
        val requests = mutableListOf<String>()
        val received = mutableListOf<String>()
        val channel = EmbeddedChannel(
            transport,
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequest -> requests += msg.uri()
                        is TextWebSocketFrame -> received += msg.text()
                    }
                    ReferenceCountUtil.release(msg)
                }
            },
        )

        try {
            channel.writeInbound(extendedConnectHeaders())
            transport.accept(
                DefaultHttpHeaders().set("x-handshake", "accepted"),
                "graphql-transport-ws",
            ).syncUninterruptibly()
            channel.runPendingTasks()

            val response = channel.readOutbound<Http2HeadersFrame>()
            try {
                assertEquals(HttpResponseStatus.OK.codeAsText(), response.headers().status())
                assertEquals("accepted", response.headers().get("x-handshake")?.toString())
                assertEquals(
                    "graphql-transport-ws",
                    response.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)?.toString(),
                )
                assertTrue(!response.isEndStream)
            } finally {
                ReferenceCountUtil.release(response)
            }

            val encoded = encodeClientTextFrame("hello over h2")
            channel.writeInbound(DefaultHttp2DataFrame(encoded, false))

            assertEquals(listOf("/graphqlws"), requests)
            assertEquals(listOf("hello over h2"), received)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket handshake applies headers and propagates handler context into session operations`() {
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        application.install(object : CallMiddleware {
            override fun onBeforeWrite(call: ServerCall) {
                call.response.header("x-before-write", "applied")
            }
        })
        application.installHandler(object : HandlerMiddleware {
            override suspend fun onHandler(call: ServerCall, next: suspend () -> Unit) {
                withContext(CoroutineName("handler-context")) { next() }
            }
        })
        val handlerInvoked = CountDownLatch(1)
        var operationContext: String? = null
        application.routing {
            webSocket("/graphqlws", protocol = "graphql-transport-ws") {
                operationContext = async { currentCoroutineContext()[CoroutineName]?.name }.await()
                handlerInvoked.countDown()
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val transport = Http2WebSocketStreamHandler(0)
        val channel = EmbeddedChannel(transport, NettyHttpHandler(application, scope, 1024))
        try {
            channel.writeInbound(extendedConnectHeaders())
            assertTrue(handlerInvoked.await(5, TimeUnit.SECONDS), "WebSocket handler should run after acceptance")
            assertEquals("handler-context", operationContext)
            channel.runPendingTasks()

            val response = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
            try {
                assertEquals(HttpResponseStatus.OK.codeAsText(), response.headers().status())
                assertEquals("applied", response.headers().get("x-before-write")?.toString())
            } finally {
                ReferenceCountUtil.release(response)
            }
        } finally {
            channel.finishAndReleaseAll()
            scope.cancel()
        }
    }

    @Test
    fun `HTTP2 WebSocket handshake rejects when before-write middleware fails`() {
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        val afterCalled = CountDownLatch(1)
        application.install(object : CallMiddleware {
            override fun onBeforeWrite(call: ServerCall) {
                error("before-write failed")
            }

            override suspend fun afterCall(call: ServerCall) {
                afterCalled.countDown()
            }
        })
        application.routing {
            webSocket("/graphqlws", protocol = "graphql-transport-ws") {
                error("WebSocket handler must not run")
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val transport = Http2WebSocketStreamHandler(0)
        val channel = EmbeddedChannel(transport, NettyHttpHandler(application, scope, 1024))
        try {
            channel.writeInbound(extendedConnectHeaders())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (afterCalled.count > 0 && System.nanoTime() < deadline) {
                channel.runPendingTasks()
                Thread.yield()
            }
            assertTrue(afterCalled.await(0, TimeUnit.SECONDS), "Failed handshake should complete cleanup")
            channel.runPendingTasks()

            val response = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
            try {
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText(), response.headers().status())
                assertTrue(response.isEndStream)
            } finally {
                ReferenceCountUtil.release(response)
            }
        } finally {
            channel.finishAndReleaseAll()
            scope.cancel()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport rejects unsupported versions without upgrading`() {
        val transport = Http2WebSocketStreamHandler(0)
        val channel = EmbeddedChannel(transport)
        val headers = DefaultHttp2Headers()
            .method(HttpMethod.CONNECT.asciiName())
            .scheme("http")
            .authority("localhost")
            .path("/graphqlws")
            .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
            .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "12")

        try {
            channel.writeInbound(DefaultHttp2HeadersFrame(headers, false))
            channel.runPendingTasks()

            val response = channel.readOutbound<Http2HeadersFrame>()
            try {
                assertEquals(HttpResponseStatus.UPGRADE_REQUIRED.codeAsText(), response.headers().status())
                assertEquals("13", response.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION)?.toString())
                assertTrue(response.isEndStream)
            } finally {
                ReferenceCountUtil.release(response)
            }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport rejects every malformed extended CONNECT shape`() {
        val cases = listOf(
            DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, false) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(endStream = true) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(method = null) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(method = "GET") to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(protocol = null) to HttpResponseStatus.NOT_IMPLEMENTED,
            extendedConnectHeaders(protocol = "not-websocket") to HttpResponseStatus.NOT_IMPLEMENTED,
            extendedConnectHeaders(scheme = null) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(scheme = "ftp") to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(path = null) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(path = "", validateHeaders = false) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(path = "relative") to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(authority = null) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(authority = "", validateHeaders = false) to HttpResponseStatus.BAD_REQUEST,
            extendedConnectHeaders(version = null) to HttpResponseStatus.UPGRADE_REQUIRED,
        )

        cases.forEach { (input, expectedStatus) ->
            val channel = EmbeddedChannel(Http2WebSocketStreamHandler(0))
            try {
                channel.writeInbound(input)
                channel.runPendingTasks()
                val response = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
                try {
                    assertEquals(expectedStatus.codeAsText(), response.headers().status())
                    assertTrue(response.isEndStream)
                    if (expectedStatus == HttpResponseStatus.UPGRADE_REQUIRED) {
                        assertEquals("13", response.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION)?.toString())
                    }
                } finally {
                    ReferenceCountUtil.release(response)
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `HTTP2 WebSocket transport buffers handshake traffic and handles open stream edges`() {
        val transport = Http2WebSocketStreamHandler(0)
        val requests = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val markers = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val channel = EmbeddedChannel(
            transport,
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequest -> requests += msg.uri()
                        is TextWebSocketFrame -> texts += msg.text()
                        is String -> markers += msg
                    }
                    ReferenceCountUtil.release(msg)
                }

                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    failures += cause
                }
            },
        )

        try {
            channel.writeInbound(extendedConnectHeaders())
            assertFalse(channel.config().isAutoRead, "handshake must stop replenishing the stream receive window")
            channel.writeInbound("handshake-marker")
            channel.writeInbound(DefaultHttp2DataFrame(encodeClientTextFrame("buffered"), false))
            transport.accept(DefaultHttpHeaders(), null).syncUninterruptibly()
            channel.runPendingTasks()

            assertTrue(channel.config().isAutoRead, "accepted stream must resume reads after buffered frames replay")
            assertEquals(listOf("/graphqlws"), requests)
            assertEquals(listOf("handshake-marker"), markers)
            assertEquals(listOf("buffered"), texts)
            ReferenceCountUtil.release(channel.readOutbound<Http2HeadersFrame>())

            channel.writeInbound(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, false))

            channel.writeInbound(DefaultHttp2HeadersFrame(DefaultHttp2Headers(), false))
            assertTrue(failures.single() is IllegalStateException)
            channel.writeInbound("open-marker")
            assertEquals(listOf("handshake-marker", "open-marker"), markers)

            channel.writeOutbound(Unpooled.copiedBuffer("raw", Charsets.UTF_8))
            val data = assertNotNull(channel.readOutbound<DefaultHttp2DataFrame>())
            try {
                assertEquals("raw", data.content().toString(Charsets.UTF_8))
                assertFalse(data.isEndStream)
            } finally {
                data.release()
            }

            transport.endStream()
            channel.runPendingTasks()
            val end = assertNotNull(channel.readOutbound<DefaultHttp2DataFrame>())
            try {
                assertTrue(end.isEndStream)
            } finally {
                end.release()
            }
            transport.endStream()

            val lateWrite = channel.writeAndFlush(Unpooled.copiedBuffer("late", Charsets.UTF_8))
            channel.runPendingTasks()
            assertFalse(lateWrite.isSuccess)

            channel.writeInbound(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
            assertFalse(channel.isActive)
        } finally {
            channel.finishAndReleaseAll()
        }

        val duplicateHeadersTransport = Http2WebSocketStreamHandler(0)
        val duplicateHeaders = EmbeddedChannel(duplicateHeadersTransport)
        try {
            duplicateHeaders.writeInbound(extendedConnectHeaders())
            ReferenceCountUtil.release(duplicateHeaders.readInbound<Any>())
            duplicateHeaders.writeInbound(extendedConnectHeaders())
            duplicateHeaders.runPendingTasks()
            val rejection = assertNotNull(duplicateHeaders.readOutbound<Http2HeadersFrame>())
            try {
                assertEquals(HttpResponseStatus.BAD_REQUEST.codeAsText(), rejection.headers().status())
            } finally {
                ReferenceCountUtil.release(rejection)
            }
        } finally {
            duplicateHeaders.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport converts full and streaming handshake rejections`() {
        listOf("", "rejected").forEach { body ->
            val transport = Http2WebSocketStreamHandler(0)
            val channel = beginHandshake(transport)
            try {
                channel.writeOutbound(
                    DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.FORBIDDEN,
                        Unpooled.copiedBuffer(body, Charsets.UTF_8),
                    ),
                )
                val headers = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
                try {
                    assertEquals(HttpResponseStatus.FORBIDDEN.codeAsText(), headers.headers().status())
                    assertEquals(body.isEmpty(), headers.isEndStream)
                } finally {
                    ReferenceCountUtil.release(headers)
                }
                if (body.isNotEmpty()) {
                    val data = assertNotNull(channel.readOutbound<DefaultHttp2DataFrame>())
                    try {
                        assertEquals(body, data.content().toString(Charsets.UTF_8))
                        assertTrue(data.isEndStream)
                    } finally {
                        data.release()
                    }
                }
            } finally {
                channel.finishAndReleaseAll()
            }
        }

        val transport = Http2WebSocketStreamHandler(0)
        val channel = beginHandshake(transport)
        try {
            channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED))
            channel.writeOutbound(DefaultHttpContent(Unpooled.copiedBuffer("part-1", Charsets.UTF_8)))
            channel.writeOutbound(DefaultLastHttpContent(Unpooled.copiedBuffer("part-2", Charsets.UTF_8)))

            val headers = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
            try {
                assertEquals(HttpResponseStatus.UNAUTHORIZED.codeAsText(), headers.headers().status())
                assertFalse(headers.isEndStream)
            } finally {
                ReferenceCountUtil.release(headers)
            }
            val first = assertNotNull(channel.readOutbound<DefaultHttp2DataFrame>())
            val last = assertNotNull(channel.readOutbound<DefaultHttp2DataFrame>())
            try {
                assertEquals("part-1", first.content().toString(Charsets.UTF_8))
                assertFalse(first.isEndStream)
                assertEquals("part-2", last.content().toString(Charsets.UTF_8))
                assertTrue(last.isEndStream)
            } finally {
                first.release()
                last.release()
            }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket rejection resumes reads and closes when the peer ends its stream`() {
        val transport = Http2WebSocketStreamHandler(0)
        val channel = beginHandshake(transport)
        try {
            assertFalse(channel.config().isAutoRead, "The application handshake should initially pause reads")

            channel.writeOutbound(
                DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED),
            )
            ReferenceCountUtil.release(assertNotNull(channel.readOutbound<Http2HeadersFrame>()))
            channel.runPendingTasks()

            assertTrue(
                channel.config().isAutoRead,
                "A rejected stream must read the peer's END_STREAM instead of waiting for forced cleanup",
            )
            channel.writeInbound(DefaultHttp2HeadersFrame(DefaultHttp2Headers(), true))
            channel.runPendingTasks()
            assertFalse(channel.isActive, "The rejected child stream should close as soon as both sides have ended")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket rejection closes after a peer end stream buffered during handshake`() {
        val transport = Http2WebSocketStreamHandler(0)
        val channel = beginHandshake(transport)
        try {
            channel.writeInbound(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
            channel.writeOutbound(
                DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED),
            )
            ReferenceCountUtil.release(assertNotNull(channel.readOutbound<Http2HeadersFrame>()))
            channel.runPendingTasks()

            assertFalse(
                channel.isActive,
                "A buffered peer END_STREAM should close the rejected child as soon as the response is written",
            )
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport rejects duplicate state transitions`() {
        val rejected = Http2WebSocketStreamHandler(0)
        val rejectedChannel = EmbeddedChannel(rejected)
        try {
            rejected.reject(HttpResponseStatus.NOT_FOUND).syncUninterruptibly()
            assertFalse(rejected.reject(HttpResponseStatus.BAD_REQUEST).isSuccess)
            assertFalse(rejected.accept(DefaultHttpHeaders(), null).isSuccess)
        } finally {
            rejectedChannel.finishAndReleaseAll()
        }

        val open = Http2WebSocketStreamHandler(0)
        val openChannel = beginHandshake(open)
        try {
            open.accept(
                DefaultHttpHeaders(false)
                    .add(HttpHeaderNames.CONNECTION, "close")
                    .add(":hidden", "no")
                    .add("x-ok", "yes"),
                null,
            )
                .syncUninterruptibly()
            openChannel.runPendingTasks()
            val response = assertNotNull(openChannel.readOutbound<Http2HeadersFrame>())
            try {
                assertNull(response.headers().get(HttpHeaderNames.CONNECTION))
                assertNull(response.headers().get(":hidden"))
                assertEquals("yes", response.headers().get("x-ok")?.toString())
            } finally {
                ReferenceCountUtil.release(response)
            }
            assertFalse(open.reject(HttpResponseStatus.BAD_REQUEST).isSuccess)
            assertFalse(open.accept(DefaultHttpHeaders(), null).isSuccess)
        } finally {
            openChannel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport applies complete HTTP2 response header filtering`() {
        val transport = Http2WebSocketStreamHandler(0)
        val channel = beginHandshake(transport)
        try {
            transport.accept(
                DefaultHttpHeaders()
                    .add(HttpHeaderNames.CONNECTION, "x-connection-only")
                    .add("x-connection-only", "must-not-escape")
                    .add(HttpHeaderNames.TE, "gzip")
                    .add("x-ok", "yes"),
                null,
            ).syncUninterruptibly()
            channel.runPendingTasks()

            val response = assertNotNull(channel.readOutbound<Http2HeadersFrame>())
            try {
                assertNull(response.headers().get(HttpHeaderNames.CONNECTION))
                assertNull(response.headers().get("x-connection-only"))
                assertNull(response.headers().get(HttpHeaderNames.TE))
                assertEquals("yes", response.headers().get("x-ok")?.toString())
            } finally {
                ReferenceCountUtil.release(response)
            }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket transport rejects duplicate transition during streaming rejection`() {
        val transport = Http2WebSocketStreamHandler(0)
        val channel = beginHandshake(transport)
        try {
            channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED))
            ReferenceCountUtil.release(assertNotNull(channel.readOutbound<Http2HeadersFrame>()))

            val duplicate = transport.reject(HttpResponseStatus.INTERNAL_SERVER_ERROR)
            channel.runPendingTasks()

            assertTrue(duplicate.isDone)
            assertFalse(duplicate.isSuccess)
            assertNull(channel.readOutbound<Http2HeadersFrame>())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket failed handshake terminates every transport state`() {
        val awaiting = Http2WebSocketStreamHandler(0)
        val awaitingChannel = EmbeddedChannel(awaiting)
        try {
            awaiting.failHandshake(HttpResponseStatus.INTERNAL_SERVER_ERROR).syncUninterruptibly()
            val rejection = assertNotNull(awaitingChannel.readOutbound<Http2HeadersFrame>())
            try {
                assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText(), rejection.headers().status())
                assertTrue(rejection.isEndStream)
            } finally {
                ReferenceCountUtil.release(rejection)
            }
            awaiting.failHandshake(HttpResponseStatus.INTERNAL_SERVER_ERROR).syncUninterruptibly()
            assertNull(awaitingChannel.readOutbound<Any>())
        } finally {
            awaitingChannel.finishAndReleaseAll()
        }

        val rejecting = Http2WebSocketStreamHandler(0)
        val rejectingChannel = beginHandshake(rejecting)
        try {
            rejectingChannel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
            ReferenceCountUtil.release(rejectingChannel.readOutbound<Http2HeadersFrame>())

            rejecting.failHandshake(HttpResponseStatus.INTERNAL_SERVER_ERROR).syncUninterruptibly()
            assertFalse(rejectingChannel.isActive)
        } finally {
            rejectingChannel.finishAndReleaseAll()
        }

        val open = Http2WebSocketStreamHandler(0)
        val openChannel = beginHandshake(open)
        open.accept(DefaultHttpHeaders(), null).syncUninterruptibly()
        ReferenceCountUtil.release(openChannel.readOutbound<Http2HeadersFrame>())
        openChannel.close().syncUninterruptibly()
        open.failHandshake(HttpResponseStatus.INTERNAL_SERVER_ERROR).syncUninterruptibly()
        assertFalse(openChannel.isOpen)
        openChannel.finishAndReleaseAll()
    }

    @Test
    fun `HTTP2 selector and WebSocket transport cover passthrough and terminal state edges`() {
        val codecGroup = DefaultEventExecutorGroup(1)
        val selected = mutableListOf<String>()
        val selectorChannel = EmbeddedChannel(
            Http2StreamProtocolSelector(false, codecGroup, 0),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg is String) selected += msg
                    ReferenceCountUtil.release(msg)
                }
            },
        )
        try {
            selectorChannel.writeInbound("not-headers")
            assertEquals(listOf("not-headers"), selected)
        } finally {
            selectorChannel.finishAndReleaseAll()
            codecGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }

        val rejected = Http2WebSocketStreamHandler(0)
        val rejectedChannel = EmbeddedChannel(rejected)
        try {
            rejected.endStream()
            rejectedChannel.writeOutbound(Unpooled.copiedBuffer("passthrough", Charsets.UTF_8))
            ReferenceCountUtil.release(rejectedChannel.readOutbound<ByteBuf>())
            rejectedChannel.writeOutbound(
                DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER),
            )
            ReferenceCountUtil.release(rejectedChannel.readOutbound<DefaultFullHttpResponse>())
            rejectedChannel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
            ReferenceCountUtil.release(rejectedChannel.readOutbound<DefaultHttpResponse>())
            rejected.reject(HttpResponseStatus.BAD_REQUEST).syncUninterruptibly()
            ReferenceCountUtil.release(rejectedChannel.readOutbound<Http2HeadersFrame>())
            rejectedChannel.writeInbound(DefaultHttp2DataFrame(Unpooled.copiedBuffer("discarded", Charsets.UTF_8)))
            rejectedChannel.advanceTimeBy(5, TimeUnit.SECONDS)
            rejectedChannel.runScheduledPendingTasks()
            assertFalse(rejectedChannel.isActive)
        } finally {
            rejectedChannel.finishAndReleaseAll()
        }

        val closedBeforeGracePeriod = Http2WebSocketStreamHandler(0)
        val closedBeforeGracePeriodChannel = EmbeddedChannel(closedBeforeGracePeriod)
        try {
            closedBeforeGracePeriod.reject(HttpResponseStatus.BAD_REQUEST).syncUninterruptibly()
            ReferenceCountUtil.release(closedBeforeGracePeriodChannel.readOutbound<Http2HeadersFrame>())
            closedBeforeGracePeriodChannel.close().syncUninterruptibly()
            closedBeforeGracePeriodChannel.advanceTimeBy(5, TimeUnit.SECONDS)
            closedBeforeGracePeriodChannel.runScheduledPendingTasks()
            assertFalse(closedBeforeGracePeriodChannel.isActive)
        } finally {
            closedBeforeGracePeriodChannel.finishAndReleaseAll()
        }

        val rejecting = Http2WebSocketStreamHandler(0)
        val rejectingChannel = beginHandshake(rejecting)
        try {
            rejectingChannel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
            ReferenceCountUtil.release(rejectingChannel.readOutbound<Http2HeadersFrame>())
            rejectingChannel.writeInbound(DefaultHttp2DataFrame(Unpooled.copiedBuffer("discarded", Charsets.UTF_8)))
            rejectingChannel.writeOutbound(DefaultLastHttpContent(Unpooled.EMPTY_BUFFER))
            ReferenceCountUtil.release(rejectingChannel.readOutbound<DefaultHttp2DataFrame>())
        } finally {
            rejectingChannel.finishAndReleaseAll()
        }

        val remoteClosed = Http2WebSocketStreamHandler(0)
        val remoteClosedChannel = beginHandshake(remoteClosed)
        try {
            remoteClosed.accept(DefaultHttpHeaders(), null).syncUninterruptibly()
            ReferenceCountUtil.release(remoteClosedChannel.readOutbound<Http2HeadersFrame>())
            remoteClosedChannel.writeInbound(DefaultHttp2HeadersFrame(DefaultHttp2Headers(), true))
            ReferenceCountUtil.release(remoteClosedChannel.readOutbound<DefaultHttp2DataFrame>())
            assertFalse(remoteClosedChannel.isActive)
        } finally {
            remoteClosedChannel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket outbound state predicates pass through unrelated messages`() {
        val handshaking = Http2WebSocketStreamHandler(0)
        val handshakingChannel = beginHandshake(handshaking)
        try {
            val marker = Unpooled.copiedBuffer("handshaking", Charsets.UTF_8)
            assertTrue(handshakingChannel.writeOutbound(marker))
            assertSame(marker, handshakingChannel.readOutbound<ByteBuf>())
            marker.release()
        } finally {
            handshakingChannel.finishAndReleaseAll()
        }

        val rejecting = Http2WebSocketStreamHandler(0)
        val rejectingChannel = beginHandshake(rejecting)
        try {
            rejectingChannel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
            ReferenceCountUtil.release(rejectingChannel.readOutbound<Http2HeadersFrame>())

            val marker = Unpooled.copiedBuffer("rejecting", Charsets.UTF_8)
            assertTrue(rejectingChannel.writeOutbound(marker))
            assertSame(marker, rejectingChannel.readOutbound<ByteBuf>())
            marker.release()
        } finally {
            rejectingChannel.finishAndReleaseAll()
        }

        val open = Http2WebSocketStreamHandler(0)
        val openChannel = beginHandshake(open)
        try {
            open.accept(DefaultHttpHeaders(), null).syncUninterruptibly()
            ReferenceCountUtil.release(openChannel.readOutbound<Http2HeadersFrame>())

            val marker = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            assertTrue(openChannel.writeOutbound(marker))
            assertSame(marker, openChannel.readOutbound<DefaultHttpResponse>())
        } finally {
            openChannel.finishAndReleaseAll()
        }
    }

    @Test
    fun `HTTP2 WebSocket write failures and void close promises clean up the child`() {
        val failedAccept = Http2WebSocketStreamHandler(0)
        val failedAcceptChannel = beginHandshake(failedAccept)
        try {
            val transportName = failedAcceptChannel.pipeline().context(failedAccept).name()
            failedAcceptChannel.pipeline().addBefore(transportName, "failWrites", failingWrites())
            val accepted = failedAccept.accept(DefaultHttpHeaders(), null)
            failedAcceptChannel.runPendingTasks()
            assertFalse(accepted.isSuccess)
            assertFalse(failedAcceptChannel.isActive)
        } finally {
            failedAcceptChannel.finishAndReleaseAll()
        }

        val voidClose = Http2WebSocketStreamHandler(0)
        val voidCloseChannel = beginHandshake(voidClose)
        try {
            voidClose.accept(DefaultHttpHeaders(), null).syncUninterruptibly()
            ReferenceCountUtil.release(voidCloseChannel.readOutbound<Http2HeadersFrame>())
            voidCloseChannel.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ReferenceCountUtil.release(msg)
                    ctx.writeAndFlush(
                        io.netty.handler.codec.http.websocketx.CloseWebSocketFrame(1000, "void"),
                        ctx.voidPromise(),
                    )
                }
            })
            voidCloseChannel.writeInbound("close")
            voidCloseChannel.runPendingTasks()
            val written = generateSequence { voidCloseChannel.readOutbound<Any>() }.toList()
            try {
                assertTrue(
                    written.any { it is io.netty.handler.codec.http2.Http2DataFrame && it.isEndStream },
                    "A close frame written with a void promise must still end the stream",
                )
            } finally {
                written.forEach(ReferenceCountUtil::release)
            }
            voidCloseChannel.finishAndReleaseAll()
        } finally {
            if (voidCloseChannel.isOpen) voidCloseChannel.finishAndReleaseAll()
        }

        val failedClose = Http2WebSocketStreamHandler(0)
        val failedCloseChannel = beginHandshake(failedClose)
        try {
            failedClose.accept(DefaultHttpHeaders(), null).syncUninterruptibly()
            ReferenceCountUtil.release(failedCloseChannel.readOutbound<Http2HeadersFrame>())
            val transportName = failedCloseChannel.pipeline().context(failedClose).name()
            failedCloseChannel.pipeline().addBefore(transportName, "failWrites", failingWrites())
            failedCloseChannel.writeAndFlush(io.netty.handler.codec.http.websocketx.CloseWebSocketFrame(1000, "fail"))
            failedCloseChannel.runPendingTasks()
            assertFalse(failedCloseChannel.isActive)
        } finally {
            failedCloseChannel.finishAndReleaseAll()
        }
    }

    private fun extendedConnectHeaders(
        method: String? = HttpMethod.CONNECT.name(),
        protocol: String? = "websocket",
        scheme: String? = "http",
        authority: String? = "localhost",
        path: String? = "/graphqlws",
        version: String? = "13",
        endStream: Boolean = false,
        validateHeaders: Boolean = true,
    ): Http2HeadersFrame {
        val headers = DefaultHttp2Headers(validateHeaders)
        method?.let(headers::method)
        scheme?.let(headers::scheme)
        authority?.let(headers::authority)
        path?.let(headers::path)
        protocol?.let { headers.set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), it) }
        version?.let { headers.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, it) }
        headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-transport-ws")
        return DefaultHttp2HeadersFrame(headers, endStream)
    }

    private fun beginHandshake(transport: Http2WebSocketStreamHandler): EmbeddedChannel =
        EmbeddedChannel(transport).also { channel ->
            channel.writeInbound(extendedConnectHeaders())
            ReferenceCountUtil.release(channel.readInbound<Any>())
        }

    private fun failingWrites(): ChannelOutboundHandlerAdapter = object : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            ReferenceCountUtil.release(msg)
            promise.tryFailure(java.io.IOException("expected write failure"))
        }
    }

    private fun encodeClientTextFrame(text: String): ByteBuf = EmbeddedChannel(WebSocket13FrameEncoder(true)).let { encoder ->
        try {
            assertTrue(encoder.writeOutbound(TextWebSocketFrame(text)))
            encoder.readOutbound<ByteBuf>()
        } finally {
            encoder.finishAndReleaseAll()
        }
    }
}
