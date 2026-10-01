package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.websocket.WebSocketFrame
import com.sun.management.UnixOperatingSystemMXBean
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpClientUpgradeHandler
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec
import io.netty.handler.codec.http2.Http2ConnectionHandler
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.util.ReferenceCountUtil
import io.netty.util.ResourceLeakDetector
import io.netty.util.ResourceLeakDetectorFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-socket transport soak coverage. This class runs in its own Gradle test JVM through
 * `nettyTransportSoakTest`, with Netty's leak detector set to PARANOID before the first allocation.
 */
class NettyServerTransportSoakTest {

    @AfterTest
    fun assertNoNettyLeaks() {
        forceGcAndLeakReporting()
        assertTrue(leakFactory.detectorsCreated.get() > 0, "Netty leak detectors were not installed")
        assertTrue(leakFactory.leaks.isEmpty(), leakFactory.leaks.joinToString("\n"))
    }

    @Test
    fun `real HTTP1 HTTP2 and WebSocket clients remain isolated through churn and failure paths`() {
        val fileDescriptorsBefore = openFileDescriptorCount()
        val transportThreadsBefore = transportThreadIds()
        val abruptWebSocketClosed = CountDownLatch(1)
        val resetHandlerStarted = CountDownLatch(1)
        val resetHandlerCancelled = CountDownLatch(1)
        val activeRouteHandlers = AtomicInteger()
        val application = createApplication()
        application.install(object : CallMiddleware {
            override suspend fun beforeCall(call: bosca.server.ServerCall) {
                when (call.request.uri) {
                    "/middleware-commit" -> call.respond(HttpStatusCode.Forbidden, "forbidden")
                    "/middleware-fails" -> error("expected beforeCall failure")
                }
            }

            override suspend fun onException(call: bosca.server.ServerCall, cause: Throwable) {
                if (call.request.uri == "/middleware-fails") error("expected onException failure")
            }
        })
        application.install(object : CallMiddleware {})
        application.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(
                call: bosca.server.ServerCall,
                authConfig: bosca.server.routing.AuthConfig?,
            ) {
                when (call.request.uri) {
                    "/auth-commit" -> call.respond(HttpStatusCode.Unauthorized, "unauthorized")
                    "/auth-fails" -> error("expected authentication failure")
                }
            }
        })
        application.router.post("/echo/{id}") {
            activeRouteHandlers.incrementAndGet()
            try {
                val id = call.pathParameters["id"] ?: error("missing id")
                call.respond(HttpStatusCode.OK, "$id:${call.request.bodyText()}")
            } finally {
                activeRouteHandlers.decrementAndGet()
            }
        }
        application.router.get("/ok") {
            call.respond(HttpStatusCode.OK, "ok")
        }
        application.router.get("/hold") {
            resetHandlerStarted.countDown()
            try {
                awaitCancellation()
            } catch (cause: CancellationException) {
                resetHandlerCancelled.countDown()
                throw cause
            }
        }
        application.routing {
            webSocket("/ws/{id}") {
                val id = call.pathParameters["id"] ?: error("missing id")
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is WebSocketFrame.Text -> {
                                frameConsumed()
                                send("$id:${frame.text}")
                            }
                            is WebSocketFrame.Close -> break
                            else -> frameConsumed()
                        }
                    }
                } finally {
                    if (id == "abrupt") abruptWebSocketClosed.countDown()
                }
            }
            webSocket("/idle") {
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                    frameConsumed()
                }
            }
            webSocket("/protocol", protocol = "required") {
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                    frameConsumed()
                }
            }
            webSocket("/returns") {}
            webSocket("/throws") {
                error("expected WebSocket route failure")
            }
            webSocket("/middleware-commit") { error("committed middleware must skip this route") }
            webSocket("/middleware-fails") { error("failed middleware must skip this route") }
            authenticate("test") {
                webSocket("/auth") {}
                webSocket("/auth-commit") { error("committed authentication must skip this route") }
                webSocket("/auth-fails") { error("failed authentication must skip this route") }
            }
        }

        val running = startServer(application)
        val clientGroup = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
        val parents = mutableListOf<Channel>()
        try {
            exerciseHttp1IsolationAndChurn(running.port)
            exerciseHttp1WebSockets(running.port, abruptWebSocketClosed)

            val primary = connectHttp2(clientGroup, running.port)
            parents += primary
            exerciseHttp2StreamIsolation(primary)
            exerciseHttp2Reset(primary, resetHandlerStarted, resetHandlerCancelled)
            exerciseExtendedConnect(primary)
            exerciseExtendedConnectFailures(primary)
            exerciseExtendedConnectRejections(primary)

            val upgraded = connectHttp2Upgrade(clientGroup, running.port)
            parents += upgraded
            assertEquals("ok", sendHttp2Request(upgraded, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))

            val isolatedParents = (0 until 3).map {
                connectHttp2(clientGroup, running.port).also(parents::add)
            }
            val crossConnectionResponses = isolatedParents.flatMapIndexed { connection, parent ->
                (0 until 12).map { request ->
                    val token = "connection-$connection-stream-$request"
                    token to sendHttp2Request(parent, HttpMethod.POST, "/echo/$token", token)
                }
            }
            crossConnectionResponses.forEach { (token, response) ->
                assertEquals("$token:$token", response.get(10, TimeUnit.SECONDS))
            }

            parents.forEach { parent ->
                if (parent.isActive) {
                    parent.writeAndFlush(DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)).sync()
                    parent.close().sync()
                }
            }
            parents.clear()

            awaitCondition("all accepted channels and streams should close") {
                val snapshot = running.engine.transportSnapshot()
                snapshot.activeConnections == 0 &&
                    snapshot.activeHttp2Streams == 0 &&
                    snapshot.activeApplicationJobs == 0
            }
            assertEquals(0, activeRouteHandlers.get())

            val warmedHeap = usedHeapAfterGc()
            repeat(300) { index ->
                val token = "heap-$index"
                assertTrue(sendHttp1(running.port, "POST", "/echo/$token", token).endsWith("$token:$token"))
            }
            awaitCondition("HTTP1 churn connections should close") {
                running.engine.transportSnapshot().activeConnections == 0
            }
            val retainedGrowth = usedHeapAfterGc() - warmedHeap
            assertTrue(
                retainedGrowth <= MAX_RETAINED_HEAP_GROWTH,
                "Retained heap grew by $retainedGrowth bytes after connection churn",
            )
        } finally {
            parents.forEach { if (it.isOpen) it.close().syncUninterruptibly() }
            clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
            running.close()
        }

        assertTransportStopped(running.engine.transportSnapshot())
        awaitCondition("Netty transport threads should terminate") {
            transportThreadIds().minus(transportThreadsBefore).isEmpty()
        }
        val fileDescriptorsAfter = openFileDescriptorCount()
        if (fileDescriptorsBefore != null && fileDescriptorsAfter != null) {
            assertTrue(
                fileDescriptorsAfter <= fileDescriptorsBefore + FILE_DESCRIPTOR_TOLERANCE,
                "Open file descriptors grew from $fileDescriptorsBefore to $fileDescriptorsAfter",
            )
        }
    }

    @Test
    fun `stopped engine and event loops are collectible`() {
        val references = createStoppedEngineReferences()
        repeat(20) {
            if (references.all { reference -> reference.get() == null }) return
            System.gc()
            System.runFinalization()
            Thread.sleep(100)
        }
        val retained = references.mapNotNull { it.get()?.javaClass?.name }
        assertTrue(retained.isEmpty(), "Stopped transport objects remained strongly reachable: $retained")
    }

    private fun exerciseHttp1IsolationAndChurn(port: Int) {
        val executor = Executors.newFixedThreadPool(12)
        try {
            val responses = (0 until 48).map { index ->
                executor.submit<String> {
                    val token = "http1-$index"
                    sendHttp1(port, "POST", "/echo/$token", token)
                }
            }
            responses.forEachIndexed { index, future ->
                val token = "http1-$index"
                assertTrue(future.get(10, TimeUnit.SECONDS).endsWith("$token:$token"))
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun exerciseHttp1WebSockets(port: Int, abruptClosed: CountDownLatch) {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val sessions = (0 until 24).map { index ->
                executor.submit<Unit> { exerciseHttp1WebSocket(port, index) }
            }
            sessions.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }

        val abrupt = RawWebSocketClient(port, "/ws/abrupt")
        abrupt.sendText("disconnect")
        abrupt.closeAbruptly()
        assertTrue(abruptClosed.await(5, TimeUnit.SECONDS), "Abrupt WebSocket disconnect did not cancel its route")
    }

    private fun exerciseHttp1WebSocket(port: Int, index: Int) {
        RawWebSocketClient(port, "/ws/client-$index").use { client ->
            client.sendText("message-$index")
            val text = client.readFrame()
            assertEquals(0x1, text.opcode)
            assertEquals("client-$index:message-$index", text.payload.toString(StandardCharsets.UTF_8))

            val pingPayload = "ping-$index".encodeToByteArray()
            client.sendFrame(0x9, pingPayload)
            val pong = client.readFrame()
            assertEquals(0xA, pong.opcode)
            assertTrue(pong.payload.contentEquals(pingPayload))

            client.sendClose(1000, "complete")
            val close = client.readFrame()
            assertEquals(0x8, close.opcode)
            assertEquals(1000, close.closeCode())
        }
    }

    private fun exerciseHttp2StreamIsolation(parent: Channel) {
        val responses = (0 until 40).map { index ->
            val token = "h2-stream-$index"
            token to sendHttp2Request(parent, HttpMethod.POST, "/echo/$token", token)
        }
        responses.forEach { (token, response) ->
            assertEquals("$token:$token", response.get(10, TimeUnit.SECONDS))
        }
    }

    private fun exerciseHttp2Reset(
        parent: Channel,
        started: CountDownLatch,
        cancelled: CountDownLatch,
    ) {
        val stream = openHttp2Stream(parent)
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hold")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        stream.writeAndFlush(request).sync()
        assertTrue(started.await(5, TimeUnit.SECONDS), "HTTP/2 reset route did not start")
        stream.close().sync()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS), "RST_STREAM did not cancel the stream route")
        assertEquals("ok", sendHttp2Request(parent, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))
    }

    private fun exerciseExtendedConnect(parent: Channel) {
        val streams = (0 until 24).map { index ->
            openHttp2WebSocket(parent, "/ws/rfc-$index")
        }
        streams.forEachIndexed { index, stream ->
            stream.awaitAccepted()
            stream.sendText("message-$index", masked = true)
        }
        streams.forEachIndexed { index, stream ->
            val frame = stream.takeFrame()
            assertEquals(0x1, frame.opcode)
            assertEquals("rfc-$index:message-$index", frame.payload.toString(StandardCharsets.UTF_8))
            stream.sendClose(1000, "complete")
            assertEquals(1000, stream.takeFrame().closeCode())
            stream.awaitEndStream()
        }
        assertEquals("ok", sendHttp2Request(parent, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))
    }

    private fun exerciseExtendedConnectFailures(parent: Channel) {
        val invalid = openHttp2WebSocket(parent, "/ws/invalid")
        invalid.awaitAccepted()
        invalid.sendText("unmasked", masked = false)
        assertEquals(1002, invalid.takeFrame().closeCode())
        invalid.awaitEndStream()
        assertEquals("ok", sendHttp2Request(parent, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))

        val halfClosed = openHttp2WebSocket(parent, "/ws/half-close")
        halfClosed.awaitAccepted()
        halfClosed.halfClose()
        halfClosed.awaitEndStream()
        assertEquals("ok", sendHttp2Request(parent, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))

        val idle = openHttp2WebSocket(parent, "/idle")
        idle.awaitAccepted()
        assertEquals(1001, idle.takeFrame(5).closeCode())
        idle.awaitEndStream()
        assertEquals("ok", sendHttp2Request(parent, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))
    }

    private fun exerciseExtendedConnectRejections(parent: Channel) {
        val missing = openHttp2WebSocket(parent, "/missing")
        missing.awaitStatus(404)
        missing.awaitEndStream()

        val missingProtocol = openHttp2WebSocket(parent, "/protocol", "other")
        missingProtocol.awaitStatus(400)
        missingProtocol.awaitEndStream()

        val negotiated = openHttp2WebSocket(parent, "/protocol", "other, required")
        negotiated.awaitAccepted()
        negotiated.awaitSelectedSubprotocol("required")
        negotiated.sendClose(1000, "complete")
        assertEquals(1000, negotiated.takeFrame().closeCode())
        negotiated.awaitEndStream()

        listOf(
            "/returns" to 1000,
            "/throws" to 1011,
        ).forEach { (path, expectedCloseCode) ->
            val stream = openHttp2WebSocket(parent, path)
            stream.awaitAccepted()
            assertEquals(expectedCloseCode, stream.takeFrame().closeCode())
            stream.awaitEndStream()
        }
        listOf(
            "/middleware-commit" to 403,
            "/middleware-fails" to 500,
            "/auth-commit" to 401,
            "/auth-fails" to 500,
        ).forEach { (path, status) ->
            val stream = openHttp2WebSocket(parent, path)
            stream.awaitStatus(status)
            stream.awaitEndStream()
        }
        val authenticated = openHttp2WebSocket(parent, "/auth")
        authenticated.awaitAccepted()
        assertEquals(1000, authenticated.takeFrame().closeCode())
        authenticated.awaitEndStream()
        assertEquals("ok", sendHttp2Request(parent, HttpMethod.GET, "/ok", "").get(5, TimeUnit.SECONDS))
    }

    private fun createStoppedEngineReferences(): List<WeakReference<Any>> {
        val application = createApplication()
        application.router.get("/ok") { call.respond(HttpStatusCode.OK, "ok") }
        val running = startServer(application)
        assertTrue(sendHttp1(running.port, "GET", "/ok", "").endsWith("ok"))
        running.close()
        assertTransportStopped(running.engine.transportSnapshot())
        // BoscaApplication is intentionally registered in the process-wide DI registry. The
        // engine and its server thread are the transport-owned roots that must become collectible.
        return listOf(WeakReference(running.engine), WeakReference(running.thread))
    }

    private fun createApplication(): BoscaApplication = BoscaApplication(
        ApplicationConfig.load(
            """
            bosca:
              server:
                worker-threads: 2
                codec-threads: 2
                max-request-size: 1048576
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent().byteInputStream(),
        ),
    )

    private fun startServer(application: BoscaApplication): RunningServer {
        val engine = NettyServerEngine(application, 0)
        val thread = Thread({ engine.start() }, "netty-soak-server")
        thread.isDaemon = true
        thread.start()
        awaitCondition("server should bind") { engine.boundPort != null }
        return RunningServer(engine, thread, checkNotNull(engine.boundPort))
    }

    private fun sendHttp1(port: Int, method: String, path: String, body: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val request = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: close\r\n")
                if (bytes.isNotEmpty() || method == "POST") {
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                }
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            socket.getOutputStream().write(request)
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun connectHttp2(group: MultiThreadIoEventLoopGroup, port: Int): Channel = Bootstrap()
        .group(group)
        .channel(NioSocketChannel::class.java)
        .handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build())
                ch.pipeline().addLast(Http2MultiplexHandler(emptyStreamInitializer()))
            }
        })
        .connect("127.0.0.1", port)
        .sync()
        .channel()

    private fun connectHttp2Upgrade(group: MultiThreadIoEventLoopGroup, port: Int): Channel {
        val upgraded = CompletableFuture<Unit>()
        val channel = Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    val httpCodec = HttpClientCodec()
                    val frameCodec = Http2FrameCodecBuilder.forClient().build()
                    val multiplex = Http2MultiplexHandler(emptyStreamInitializer(), emptyStreamInitializer())
                    ch.pipeline().addLast(httpCodec)
                    ch.pipeline().addLast(
                        HttpClientUpgradeHandler(
                            httpCodec,
                            Http2ClientUpgradeCodec(frameCodec as Http2ConnectionHandler, multiplex),
                            65_536,
                        ),
                    )
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                            if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
                                upgraded.complete(Unit)
                            }
                            ctx.fireUserEventTriggered(evt)
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            upgraded.completeExceptionally(cause)
                            ctx.close()
                        }
                    })
                }
            })
            .connect("127.0.0.1", port)
            .sync()
            .channel()
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ok")
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        channel.writeAndFlush(request).sync()
        upgraded.get(5, TimeUnit.SECONDS)
        return channel
    }

    private fun emptyStreamInitializer(): ChannelInitializer<Channel> = object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) = Unit
    }

    private fun openHttp2Stream(parent: Channel): Channel = Http2StreamChannelBootstrap(parent)
        .handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(false))
            }
        })
        .open()
        .sync()
        .getNow()

    private fun sendHttp2Request(
        parent: Channel,
        method: HttpMethod,
        path: String,
        body: String,
    ): CompletableFuture<String> {
        val response = CompletableFuture<String>()
        val stream = Http2StreamChannelBootstrap(parent)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(false))
                    ch.pipeline().addLast(HttpObjectAggregator(1_048_576))
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            try {
                                if (msg is FullHttpResponse) {
                                    response.complete(msg.content().toString(StandardCharsets.UTF_8))
                                }
                            } finally {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            response.completeExceptionally(cause)
                            ctx.close()
                        }
                    })
                }
            })
            .open()
            .sync()
            .getNow()
        val content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, content)
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        stream.writeAndFlush(request).addListener { future ->
            if (!future.isSuccess) response.completeExceptionally(future.cause())
        }
        return response
    }

    private fun openHttp2WebSocket(
        parent: Channel,
        path: String,
        offeredSubprotocols: String? = null,
    ): Http2WebSocketClient {
        val client = Http2WebSocketClient()
        val stream = Http2StreamChannelBootstrap(parent)
            .handler(client.initializer())
            .open()
            .sync()
            .getNow()
        client.attach(stream)
        val headers = DefaultHttp2Headers()
            .method(HttpMethod.CONNECT.asciiName())
            .scheme("http")
            .authority("localhost")
            .path(path)
            .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
            .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
        offeredSubprotocols?.let { headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, it) }
        stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()
        return client
    }

    private fun usedHeapAfterGc(): Long {
        repeat(4) {
            System.gc()
            System.runFinalization()
            Thread.sleep(100)
        }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private fun openFileDescriptorCount(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)?.openFileDescriptorCount

    private fun transportThreadIds(): Set<Long> = Thread.getAllStackTraces().keys
        .asSequence()
        .filter(Thread::isAlive)
        .filter { thread ->
            thread.name.startsWith("nioEventLoopGroup-") ||
                thread.name.startsWith("epollEventLoopGroup-") ||
                thread.name.startsWith("bosca-codec-")
        }
        .map(Thread::threadId)
        .toSet()

    private fun assertTransportStopped(snapshot: NettyTransportSnapshot) {
        assertEquals(0, snapshot.activeConnections)
        assertEquals(0, snapshot.activeHttp2Streams)
        assertEquals(0, snapshot.activeApplicationJobs)
        assertTrue(snapshot.bossGroupTerminated)
        assertTrue(snapshot.workerGroupTerminated)
        assertTrue(snapshot.codecGroupTerminated)
    }

    private fun awaitCondition(message: String, timeoutSeconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), message)
    }

    private data class RunningServer(
        val engine: NettyServerEngine,
        val thread: Thread,
        val port: Int,
    ) : AutoCloseable {
        override fun close() {
            engine.stopWithoutHalting()
            thread.join(15_000)
            assertFalse(thread.isAlive, "Server thread did not terminate")
        }
    }

    private data class WireFrame(val opcode: Int, val payload: ByteArray) {
        fun closeCode(): Int {
            assertEquals(0x8, opcode)
            assertTrue(payload.size >= 2, "Close frame did not contain a status code")
            return ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        }
    }

    private class RawWebSocketClient(port: Int, path: String) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()

        init {
            val key = ByteArray(16).also(SecureRandom()::nextBytes)
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ${Base64.getEncoder().encodeToString(key)}\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val response = readHttpHeaders(input)
            assertTrue(response.startsWith("HTTP/1.1 101"), response)
        }

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))

        fun sendClose(code: Int, reason: String) {
            val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
            val payload = ByteArray(reasonBytes.size + 2)
            payload[0] = (code ushr 8).toByte()
            payload[1] = code.toByte()
            reasonBytes.copyInto(payload, 2)
            sendFrame(0x8, payload)
        }

        fun sendFrame(opcode: Int, payload: ByteArray) {
            require(payload.size <= 125)
            val mask = ByteArray(4).also(SecureRandom()::nextBytes)
            output.write(0x80 or opcode)
            output.write(0x80 or payload.size)
            output.write(mask)
            payload.forEachIndexed { index, byte -> output.write(byte.toInt() xor mask[index % 4].toInt()) }
            output.flush()
        }

        fun readFrame(): WireFrame = readWireFrame(input)

        fun closeAbruptly() {
            socket.setSoLinger(true, 0)
            socket.close()
        }

        override fun close() {
            if (!socket.isClosed) socket.close()
        }
    }

    private class Http2WebSocketClient {
        private val accepted = CompletableFuture<Pair<Int, String?>>()
        private val frames = LinkedBlockingQueue<WireFrame>()
        private val endStream = CountDownLatch(1)
        private lateinit var stream: Channel

        fun attach(stream: Channel) {
            this.stream = stream
        }

        fun initializer(): ChannelInitializer<Channel> = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                val decoder = EmbeddedChannel(
                    WebSocket13FrameDecoder(
                        WebSocketDecoderConfig.newBuilder()
                            .expectMaskedFrames(false)
                            .allowMaskMismatch(false)
                            .allowExtensions(false)
                            .maxFramePayloadLength(NettyHttpHandler.MAX_WEBSOCKET_FRAME_SIZE)
                            .closeOnProtocolViolation(true)
                            .withUTF8Validator(true)
                            .build(),
                    ),
                )
                ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        try {
                            when (msg) {
                                is Http2HeadersFrame -> {
                                    accepted.complete(
                                        (msg.headers().status()?.toString()?.toInt() ?: -1) to
                                            msg.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)?.toString(),
                                    )
                                    if (msg.isEndStream) endStream.countDown()
                                }
                                is Http2DataFrame -> {
                                    if (msg.content().isReadable) {
                                        decoder.writeInbound(msg.content().retain())
                                        while (true) {
                                            val frame = decoder.readInbound<io.netty.handler.codec.http.websocketx.WebSocketFrame>()
                                                ?: break
                                            try {
                                                when (frame) {
                                                    is TextWebSocketFrame -> frames.add(
                                                        WireFrame(0x1, frame.text().toByteArray(StandardCharsets.UTF_8)),
                                                    )
                                                    is CloseWebSocketFrame -> {
                                                        val reason = frame.reasonText().toByteArray(StandardCharsets.UTF_8)
                                                        val payload = ByteArray(reason.size + 2)
                                                        payload[0] = (frame.statusCode() ushr 8).toByte()
                                                        payload[1] = frame.statusCode().toByte()
                                                        reason.copyInto(payload, 2)
                                                        frames.add(WireFrame(0x8, payload))
                                                        ctx.writeAndFlush(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
                                                    }
                                                }
                                            } finally {
                                                ReferenceCountUtil.release(frame)
                                            }
                                        }
                                    }
                                    if (msg.isEndStream) endStream.countDown()
                                }
                            }
                        } finally {
                            ReferenceCountUtil.release(msg)
                        }
                    }

                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        decoder.finishAndReleaseAll()
                        endStream.countDown()
                        ctx.fireChannelInactive()
                    }

                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        accepted.completeExceptionally(cause)
                        ctx.close()
                    }
                })
            }
        }

        fun awaitAccepted() = awaitStatus(200)

        fun awaitStatus(expected: Int) = assertEquals(expected, accepted.get(5, TimeUnit.SECONDS).first)

        fun awaitSelectedSubprotocol(expected: String) =
            assertEquals(expected, accepted.get(5, TimeUnit.SECONDS).second)

        fun sendText(text: String, masked: Boolean) {
            stream.writeAndFlush(DefaultHttp2DataFrame(encodeFrame(TextWebSocketFrame(text), masked), false)).sync()
        }

        fun sendClose(code: Int, reason: String) {
            stream.writeAndFlush(
                DefaultHttp2DataFrame(encodeFrame(CloseWebSocketFrame(code, reason), masked = true), false),
            ).sync()
        }

        fun halfClose() {
            stream.writeAndFlush(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true)).sync()
        }

        fun takeFrame(timeoutSeconds: Long = 5): WireFrame =
            assertNotNull(frames.poll(timeoutSeconds, TimeUnit.SECONDS), "Timed out waiting for WebSocket frame")

        fun awaitEndStream() {
            assertTrue(endStream.await(5, TimeUnit.SECONDS), "Timed out waiting for HTTP/2 END_STREAM")
        }

        private fun encodeFrame(
            frame: io.netty.handler.codec.http.websocketx.WebSocketFrame,
            masked: Boolean,
        ): ByteBuf {
            val encoder = EmbeddedChannel(WebSocket13FrameEncoder(masked))
            return try {
                assertTrue(encoder.writeOutbound(frame))
                encoder.readOutbound()
            } finally {
                encoder.finishAndReleaseAll()
            }
        }
    }

    private class CapturingLeakDetectorFactory : ResourceLeakDetectorFactory() {
        val leaks = ConcurrentLinkedQueue<String>()
        val detectorsCreated = AtomicInteger()

        override fun <T : Any?> newResourceLeakDetector(
            resource: Class<T>,
            samplingInterval: Int,
            maxActive: Long,
        ): ResourceLeakDetector<T> {
            detectorsCreated.incrementAndGet()
            return ResourceLeakDetector<T>(resource, 1, maxActive).apply {
                setLeakListener { resourceType, records -> leaks.add("$resourceType: $records") }
            }
        }
    }

    companion object {
        private const val MAX_RETAINED_HEAP_GROWTH = 16L * 1024 * 1024
        private const val FILE_DESCRIPTOR_TOLERANCE = 4L
        private val leakFactory = CapturingLeakDetectorFactory()

        init {
            ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(leakFactory)
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
        }

        private fun readHttpHeaders(input: BufferedInputStream): String {
            val output = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < terminator.size) {
                val value = input.read()
                if (value < 0) throw EOFException("Connection closed during HTTP headers")
                output.write(value)
                matched = if (value.toByte() == terminator[matched]) matched + 1 else 0
            }
            return output.toString(StandardCharsets.US_ASCII)
        }

        private fun readWireFrame(input: BufferedInputStream): WireFrame {
            val first = input.read()
            val second = input.read()
            if (first < 0 || second < 0) throw EOFException("Connection closed before WebSocket frame")
            var length = second and 0x7f
            if (length == 126) length = readUnsignedShort(input)
            if (length == 127) {
                var longLength = 0L
                repeat(8) { longLength = (longLength shl 8) or readRequiredByte(input).toLong() }
                require(longLength <= Int.MAX_VALUE)
                length = longLength.toInt()
            }
            val mask = if (second and 0x80 != 0) ByteArray(4) { readRequiredByte(input).toByte() } else null
            val payload = ByteArray(length) { readRequiredByte(input).toByte() }
            if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            return WireFrame(first and 0x0f, payload)
        }

        private fun readUnsignedShort(input: BufferedInputStream): Int =
            (readRequiredByte(input) shl 8) or readRequiredByte(input)

        private fun readRequiredByte(input: BufferedInputStream): Int =
            input.read().takeIf { it >= 0 } ?: throw EOFException("Connection closed during WebSocket frame")

        private fun forceGcAndLeakReporting() {
            repeat(8) {
                System.gc()
                System.runFinalization()
                repeat(256) { Unpooled.buffer(1).release() }
                Thread.sleep(100)
            }
        }
    }
}
