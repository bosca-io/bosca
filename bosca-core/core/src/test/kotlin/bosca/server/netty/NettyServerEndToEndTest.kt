package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.routing.AuthConfig
import bosca.server.websocket.CloseReason
import bosca.server.websocket.WebSocketFrame
import bosca.server.websocket.WebSocketSession
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
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame as NettyCloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec
import io.netty.handler.codec.http2.Http2ConnectionHandler
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2GoAwayFrame
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2ResetFrame
import io.netty.handler.codec.http2.Http2SettingsFrame
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.util.ReferenceCountUtil
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests using a real Netty server over TCP to verify responses
 * actually arrive at the client through the full pipeline.
 */
class NettyServerEndToEndTest {

    private val testPort = 19876

    private fun createApplication(yaml: String? = null): BoscaApplication = if (yaml == null) {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        BoscaApplication(config)
    } else {
        BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))
    }

    private fun startServer(app: BoscaApplication): NettyServerEngine {
        val engine = NettyServerEngine(app, testPort)
        val thread = Thread { engine.start() }
        thread.isDaemon = true
        thread.start()
        // Wait for port to be available
        for (i in 0 until 50) {
            try {
                Socket("localhost", testPort).use { }
                return engine
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        throw RuntimeException("Server didn't start in time")
    }

    @Test
    fun `default executor sizing supports small containers and larger hosts`() {
        assertEquals(1, NettyServerSettings.defaultWorkerThreadCount(1))
        assertEquals(2, NettyServerSettings.defaultWorkerThreadCount(2))
        assertEquals(4, NettyServerSettings.defaultWorkerThreadCount(16))
        assertEquals(4, NettyServerSettings.defaultCodecThreadCount(1))
        assertEquals(4, NettyServerSettings.defaultCodecThreadCount(2))
        assertEquals(16, NettyServerSettings.defaultCodecThreadCount(16))
        assertEquals(2, NettyServerSettings.defaultRequestThreadCount(1))
        assertEquals(2, NettyServerSettings.defaultRequestThreadCount(2))
        assertEquals(16, NettyServerSettings.defaultRequestThreadCount(16))
    }

    @Test
    fun `request dispatcher defaults to the event loop and accepts pool`() {
        fun parse(value: String?) = NettyServerSettings.configuredRequestDispatcher(
            ApplicationConfig.load(
                (if (value == null) "" else "bosca: { server: { request-dispatcher: $value } }").byteInputStream(),
            ),
        )
        assertEquals(RequestDispatcherMode.EVENT_LOOP, parse(null))
        assertEquals(RequestDispatcherMode.EVENT_LOOP, parse("event-loop"))
        assertEquals(RequestDispatcherMode.POOL, parse("pool"))
        assertEquals(RequestDispatcherMode.POOL, parse("POOL"))
        assertEquals(RequestDispatcherMode.EVENT_LOOP, parse("threads"), "Unknown values fall back to event-loop")
    }

    @Test
    fun `request thread count is configurable and rejects values below one`() {
        assertEquals(
            8,
            NettyServerSettings.configuredRequestThreads(
                ApplicationConfig.load("bosca: { server: { request-threads: 8 } }".byteInputStream()),
                availableProcessors = 2,
            ),
        )
        listOf("invalid", "0", "-3").forEach { configuredValue ->
            assertEquals(
                2,
                NettyServerSettings.configuredRequestThreads(
                    ApplicationConfig.load("bosca: { server: { request-threads: $configuredValue } }".byteInputStream()),
                    availableProcessors = 2,
                ),
                "request-threads=$configuredValue must fall back to the default",
            )
        }
    }

    @Test
    fun `worker and codec thread counts below one fall back to defaults instead of failing startup`() {
        val settings = NettyServerSettings.from(
            ApplicationConfig.load("bosca: { server: { worker-threads: 0, codec-threads: -1 } }".byteInputStream()),
            availableProcessors = 2,
        )
        assertEquals(NettyServerSettings.defaultWorkerThreadCount(2), settings.workerThreads)
        assertEquals(NettyServerSettings.defaultCodecThreadCount(2), settings.codecThreads)

        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 0
                codec-threads: -1
            """.trimIndent()
        )
        NettyServerEngine(app, 0).stopWithoutHalting()
    }

    @Test
    fun `response compression and HTTP2 default on and accept explicit false values`() {
        assertTrue(NettyServerSettings.configuredCompressionEnabled(ApplicationConfig.load("".byteInputStream())))
        assertTrue(
            NettyServerSettings.configuredCompressionEnabled(
                ApplicationConfig.load("bosca: { server: { compression-enabled: invalid } }".byteInputStream()),
            ),
        )
        assertFalse(
            NettyServerSettings.configuredCompressionEnabled(
                ApplicationConfig.load("bosca: { server: { compression-enabled: false } }".byteInputStream()),
            ),
        )
        assertTrue(NettyServerSettings.configuredHttp2Enabled(ApplicationConfig.load("".byteInputStream())))
        assertTrue(
            NettyServerSettings.configuredHttp2Enabled(
                ApplicationConfig.load("bosca: { server: { http2-enabled: invalid } }".byteInputStream()),
            ),
        )
        assertFalse(
            NettyServerSettings.configuredHttp2Enabled(
                ApplicationConfig.load("bosca: { server: { http2-enabled: false } }".byteInputStream()),
            ),
        )
        assertTrue(
            HttpChannelInitializer.http2InitialSettings().connectProtocolEnabled() == true,
            "HTTP/2 must advertise SETTINGS_ENABLE_CONNECT_PROTOCOL for RFC 8441",
        )
        assertEquals(
            NettyServerSettings.DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS,
            HttpChannelInitializer.http2InitialSettings().maxConcurrentStreams(),
        )

        listOf("invalid", "0", "-1", "4294967296").forEach { configuredValue ->
            val config = ApplicationConfig.load(
                "bosca: { server: { http2-max-concurrent-streams: $configuredValue } }".byteInputStream(),
            )
            assertEquals(
                NettyServerSettings.DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS,
                NettyServerSettings.configuredHttp2MaxConcurrentStreams(config),
            )
        }
        assertEquals(
            256L,
            NettyServerSettings.configuredHttp2MaxConcurrentStreams(
                ApplicationConfig.load(
                    "bosca: { server: { http2-max-concurrent-streams: 256 } }".byteInputStream(),
                ),
            ),
        )
    }

    @Test
    fun `HTTP 404 response arrives for unmatched route over real TCP`() {
        val app = createApplication()
        app.router.get("/exists") {
            call.respond(HttpStatusCode.OK, "hello")
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(
                "GET /nonexistent HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept-Encoding: gzip\r\n" +
                    "Connection: close\r\n\r\n",
            )
            assertTrue(response.contains("404"), "Response should contain 404 status, got: [$response]")
            assertTrue(
                response.contains("Content-Encoding: gzip", ignoreCase = true),
                "Response should be compressed by default, got: [$response]",
            )
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP 200 response arrives for matched route over real TCP`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent()
        )
        app.router.get("/hello") {
            call.respond(HttpStatusCode.OK, "world")
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(
                "GET /hello HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept-Encoding: gzip\r\n" +
                    "Connection: close\r\n\r\n",
            )
            assertTrue(response.contains("200"), "Response should contain 200 status, got: [$response]")
            assertTrue(response.contains("world"), "Response should contain body, got: [$response]")
            assertFalse(
                response.contains("Content-Encoding", ignoreCase = true),
                "Response should not be compressed when compression is disabled, got: [$response]",
            )
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `route resolution failure returns HTTP 500 over real TCP`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        app.router.staticResources("/assets", "test-static")

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(
                "GET /assets/%00 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                timeoutMs = 5000,
            )

            assertTrue(
                response.startsWith("HTTP/1.1 500"),
                "A route-resolution exception must produce HTTP 500, got: [$response]",
            )
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `body-bearing h2c upgrade request falls back to HTTP1`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.router.post("/graphql") {
            call.respond(HttpStatusCode.OK, call.request.bodyText())
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(
                "POST /graphql HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: Upgrade, HTTP2-Settings, close\r\n" +
                    "Upgrade: h2c\r\n" +
                    "HTTP2-Settings: AAMAAABk\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 2\r\n\r\n" +
                    "{}",
            )

            assertTrue(response.contains("200"), "Response should contain 200 status, got: [$response]")
            assertTrue(response.contains("{}"), "HTTP1 fallback should deliver the request body, got: [$response]")
            assertFalse(response.contains("413"), "Upgrade aggregation must not reject the request, got: [$response]")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `pool dispatcher runs requests away from the channel event loop across suspension`() {
        val app = createApplication(
            """
            bosca:
              server:
                request-dispatcher: pool
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
            """.trimIndent()
        )
        val beforeSuspension = AtomicReference<String>()
        val afterSuspension = AtomicReference<String>()
        app.router.get("/suspended") {
            beforeSuspension.set(Thread.currentThread().name)
            yield()
            afterSuspension.set(Thread.currentThread().name)
            call.respond(HttpStatusCode.OK, "resumed")
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest("GET /suspended HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")

            assertTrue(response.contains("200"), "Response should contain 200 status, got: [$response]")
            assertFalse(beforeSuspension.get().startsWith("bosca-codec-"))
            assertFalse(beforeSuspension.get().startsWith("bosca-worker-"))
            assertTrue(beforeSuspension.get().startsWith("bosca-request-"), "Handlers must run on the request pool")
            assertFalse(afterSuspension.get().startsWith("bosca-codec-"))
            assertFalse(afterSuspension.get().startsWith("bosca-worker-"))
            assertTrue(afterSuspension.get().startsWith("bosca-request-"), "Handlers must resume on the request pool")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `event-loop dispatcher runs requests on the channel event loop and resumes there after leaving it`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
            """.trimIndent()
        )
        val beforeSuspension = AtomicReference<String>()
        val offLoop = AtomicReference<String>()
        val afterSuspension = AtomicReference<String>()
        app.router.get("/suspended") {
            beforeSuspension.set(Thread.currentThread().name)
            // A real suspension onto another dispatcher, as a database or IO call makes.
            withContext(Dispatchers.IO) { offLoop.set(Thread.currentThread().name) }
            afterSuspension.set(Thread.currentThread().name)
            call.respond(HttpStatusCode.OK, "resumed")
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest("GET /suspended HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")

            assertTrue(response.contains("200"), "Response should contain 200 status, got: [$response]")
            assertTrue(beforeSuspension.get().startsWith("bosca-worker-"), "Handlers must start on the event loop")
            assertFalse(offLoop.get().startsWith("bosca-worker-"), "withContext(Dispatchers.IO) must leave the event loop")
            assertEquals(
                beforeSuspension.get(),
                afterSuspension.get(),
                "Handlers must resume on their own channel's event loop",
            )
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `management liveness fails while the request event loop is stuck and recovers once it is released`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                management-port: 0
                management-liveness:
                  stall-timeout-ms: 500
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
            """.trimIndent()
        )
        val handlerBlocking = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        app.router.get("/block-loop") {
            handlerBlocking.countDown()
            // Deliberately blocks the only event loop, as an unwrapped blocking call would.
            releaseHandler.await(10, TimeUnit.SECONDS)
            call.respond(HttpStatusCode.OK, "released")
        }

        val engine = startServer(app)
        val managementPort = assertNotNull(engine.boundManagementPort, "management-port: 0 must bind a port")
        fun probe() = sendHttpRequestTo(managementPort, "GET /api/v1/live HTTP/1.1\r\nHost: localhost\r\n\r\n", timeoutMs = 10_000)

        // Probes answer at once from the heartbeat state, which changes within the stall timeout
        // plus one heartbeat period; poll for the expected status.
        fun probeUntil(status: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val response = probe()
                if (response.startsWith("HTTP/1.1 $status") || System.nanoTime() > deadline) return response
                Thread.sleep(50)
            }
        }

        val healthy = probe()
        assertTrue(healthy.startsWith("HTTP/1.1 200"), "A responsive server is live, got: [$healthy]")
        assertTrue(healthy.contains("""{"status":"live"}"""), healthy)

        val blocked = thread(isDaemon = true) {
            sendHttpRequest("GET /block-loop HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", timeoutMs = 15000)
        }
        try {
            assertTrue(handlerBlocking.await(5, TimeUnit.SECONDS), "The blocking handler must start")

            val stalled = probeUntil("503")
            assertTrue(stalled.startsWith("HTTP/1.1 503"), "A stuck event loop fails liveness, got: [$stalled]")
            assertTrue(stalled.contains("""{"status":"stalled","executors":["event loop 1"]}"""), stalled)

            val other = sendHttpRequestTo(managementPort, "GET /api/v1/ready HTTP/1.1\r\nHost: localhost\r\n\r\n", timeoutMs = 1000)
            assertTrue(other.startsWith("HTTP/1.1 404"), "Only liveness is served on the management port, got: [$other]")

            releaseHandler.countDown()
            blocked.join(5000)
            val recovered = probeUntil("200")
            assertTrue(recovered.startsWith("HTTP/1.1 200"), "Liveness recovers once the loop runs again, got: [$recovered]")
        } finally {
            releaseHandler.countDown()
            blocked.join(5000)
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `management listener is disabled unless configured`() {
        fun parse(yaml: String) = NettyServerSettings.configuredManagementPort(ApplicationConfig.load(yaml.byteInputStream()))
        assertNull(parse(""))
        assertEquals(9090, parse("bosca: { server: { management-port: 9090 } }"))
        assertEquals(0, parse("bosca: { server: { management-port: 0 } }"))
        assertNull(parse("bosca: { server: { management-port: 70000 } }"))
        assertNull(parse("bosca: { server: { management-port: none } }"))
    }

    @Test
    fun `pipelined liveness probes are all answered in order`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                management-port: 0
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
            """.trimIndent()
        )
        val engine = startServer(app)
        val managementPort = assertNotNull(engine.boundManagementPort)
        val live = "GET /api/v1/live HTTP/1.1\r\nHost: localhost\r\n\r\n"
        val other = "GET /api/v1/ready HTTP/1.1\r\nHost: localhost\r\n\r\n"
        try {
            Socket("localhost", managementPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write((live + other + live).repeat(5).encodeToByteArray())
                    flush()
                }
                val statuses = ArrayList<String>()
                val responses = StringBuilder()
                val buffer = ByteArray(4096)
                while (statuses.size < 15) {
                    val read = socket.getInputStream().read(buffer)
                    check(read > 0) { "Connection closed after: $responses" }
                    responses.append(String(buffer, 0, read))
                    statuses.clear()
                    Regex("HTTP/1.1 (\\d{3})").findAll(responses).mapTo(statuses) { it.groupValues[1] }
                }
                assertEquals(List(5) { listOf("200", "404", "200") }.flatten(), statuses)
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `event-loop WebSocket session records going away when the client drops the connection`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                http2-enabled: false
            """.trimIndent()
        )
        val session = CompletableFuture<WebSocketSession>()
        app.routing {
            webSocket("/drop") {
                session.complete(this)
                for (frame in incoming) {
                    // Wait for the client to go away.
                }
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write(webSocketUpgradeRequest("/drop").encodeToByteArray())
                    flush()
                }
                assertTrue(readHttpHeaders(socket.getInputStream()).startsWith("HTTP/1.1 101"))
                session.get(5, TimeUnit.SECONDS)
            }
            val reason = runBlocking { withTimeout(5000) { session.get().closeReason.await() } }
            assertEquals(CloseReason.Codes.GOING_AWAY, reason?.code, "A dropped connection is not a normal close: $reason")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `event-loop WebSocket session records the peer's close reason`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                http2-enabled: false
            """.trimIndent()
        )
        val session = CompletableFuture<WebSocketSession>()
        app.routing {
            webSocket("/peer-close") {
                session.complete(this)
                for (frame in incoming) {
                    // Return once the peer's close frame arrives.
                }
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write(webSocketUpgradeRequest("/peer-close").encodeToByteArray())
                    flush()
                }
                val input = socket.getInputStream()
                assertTrue(readHttpHeaders(input).startsWith("HTTP/1.1 101"))
                session.get(5, TimeUnit.SECONDS)
                writeWebSocketFrames(socket, NettyCloseWebSocketFrame(4001, "done"))
                assertEquals(4001, readServerWebSocketCloseCode(input), "The server echoes the peer's close")
            }
            val reason = runBlocking { withTimeout(5000) { session.get().closeReason.await() } }
            assertEquals(CloseReason(4001, "done"), reason)
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `a fragmented text message that is not valid UTF-8 is closed with 1007`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                http2-enabled: false
            """.trimIndent()
        )
        val session = CompletableFuture<WebSocketSession>()
        app.routing {
            webSocket("/utf8") {
                session.complete(this)
                for (frame in incoming) {
                    // The invalid frame must never reach the route.
                }
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write(webSocketUpgradeRequest("/utf8").encodeToByteArray())
                    flush()
                }
                val input = socket.getInputStream()
                assertTrue(readHttpHeaders(input).startsWith("HTTP/1.1 101"))
                session.get(5, TimeUnit.SECONDS)
                // 0xC3 0x28 is an invalid two-byte UTF-8 sequence, here split across a fragmented
                // message: the validator sits ahead of the aggregator, so it checks continuation
                // frames too.
                writeWebSocketFrames(
                    socket,
                    TextWebSocketFrame(false, 0, Unpooled.wrappedBuffer(byteArrayOf(0xC3.toByte()))),
                    ContinuationWebSocketFrame(true, 0, Unpooled.wrappedBuffer(byteArrayOf(0x28))),
                )
                assertEquals(1007, readServerWebSocketCloseCode(input))
            }
            val reason = runBlocking { withTimeout(5000) { session.get().closeReason.await() } }
            assertEquals(1007, reason?.code)
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `event-loop request body larger than the read-pause watermark arrives intact`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent()
        )
        app.router.post("/upload") {
            val digest = MessageDigest.getInstance("SHA-256")
            // A slow consumer, so the socket fills and reads pause and resume repeatedly.
            val received = call.request.bodyStreamTo(object : OutputStream() {
                override fun write(b: Int) {
                    digest.update(b.toByte())
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    Thread.sleep(1)
                    digest.update(b, off, len)
                }
            })
            call.respond(HttpStatusCode.OK, "$received:${HexFormat.of().formatHex(digest.digest())}")
        }
        val body = ByteArray(4 * 1024 * 1024) { (it * 31).toByte() }
        val expected = "${body.size}:${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))}"

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 30000
                val writer = thread(isDaemon = true) {
                    socket.getOutputStream().run {
                        write(
                            "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                .encodeToByteArray(),
                        )
                        write(body)
                        flush()
                    }
                }
                val response = String(socket.getInputStream().readAllBytes())
                writer.join(5000)
                assertTrue(response.startsWith("HTTP/1.1 200"), response)
                assertTrue(response.endsWith(expected), "Expected [$expected] in [$response]")
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `a streaming response ending at its time limit is not reported as a failure`() {
        val app = createApplication()
        val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                exceptions += cause
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/watch") {
            call.response.respondStreamingWithTimeout(bosca.server.ContentType.Text.Plain, timeoutMs = 200) { stream ->
                stream.write("event\n".encodeToByteArray())
                stream.flush()
                awaitCancellation()
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().run {
                    write("GET /watch HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                    flush()
                }
                // The time limit closes the stream; the client sees the start of the response, then EOF.
                val response = String(socket.getInputStream().readAllBytes())
                assertTrue(response.startsWith("HTTP/1.1 200") && response.contains("event"), response)
            }
            // afterCall runs after the dispatcher has decided whether to report the ending.
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(exceptions.isEmpty(), "Reaching the time limit is how watch routes end: $exceptions")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `a timeout after the response was committed is still reported`() {
        val app = createApplication()
        val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val finished = CountDownLatch(1)
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                exceptions += cause
            }

            override suspend fun afterCall(call: ServerCall) {
                finished.countDown()
            }
        })
        app.router.get("/late-timeout") {
            call.respond(HttpStatusCode.OK, "answered")
            withTimeout(10) { awaitCancellation() }
        }

        val engine = startServer(app)
        try {
            // Keep the connection open until the request has finished: closing it would cancel the
            // handler, which is a disconnect, not the timeout this test is about.
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write("GET /late-timeout HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                    flush()
                }
                val buffer = ByteArray(1024)
                val read = socket.getInputStream().read(buffer)
                val response = String(buffer, 0, read)
                assertTrue(response.startsWith("HTTP/1.1 200"), "Got: [$response]")
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            }
            assertTrue(
                exceptions.singleOrNull() is kotlinx.coroutines.TimeoutCancellationException,
                "Only the streaming time limit is an expected ending; this timeout must be reported: $exceptions",
            )
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `the management listener refuses connections beyond its cap`() {
        val app = createApplication(
            """
            bosca:
              server:
                management-port: 0
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
            """.trimIndent()
        )
        val engine = startServer(app)
        val held = mutableListOf<Socket>()
        try {
            val managementPort = assertNotNull(engine.boundManagementPort)
            repeat(ManagementServer.MAX_CONNECTIONS) {
                held += Socket("localhost", managementPort).also { it.soTimeout = 5000 }
            }
            // Every held connection still answers probes.
            held.first().getOutputStream().run {
                write("GET /api/v1/live HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                flush()
            }
            val buffer = ByteArray(256)
            val read = held.first().getInputStream().read(buffer)
            assertTrue(String(buffer, 0, read).startsWith("HTTP/1.1 200"))

            // One more is closed straight away instead of holding a descriptor.
            Socket("localhost", managementPort).use { extra ->
                extra.soTimeout = 5000
                val closed = try {
                    extra.getInputStream().read() == -1
                } catch (_: java.net.SocketException) {
                    true
                }
                assertTrue(closed, "A connection over the cap must be closed")
            }
        } finally {
            held.forEach { it.close() }
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `a timing-out onException middleware closes the connection instead of leaving it waiting`() {
        val app = createApplication()
        app.install(object : CallMiddleware {
            override suspend fun onException(call: ServerCall, cause: Throwable) {
                withTimeout(10) { awaitCancellation() }
            }
        })
        app.router.get("/fails") {
            error("handler failed")
        }

        val engine = startServer(app)
        try {
            // The middleware's cancellation stops the unwind (by design), so no 500 is sent; the
            // keep-alive connection must close promptly rather than wait for the idle timeout.
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().run {
                    write("GET /fails HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                    flush()
                }
                val startedAt = System.nanoTime()
                val received = String(socket.getInputStream().readAllBytes())
                val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                assertEquals("", received, "Nothing may be sent: not a default 200")
                assertTrue(waitedMillis < 5_000, "The connection must close promptly, took $waitedMillis ms")
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `a failed main bind stops the management listener instead of passing liveness`() {
        // A port that was free a moment ago, rather than a fixed one another process might hold.
        val managementPort = java.net.ServerSocket(0).use { it.localPort }
        val holder = startServer(createApplication())
        try {
            val app = createApplication(
                """
                bosca:
                  server:
                    management-port: $managementPort
                    drain-timeout-ms: 0
                    shutdown-timeout-ms: 5000
                """.trimIndent()
            )
            val engine = NettyServerEngine(app, testPort)
            // NIO reports BindException, epoll a native IOException; both are IOExceptions.
            assertFailsWith<java.io.IOException> { engine.start() }
            assertNull(engine.boundManagementPort)
            assertFailsWith<java.net.ConnectException> { Socket("localhost", managementPort).close() }
        } finally {
            holder.stopWithoutHalting()
        }
    }

    @Test
    fun `event-loop idle WebSocket receives its going-away close frame before the connection closes`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                http2-enabled: false
            """.trimIndent()
        )
        val session = CompletableFuture<WebSocketSession>()
        app.routing {
            webSocket("/idle") {
                session.complete(this)
                // Wait on the close reason, as the GraphQL protocol handler does: recording the reason
                // wakes this route, so the close frame must already be written by then.
                closeReason.await()
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().run {
                    write(webSocketUpgradeRequest("/idle").encodeToByteArray())
                    flush()
                }
                val input = socket.getInputStream()
                assertTrue(readHttpHeaders(input).startsWith("HTTP/1.1 101"))
                assertEquals(CloseReason.Codes.GOING_AWAY, readServerWebSocketCloseCode(input))
            }
            val reason = runBlocking { withTimeout(5000) { session.get().closeReason.await() } }
            assertEquals(CloseReason(CloseReason.Codes.GOING_AWAY, "Idle timeout"), reason)
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `a timeout escaping a handler is answered as a server error, not 200`() {
        val app = createApplication()
        app.router.get("/times-out") {
            withTimeout(10) { awaitCancellation() }
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest("GET /times-out HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 500"), "Got: [$response]")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `an Error escaping a handler closes its connection without a 200 and leaves the server serving`() {
        val app = createApplication()
        app.router.get("/unimplemented") {
            TODO("not built yet")
        }
        app.router.get("/ok") {
            call.respond(HttpStatusCode.OK, "fine")
        }

        val engine = startServer(app)
        try {
            // A pipelined second request on the same connection must not be left waiting: the
            // connection closes promptly (EOF), well before the read timeout, with no response at all.
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().run {
                    write(
                        ("GET /unimplemented HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                            "GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n").encodeToByteArray(),
                    )
                    flush()
                }
                val startedAt = System.nanoTime()
                val received = socket.getInputStream().readAllBytes()
                val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                assertEquals("", String(received), "The failed request must not be answered")
                assertTrue(waitedMillis < 5_000, "The connection must close promptly, took $waitedMillis ms")
            }
            val next = sendHttpRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertTrue(next.startsWith("HTTP/1.1 200"), "Got: [$next]")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `pipelined HTTP1 requests execute and respond in order`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
            """.trimIndent(),
        )
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        app.router.get("/first") {
            firstStarted.countDown()
            releaseFirst.await()
            call.respond(HttpStatusCode.OK, "first")
        }
        app.router.get("/second") {
            secondStarted.countDown()
            call.respond(HttpStatusCode.OK, "second")
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 3000
                val writer = OutputStreamWriter(socket.getOutputStream())
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.write(
                    "GET /first HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n" +
                        "GET /second HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n",
                )
                writer.flush()

                assertTrue(firstStarted.await(3, TimeUnit.SECONDS), "First handler should start")
                assertFalse(
                    secondStarted.await(250, TimeUnit.MILLISECONDS),
                    "Second handler must wait for the first response",
                )

                releaseFirst.complete(Unit)
                val firstOnWire = readHttpResponse(reader)
                val secondOnWire = readHttpResponse(reader)

                assertTrue(firstOnWire.endsWith("first"), firstOnWire)
                assertTrue(secondStarted.await(3, TimeUnit.SECONDS), "Second handler should start after the first")
                assertTrue(secondOnWire.endsWith("second"), secondOnWire)
            }
        } finally {
            releaseFirst.complete(Unit)
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP2 streams execute independently on one connection`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 2
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        app.router.get("/first") {
            firstStarted.countDown()
            releaseFirst.await()
            call.respond(HttpStatusCode.OK, "first")
        }
        app.router.get("/second") {
            secondStarted.countDown()
            call.respond(HttpStatusCode.OK, "second")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        try {
            val inboundStreamInitializer = object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) = Unit
            }
            connection = connectHttp2(clientGroup, inboundStreamInitializer)

            val firstResponse = sendHttp2Request(connection, "/first")
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS), "First HTTP/2 stream should start")

            val secondResponse = sendHttp2Request(connection, "/second")
            assertEquals("second", secondResponse.get(3, TimeUnit.SECONDS))
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS), "Second HTTP/2 stream should run independently")
            assertFalse(firstResponse.isDone, "The suspended first stream must still be waiting")

            releaseFirst.complete(Unit)
            assertEquals("first", firstResponse.get(3, TimeUnit.SECONDS))
        } finally {
            releaseFirst.complete(Unit)
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP2 rejects connection-specific headers without invoking the application`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val malformedRequestReachedApplication = CompletableFuture<Unit>()
        app.router.get("/must-not-run") {
            malformedRequestReachedApplication.complete(Unit)
            call.respond(HttpStatusCode.OK, "accepted-invalid-request")
        }
        app.router.get("/after-malformed") {
            call.respond(HttpStatusCode.OK, "connection-alive")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            val resetError = CompletableFuture<Long>()
            connection = connectHttp2(clientGroup, resetReceived = resetError)
            stream = Http2StreamChannelBootstrap(connection)
                .handler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                try {
                                    when (msg) {
                                        is Http2HeadersFrame -> {
                                            val status = msg.headers().status()
                                            if (status != null) {
                                                resetError.completeExceptionally(
                                                    AssertionError(
                                                        "Malformed HTTP/2 request was accepted with status $status",
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                } finally {
                                    ReferenceCountUtil.release(msg)
                                }
                            }

                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                resetError.completeExceptionally(cause)
                                ctx.close()
                            }
                        })
                    }
                })
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/must-not-run")
                .set(HttpHeaderNames.CONNECTION, "keep-alive")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, true)).sync()

            val outcome = CompletableFuture.anyOf(
                resetError.thenApply { "reset:$it" },
                malformedRequestReachedApplication.thenApply { "application-invoked" },
            ).get(3, TimeUnit.SECONDS)
            assertEquals("reset:${Http2Error.PROTOCOL_ERROR.code()}", outcome)
            assertFalse(
                malformedRequestReachedApplication.isDone,
                "A malformed HTTP/2 request must be rejected before application routing",
            )
            assertEquals(
                "connection-alive",
                sendHttp2Request(connection, "/after-malformed").get(3, TimeUnit.SECONDS),
                "Rejecting one malformed stream must not close its parent HTTP/2 connection",
            )
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP2 delivers a request body without content length`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.router.post("/stream") {
            call.respond(HttpStatusCode.OK, call.request.bodyText())
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        try {
            connection = connectHttp2(clientGroup)

            assertEquals(
                "streamed-body",
                sendHttp2Request(connection, "/stream", HttpMethod.POST, "streamed-body")
                    .get(3, TimeUnit.SECONDS),
            )
        } finally {
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP2 HEAD response preserves headers without sending a body`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.router.get("/head") {
            call.respond(HttpStatusCode.OK, "must-not-be-sent")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        try {
            connection = connectHttp2(clientGroup)

            assertEquals(
                "",
                sendHttp2Request(connection, "/head", HttpMethod.HEAD).get(3, TimeUnit.SECONDS),
            )
        } finally {
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `active HTTP2 streams keep their parent connection out of idle timeout`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.router.get("/ping") {
            call.respond(HttpStatusCode.OK, "pong")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        try {
            connection = connectHttp2(clientGroup)
            repeat(4) {
                assertEquals("pong", sendHttp2Request(connection, "/ping").get(3, TimeUnit.SECONDS))
                Thread.sleep(600)
            }
            assertTrue(connection.isActive)
        } finally {
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `ordinary idle HTTP2 stream does not disable its parent timeout`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val routeStarted = CountDownLatch(1)
        app.router.get("/blocked") {
            routeStarted.countDown()
            awaitCancellation()
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup)
            stream = Http2StreamChannelBootstrap(connection)
                .handler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                ReferenceCountUtil.release(msg)
                            }
                        })
                    }
                })
                .open()
                .sync()
                .getNow()
            val headers = DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/blocked")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, true)).sync()

            assertTrue(routeStarted.await(3, TimeUnit.SECONDS))
            assertTrue(
                connection.closeFuture().await(3, TimeUnit.SECONDS),
                "A stalled ordinary stream disabled the parent connection idle timeout",
            )
            assertFalse(connection.isActive)
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `idle HTTP2 SSE streams preserve prior knowledge and h2c parent lifecycle`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.routing {
            sse("/events") {
                send("connected")
                awaitCancellation()
            }
        }
        app.router.get("/ping") {
            call.respond(HttpStatusCode.OK, "pong")
        }
        app.router.get("/upgrade") {
            call.respond(HttpStatusCode.OK, "upgraded")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        var connection: Channel? = null
        var firstEventStream: Http2SseClientStream? = null
        var secondEventStream: Http2SseClientStream? = null
        try {
            connection = connectHttp2(clientGroup)
            firstEventStream = openHttp2SseStream(connection, "/events")
            secondEventStream = openHttp2SseStream(connection, "/events")
            assertTrue(firstEventStream.firstEvent.get(3, TimeUnit.SECONDS).contains("data: connected"))
            assertTrue(secondEventStream.firstEvent.get(3, TimeUnit.SECONDS).contains("data: connected"))

            // Wait through two complete parent idle periods. SSE owns its own keep-alive policy,
            // so neither its stream nor unrelated multiplexed streams may be closed here.
            Thread.sleep(2_200)

            assertTrue(connection.isActive, "The HTTP/2 parent connection closed while SSE was active")
            assertTrue(firstEventStream.channel.isActive, "The first idle SSE child stream was closed")
            assertTrue(secondEventStream.channel.isActive, "The second idle SSE child stream was closed")
            assertFalse(firstEventStream.closed.isDone, "The server closed the first SSE stream")
            assertFalse(secondEventStream.closed.isDone, "The server closed the second SSE stream")
            assertEquals("pong", sendHttp2Request(connection, "/ping").get(3, TimeUnit.SECONDS))

            firstEventStream.channel.close().sync()
            firstEventStream = null
            Thread.sleep(1_200)
            assertTrue(connection.isActive, "Closing one SSE stream disabled the remaining stream's exemption")
            assertTrue(secondEventStream.channel.isActive)
            assertEquals("pong", sendHttp2Request(connection, "/ping").get(3, TimeUnit.SECONDS))

            secondEventStream.channel.close().sync()
            secondEventStream = null
            assertTrue(
                connection.closeFuture().await(3, TimeUnit.SECONDS),
                "The parent idle timeout remained disabled after all SSE streams closed",
            )
            assertFalse(connection.isActive)

            val goAwayReceived = CompletableFuture<Unit>()
            connection = connectHttp2Upgrade(clientGroup, "/upgrade", goAwayReceived)
            firstEventStream = openHttp2SseStream(connection, "/events")
            assertTrue(firstEventStream.firstEvent.get(3, TimeUnit.SECONDS).contains("data: connected"))
            Thread.sleep(2_200)
            assertTrue(connection.isActive, "The h2c parent connection closed while SSE was active")
            assertTrue(firstEventStream.channel.isActive, "The h2c SSE child stream was closed")
            assertEquals("pong", sendHttp2Request(connection, "/ping").get(3, TimeUnit.SECONDS))

            // The SSE exemption applies only to the connection idle event. It must not interfere
            // with Netty's normal graceful-shutdown path or make the parent uncloseable.
            val stopThread = Thread(engine::stopWithoutHalting).also(Thread::start)
            goAwayReceived.get(3, TimeUnit.SECONDS)
            assertTrue(
                connection.closeFuture().await(15, TimeUnit.SECONDS),
                "An active SSE stream prevented graceful shutdown from closing its HTTP/2 parent",
            )
            stopThread.join(15_000)
            assertFalse(stopThread.isAlive, "Server shutdown did not finish with an active SSE stream")
        } finally {
            firstEventStream?.channel?.close()?.sync()
            secondEventStream?.channel?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `RFC8441 WebSocket exchanges frames and closes only its HTTP2 stream`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val handlerCompleted = CountDownLatch(1)
        app.router.get("/after-websocket") {
            call.respond(HttpStatusCode.OK, "connection-alive")
        }
        app.routing {
            webSocket("/graphqlws", "graphql-transport-ws") {
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is WebSocketFrame.Text -> {
                                frameConsumed()
                                send("echo:${frame.text}")
                            }
                            is WebSocketFrame.Close -> break
                            else -> frameConsumed()
                        }
                    }
                } finally {
                    handlerCompleted.countDown()
                }
            }
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val settingsReceived = CompletableFuture<Boolean>()
        val accepted = CompletableFuture<Pair<String, String?>>()
        val echoed = CompletableFuture<String>()
        val closeCode = CompletableFuture<Int>()
        val serverEndStream = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup, settingsReceived = settingsReceived)
            assertTrue(
                settingsReceived.get(3, TimeUnit.SECONDS),
                "Server must advertise SETTINGS_ENABLE_CONNECT_PROTOCOL",
            )

            stream = Http2StreamChannelBootstrap(connection)
                .handler(http2WebSocketClientHandler(accepted, echoed, closeCode, serverEndStream))
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.CONNECT.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/graphqlws")
                .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
                .set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-transport-ws")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()

            assertEquals(
                "200" to "graphql-transport-ws",
                accepted.get(3, TimeUnit.SECONDS),
            )

            stream.writeAndFlush(DefaultHttp2DataFrame(encodeClientFrame(TextWebSocketFrame("hello")), false)).sync()
            assertEquals("echo:hello", echoed.get(3, TimeUnit.SECONDS))

            stream.writeAndFlush(
                DefaultHttp2DataFrame(encodeClientFrame(NettyCloseWebSocketFrame(1000, "done")), false),
            ).sync()
            assertEquals(1000, closeCode.get(3, TimeUnit.SECONDS))
            serverEndStream.get(3, TimeUnit.SECONDS)
            assertTrue(handlerCompleted.await(3, TimeUnit.SECONDS), "WebSocket handler should observe the close frame")

            assertEquals(
                "connection-alive",
                sendHttp2Request(connection, "/after-websocket").get(3, TimeUnit.SECONDS),
                "Closing the RFC 8441 stream must not close the parent HTTP/2 connection",
            )
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `idle RFC8441 WebSocket closes its stream with a graceful close frame`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.routing {
            webSocket("/idle-websocket") {
                awaitCancellation()
            }
        }
        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val accepted = CompletableFuture<Pair<String, String?>>()
        val echoed = CompletableFuture<String>()
        val closeCode = CompletableFuture<Int>()
        val serverEndStream = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup)
            stream = Http2StreamChannelBootstrap(connection)
                .handler(http2WebSocketClientHandler(accepted, echoed, closeCode, serverEndStream))
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.CONNECT.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/idle-websocket")
                .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()

            assertEquals("200" to null, accepted.get(3, TimeUnit.SECONDS))
            assertEquals(
                1001,
                closeCode.get(3, TimeUnit.SECONDS),
                "The idle stream must receive a WebSocket Going Away close frame",
            )
            serverEndStream.get(3, TimeUnit.SECONDS)
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `an RFC8441 WebSocket whose client spoke last idles out with 1001 and keeps its connection`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.router.get("/after-idle-websocket") {
            call.respond(HttpStatusCode.OK, "connection-alive")
        }
        app.routing {
            webSocket("/quiet-websocket") {
                for (frame in incoming) frameConsumed()
            }
        }
        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val accepted = CompletableFuture<Pair<String, String?>>()
        val echoed = CompletableFuture<String>()
        val closeCode = CompletableFuture<Int>()
        val serverEndStream = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup)
            stream = Http2StreamChannelBootstrap(connection)
                .handler(http2WebSocketClientHandler(accepted, echoed, closeCode, serverEndStream))
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.CONNECT.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/quiet-websocket")
                .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()
            assertEquals("200" to null, accepted.get(3, TimeUnit.SECONDS))

            // The client speaks last, so the connection's idle timer starts no later than the stream's.
            stream.writeAndFlush(DefaultHttp2DataFrame(encodeClientFrame(TextWebSocketFrame("last word")), false)).sync()

            assertEquals(1001, closeCode.get(5, TimeUnit.SECONDS), "The stream's own idle close, not a dropped connection")
            serverEndStream.get(3, TimeUnit.SECONDS)
            assertEquals(
                "connection-alive",
                sendHttp2Request(connection, "/after-idle-websocket").get(3, TimeUnit.SECONDS),
                "The idle WebSocket must not take its HTTP/2 connection down with it",
            )
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `invalid UTF-8 on an RFC8441 WebSocket closes its stream with 1007`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val routeReason = CompletableFuture<CloseReason?>()
        app.routing {
            webSocket("/utf8-websocket") {
                try {
                    for (frame in incoming) frameConsumed()
                } finally {
                    routeReason.complete(closeReason.await())
                }
            }
        }
        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val accepted = CompletableFuture<Pair<String, String?>>()
        val echoed = CompletableFuture<String>()
        val closeCode = CompletableFuture<Int>()
        val serverEndStream = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup)
            stream = Http2StreamChannelBootstrap(connection)
                .handler(http2WebSocketClientHandler(accepted, echoed, closeCode, serverEndStream))
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.CONNECT.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/utf8-websocket")
                .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()
            assertEquals("200" to null, accepted.get(3, TimeUnit.SECONDS))

            // 0xC3 0x28 is an invalid two-byte UTF-8 sequence.
            val invalid = TextWebSocketFrame(Unpooled.wrappedBuffer(byteArrayOf(0xC3.toByte(), 0x28)))
            stream.writeAndFlush(DefaultHttp2DataFrame(encodeClientFrame(invalid), false)).sync()

            assertEquals(1007, closeCode.get(3, TimeUnit.SECONDS))
            serverEndStream.get(3, TimeUnit.SECONDS)
            assertEquals(1007, routeReason.get(3, TimeUnit.SECONDS)?.code, "The route must see why the peer was closed")
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `RFC8441 WebSocket handler failure closes with internal error`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        app.routing {
            webSocket("/failing-websocket") {
                error("expected WebSocket handler failure")
            }
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val settingsReceived = CompletableFuture<Boolean>()
        val accepted = CompletableFuture<Pair<String, String?>>()
        val echoed = CompletableFuture<String>()
        val closeCode = CompletableFuture<Int>()
        val serverEndStream = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup, settingsReceived = settingsReceived)
            assertTrue(settingsReceived.get(3, TimeUnit.SECONDS))

            stream = Http2StreamChannelBootstrap(connection)
                .handler(http2WebSocketClientHandler(accepted, echoed, closeCode, serverEndStream))
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.CONNECT.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/failing-websocket")
                .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()

            assertEquals("200" to null, accepted.get(3, TimeUnit.SECONDS))
            assertEquals(1011, closeCode.get(3, TimeUnit.SECONDS))
            serverEndStream.get(3, TimeUnit.SECONDS)
            assertTrue(connection.isActive, "A failed WebSocket stream must not close its HTTP/2 connection")
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `timed out RFC8441 middleware closes only its pending stream`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val middlewareStarted = CountDownLatch(1)
        val handlerInvoked = AtomicBoolean(false)
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                if (call.request.uri != "/timed-out") return
                middlewareStarted.countDown()
                withTimeout(250) { awaitCancellation() }
            }
        })
        app.routing {
            webSocket("/timed-out") {
                handlerInvoked.set(true)
            }
        }
        app.router.get("/after-timeout") {
            call.respond(HttpStatusCode.OK, "connection-alive")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val settingsReceived = CompletableFuture<Boolean>()
        var connection: Channel? = null
        var stream: Channel? = null
        try {
            connection = connectHttp2(clientGroup, settingsReceived = settingsReceived)
            assertTrue(settingsReceived.get(3, TimeUnit.SECONDS))
            stream = Http2StreamChannelBootstrap(connection)
                .handler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                ReferenceCountUtil.release(msg)
                            }
                        })
                    }
                })
                .open()
                .sync()
                .getNow()

            val headers = DefaultHttp2Headers()
                .method(HttpMethod.CONNECT.asciiName())
                .scheme("http")
                .authority("localhost")
                .path("/timed-out")
                .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
                .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
            val streamClosed = CompletableFuture<Unit>()
            stream.closeFuture().addListener { streamClosed.complete(Unit) }
            stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()

            assertTrue(middlewareStarted.await(3, TimeUnit.SECONDS))
            streamClosed.get(3, TimeUnit.SECONDS)
            assertFalse(handlerInvoked.get(), "Timed-out middleware must not enter the WebSocket handler")
            assertTrue(connection.isActive, "A timed-out RFC 8441 stream must not close its parent connection")
            assertEquals(
                "connection-alive",
                sendHttp2Request(connection, "/after-timeout").get(3, TimeUnit.SECONDS),
            )
        } finally {
            stream?.close()?.sync()
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `invalid numeric server configuration falls back to defaults and stop is idempotent`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: invalid
                codec-threads: invalid
                max-request-size: invalid
                idle-timeout-seconds: invalid
                drain-timeout-ms: invalid
                shutdown-timeout-ms: invalid
            """.trimIndent()
        )
        val engine = NettyServerEngine(app, 0)
        engine.stopWithoutHalting()
        engine.stopWithoutHalting()
    }

    @Test
    fun `companion creates initialized engines from classpath and file configuration`() {
        assertEquals(8080, NettyServerSettings.configuredPort(ApplicationConfig.load("".byteInputStream())))
        assertEquals(
            8080,
            NettyServerSettings.configuredPort(ApplicationConfig.load("bosca: { server: { port: invalid } }".byteInputStream())),
        )
        var classpathModuleRuns = 0
        val classpathEngine = NettyServerEngine.create("netty-server-engine-test.yaml") {
            classpathModuleRuns++
        }
        try {
            assertEquals(1, classpathModuleRuns)
            assertEquals("0", classpathEngine.application.config.property("bosca.server.port").getString())
        } finally {
            classpathEngine.stopWithoutHalting()
        }

        val file = Files.createTempFile("bosca-netty-engine", ".yaml")
        Files.writeString(
            file,
            """
            bosca:
              server:
                port: 0
                worker-threads: 1
                codec-threads: 1
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
            """.trimIndent(),
        )
        var fileModuleRuns = 0
        val fileEngine = NettyServerEngine.create(
            args = arrayOf("ignored", "-config=$file"),
        ) {
            fileModuleRuns++
        }
        try {
            assertEquals(1, fileModuleRuns)
            assertEquals("0", fileEngine.application.config.property("bosca.server.port").getString())
        } finally {
            fileEngine.stopWithoutHalting()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `WebSocket upgrade returns 101 response over real TCP`() {
        val app = createApplication()
        val handlerInvoked = CountDownLatch(1)
        val handshakeLog = java.util.concurrent.CopyOnWriteArrayList<String>()

        app.routing {
            webSocket("/ws") {
                handshakeLog.add("handler-invoked")
                handlerInvoked.countDown()
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Close) break
                }
            }
        }

        val engine = startServer(app)
        try {
            val upgradeRequest = buildString {
                append("GET /ws HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }

            val response = sendHttpRequest(upgradeRequest, timeoutMs = 5000)
            val handlerWasInvoked = handlerInvoked.await(3, TimeUnit.SECONDS)
            println("WebSocket test: response=[${response.take(500)}], handlerInvoked=$handlerWasInvoked, log=$handshakeLog")
            assertTrue(response.contains("101"), "Response should contain 101 Switching Protocols, got: [$response]")
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP1 WebSocket handler return sends normal close frame`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        app.routing {
            webSocket("/returning-websocket") {}
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write(webSocketUpgradeRequest("/returning-websocket").encodeToByteArray())
                    flush()
                }

                val input = socket.getInputStream()
                val responseHeaders = readHttpHeaders(input)
                assertTrue(responseHeaders.startsWith("HTTP/1.1 101"), responseHeaders)
                assertEquals(1000, readServerWebSocketCloseCode(input))
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP1 WebSocket handler failure closes with internal error`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        app.routing {
            webSocket("/failing-websocket") {
                error("expected WebSocket handler failure")
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().run {
                    write(webSocketUpgradeRequest("/failing-websocket").encodeToByteArray())
                    flush()
                }

                val input = socket.getInputStream()
                val responseHeaders = readHttpHeaders(input)
                assertTrue(responseHeaders.startsWith("HTTP/1.1 101"), responseHeaders)
                assertEquals(1011, readServerWebSocketCloseCode(input))
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `authenticated HTTP1 WebSocket rejection is sent before switching protocols`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        val handlerInvoked = AtomicBoolean(false)
        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                call.respond(HttpStatusCode.Unauthorized, "denied")
            }
        })
        app.routing {
            authenticate("bearer") {
                webSocket("/secure") {
                    handlerInvoked.set(true)
                }
            }
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(webSocketUpgradeRequest("/secure"), timeoutMs = 5000)

            assertTrue(
                response.startsWith("HTTP/1.1 401"),
                "Authentication rejection must remain an HTTP 401 response, got: [$response]",
            )
            assertFalse(response.contains("101 Switching Protocols"), response)
            assertFalse(handlerInvoked.get())
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `failed SSE before-write callback returns an ordinary HTTP 500 response`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                call.response.onBeforeWrite { error("expected before-write failure") }
            }
        })
        app.routing {
            sse("/before-write-fails") {
                send("unreachable")
            }
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(
                "GET /before-write-fails HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: text/event-stream\r\n" +
                    "Connection: close\r\n\r\n",
                timeoutMs = 5000,
            )

            assertTrue(response.startsWith("HTTP/1.1 500"), response)
            assertTrue(response.contains("Internal Server Error"), response)
            assertFalse(
                response.contains("Transfer-Encoding:", ignoreCase = true),
                "The fallback response must not retain SSE chunked framing: [$response]",
            )
            assertFalse(
                response.contains("text/event-stream", ignoreCase = true),
                "The fallback response must not retain the SSE content type: [$response]",
            )
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `failed HTTP1 WebSocket before-write callback returns HTTP 500`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        val handlerInvoked = AtomicBoolean(false)
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                call.response.onBeforeWrite { error("expected before-write failure") }
            }
        })
        app.routing {
            webSocket("/before-write-fails") {
                handlerInvoked.set(true)
            }
        }

        val engine = startServer(app)
        try {
            val response = sendHttpRequest(webSocketUpgradeRequest("/before-write-fails"), timeoutMs = 5000)

            assertTrue(
                response.startsWith("HTTP/1.1 500"),
                "A failed handshake callback must produce HTTP 500, got: [$response]",
            )
            assertFalse(response.contains("101 Switching Protocols"), response)
            assertFalse(handlerInvoked.get())
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `fragmented HTTP1 WebSocket text is delivered as one complete message`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        val received = CompletableFuture<String>()
        app.routing {
            webSocket("/fragmented") {
                for (frame in incoming) {
                    if (frame is WebSocketFrame.Text) {
                        frameConsumed()
                        received.complete(frame.text)
                        break
                    }
                    frameConsumed()
                }
            }
        }

        val engine = startServer(app)
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.write(webSocketUpgradeRequest("/fragmented"))
                writer.flush()

                val responseHeaders = readHttpHeaders(reader)
                assertTrue(responseHeaders.startsWith("HTTP/1.1 101"), responseHeaders)

                writeWebSocketFrames(
                    socket,
                    TextWebSocketFrame(false, 0, "hel"),
                    ContinuationWebSocketFrame(true, 0, Unpooled.wrappedBuffer("lo".encodeToByteArray())),
                )

                assertEquals("hello", received.get(3, TimeUnit.SECONDS))
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP1 keep-alive stops admitting requests when shutdown begins`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 1500
                shutdown-timeout-ms: 10000
                compression-enabled: false
                http2-enabled: false
            """.trimIndent(),
        )
        val firstStarted = CountDownLatch(1)
        val lateStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        app.router.get("/in-flight") {
            firstStarted.countDown()
            releaseFirst.await()
            call.respond(HttpStatusCode.OK, "first")
        }
        app.router.get("/late") {
            lateStarted.countDown()
            call.respond(HttpStatusCode.OK, "late")
        }

        val engine = startServer(app)
        var stopThread: Thread? = null
        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.write("GET /in-flight HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
                writer.flush()
                assertTrue(firstStarted.await(3, TimeUnit.SECONDS))

                stopThread = Thread(engine::stopWithoutHalting).also(Thread::start)
                Thread.sleep(250)
                writer.write("GET /late HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
                writer.flush()
                releaseFirst.complete(Unit)

                val firstResponse = readHttpResponse(reader)
                assertTrue(firstResponse.endsWith("first"), firstResponse)
                assertTrue(
                    firstResponse.contains("Connection: close", ignoreCase = true),
                    "The in-flight response must tell the client the connection is draining: [$firstResponse]",
                )
                assertFalse(
                    lateStarted.await(500, TimeUnit.MILLISECONDS),
                    "A request received after draining starts must not reach its route",
                )
            }
        } finally {
            releaseFirst.complete(Unit)
            stopThread?.join(15_000)
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `HTTP2 sends GOAWAY and rejects new streams when shutdown begins`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 3000
                shutdown-timeout-ms: 10000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val firstStarted = CountDownLatch(1)
        val lateStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        app.router.get("/in-flight") {
            firstStarted.countDown()
            releaseFirst.await()
            call.respond(HttpStatusCode.OK, "first")
        }
        app.router.get("/late") {
            lateStarted.countDown()
            call.respond(HttpStatusCode.OK, "late")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val goAwayReceived = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stopThread: Thread? = null
        try {
            connection = connectHttp2(clientGroup, goAwayReceived = goAwayReceived)
            val firstResponse = sendHttp2Request(connection, "/in-flight")
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))

            stopThread = Thread(engine::stopWithoutHalting).also(Thread::start)
            goAwayReceived.get(2500, TimeUnit.MILLISECONDS)

            val lateResponse = runCatching {
                sendHttp2Request(connection, "/late").get(1, TimeUnit.SECONDS)
            }
            assertTrue(lateResponse.isFailure, "A new stream opened after GOAWAY must fail")
            assertFalse(lateStarted.await(500, TimeUnit.MILLISECONDS))

            releaseFirst.complete(Unit)
            assertEquals("first", firstResponse.get(3, TimeUnit.SECONDS))
        } finally {
            releaseFirst.complete(Unit)
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            stopThread?.join(15_000)
            engine.stopWithoutHalting()
        }
    }

    @Test
    fun `h2c upgraded connection receives GOAWAY when shutdown begins`() {
        val app = createApplication(
            """
            bosca:
              server:
                worker-threads: 1
                codec-threads: 1
                max-request-size: 1048576
                idle-timeout-seconds: 30
                drain-timeout-ms: 3000
                shutdown-timeout-ms: 10000
                compression-enabled: false
                http2-enabled: true
            """.trimIndent(),
        )
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        app.router.get("/in-flight-h2c") {
            firstStarted.countDown()
            releaseFirst.await()
            call.respond(HttpStatusCode.OK, "first")
        }

        val engine = startServer(app)
        val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        val goAwayReceived = CompletableFuture<Unit>()
        var connection: Channel? = null
        var stopThread: Thread? = null
        try {
            connection = connectHttp2Upgrade(clientGroup, "/in-flight-h2c", goAwayReceived)
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))

            stopThread = Thread(engine::stopWithoutHalting).also(Thread::start)
            goAwayReceived.get(2500, TimeUnit.MILLISECONDS)
        } finally {
            releaseFirst.complete(Unit)
            connection?.close()?.sync()
            clientGroup.shutdownGracefully().sync()
            stopThread?.join(15_000)
            engine.stopWithoutHalting()
        }
    }

    private fun sendHttpRequestTo(port: Int, request: String, timeoutMs: Int): String =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val buf = ByteArray(4096)
            try {
                val n = socket.getInputStream().read(buf)
                if (n > 0) String(buf, 0, n) else ""
            } catch (_: java.net.SocketTimeoutException) {
                ""
            }
        }

    private fun sendHttpRequest(request: String, timeoutMs: Int = 3000): String {
        val socket = Socket("localhost", testPort)
        socket.soTimeout = timeoutMs
        try {
            val writer = OutputStreamWriter(socket.getOutputStream())
            writer.write(request)
            writer.flush()

            val buf = ByteArray(4096)
            val response = StringBuilder()
            try {
                val n = socket.getInputStream().read(buf)
                if (n > 0) response.append(String(buf, 0, n))
            } catch (_: java.net.SocketTimeoutException) {
                // Timeout means no response
            }
            return response.toString()
        } finally {
            socket.close()
        }
    }

    private fun webSocketUpgradeRequest(path: String): String = buildString {
        append("GET $path HTTP/1.1\r\n")
        append("Host: localhost\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("\r\n")
    }

    private fun readHttpHeaders(reader: BufferedReader): String {
        val response = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: error("Connection closed before response headers completed")
            response.append(line).append("\r\n")
            if (line.isEmpty()) return response.toString()
        }
    }

    private fun readHttpHeaders(input: InputStream): String {
        val response = StringBuilder()
        var trailing = 0
        while (trailing != 0x0d0a0d0a) {
            val byte = input.read()
            check(byte >= 0) { "Connection closed before response headers completed" }
            response.append(byte.toChar())
            trailing = (trailing shl 8) or byte
        }
        return response.toString()
    }

    private fun readServerWebSocketCloseCode(input: InputStream): Int {
        val first = input.read()
        check(first >= 0) { "Connection closed before the server sent a WebSocket close frame" }
        check(first and 0x8f == 0x88) { "Expected a final WebSocket close frame, first byte was 0x${first.toString(16)}" }

        val second = input.read()
        check(second >= 0) { "Connection closed before the WebSocket close frame length" }
        check(second and 0x80 == 0) { "Server WebSocket frames must not be masked" }
        val length = when (val encodedLength = second and 0x7f) {
            in 0..125 -> encodedLength
            126 -> (input.read() shl 8) or input.read()
            else -> error("Unexpected 64-bit close frame payload length")
        }
        check(length >= 2) { "WebSocket close frame did not contain a status code" }
        val payload = input.readNBytes(length)
        check(payload.size == length) { "Connection closed before the WebSocket close frame completed" }
        return ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
    }

    private fun writeWebSocketFrames(socket: Socket, vararg frames: io.netty.handler.codec.http.websocketx.WebSocketFrame) {
        val encoded = frames.map(::encodeClientFrame)
        try {
            val output = socket.getOutputStream()
            encoded.forEach { buffer ->
                val bytes = ByteArray(buffer.readableBytes())
                buffer.readBytes(bytes)
                output.write(bytes)
            }
            output.flush()
        } finally {
            encoded.forEach(ReferenceCountUtil::safeRelease)
        }
    }

    private data class Http2SseClientStream(
        val channel: Channel,
        val firstEvent: CompletableFuture<String>,
        val closed: CompletableFuture<Unit>,
    )

    private fun openHttp2SseStream(connection: Channel, path: String): Http2SseClientStream {
        val firstEvent = CompletableFuture<String>()
        val closed = CompletableFuture<Unit>()
        val stream = Http2StreamChannelBootstrap(connection)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    val received = StringBuilder()
                    ch.closeFuture().addListener {
                        closed.complete(Unit)
                        firstEvent.completeExceptionally(
                            IllegalStateException("SSE stream closed before its first event"),
                        )
                    }
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            try {
                                if (msg is Http2DataFrame) {
                                    received.append(msg.content().toString(Charsets.UTF_8))
                                    if (received.contains("data: connected")) {
                                        firstEvent.complete(received.toString())
                                    }
                                }
                            } finally {
                                ReferenceCountUtil.release(msg)
                            }
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            firstEvent.completeExceptionally(cause)
                            ctx.close()
                        }
                    })
                }
            })
            .open()
            .sync()
            .getNow()

        val headers = DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName())
            .scheme("http")
            .authority("localhost")
            .path(path)
            .set(HttpHeaderNames.ACCEPT, "text/event-stream")
        stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, true)).sync()
        return Http2SseClientStream(stream, firstEvent, closed)
    }

    private fun sendHttp2Request(
        connection: Channel,
        path: String,
        method: HttpMethod = HttpMethod.GET,
        bodyWithoutContentLength: String? = null,
    ): CompletableFuture<String> {
        val response = CompletableFuture<String>()
        val stream = Http2StreamChannelBootstrap(connection)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(false))
                    ch.pipeline().addLast(HttpObjectAggregator(1_048_576))
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            try {
                                if (msg is FullHttpResponse) {
                                    response.complete(msg.content().toString(Charsets.UTF_8))
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

        val write = if (bodyWithoutContentLength == null) {
            val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path)
            request.headers().set(HttpHeaderNames.HOST, "localhost")
            stream.writeAndFlush(request)
        } else {
            val headers = DefaultHttp2Headers()
                .method(method.asciiName())
                .scheme("http")
                .authority("localhost")
                .path(path)
            stream.write(DefaultHttp2HeadersFrame(headers, false))
            stream.writeAndFlush(
                DefaultHttp2DataFrame(Unpooled.copiedBuffer(bodyWithoutContentLength, Charsets.UTF_8), true),
            )
        }
        write.addListener { future ->
            if (!future.isSuccess) response.completeExceptionally(future.cause())
        }
        return response
    }

    private fun connectHttp2(
        clientGroup: MultiThreadIoEventLoopGroup,
        inboundStreamInitializer: ChannelInitializer<Channel> = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) = Unit
        },
        settingsReceived: CompletableFuture<Boolean>? = null,
        goAwayReceived: CompletableFuture<Unit>? = null,
        resetReceived: CompletableFuture<Long>? = null,
    ): Channel = Bootstrap()
        .group(clientGroup)
        .channel(NioSocketChannel::class.java)
        .handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build())
                if (goAwayReceived != null) {
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is Http2GoAwayFrame) {
                                goAwayReceived.complete(Unit)
                            }
                            ctx.fireChannelRead(msg)
                        }
                    })
                }
                if (settingsReceived != null) {
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is Http2SettingsFrame) {
                                settingsReceived.complete(msg.settings().connectProtocolEnabled() == true)
                            }
                            ctx.fireChannelRead(msg)
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            settingsReceived.completeExceptionally(cause)
                            ctx.fireExceptionCaught(cause)
                        }
                    })
                }
                if (resetReceived != null) {
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is Http2ResetFrame) {
                                resetReceived.complete(msg.errorCode())
                            }
                            ctx.fireChannelRead(msg)
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            resetReceived.completeExceptionally(cause)
                            ctx.fireExceptionCaught(cause)
                        }
                    })
                }
                ch.pipeline().addLast(Http2MultiplexHandler(inboundStreamInitializer))
            }
        })
        .connect("localhost", testPort)
        .sync()
        .channel()

    private fun connectHttp2Upgrade(
        clientGroup: MultiThreadIoEventLoopGroup,
        path: String,
        goAwayReceived: CompletableFuture<Unit>,
    ): Channel {
        val upgraded = CompletableFuture<Unit>()
        val channel = Bootstrap()
            .group(clientGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    val httpCodec = HttpClientCodec()
                    val frameCodec = Http2FrameCodecBuilder.forClient().build()
                    val multiplex = Http2MultiplexHandler(
                        object : ChannelInitializer<Channel>() {
                            override fun initChannel(ch: Channel) = Unit
                        },
                        object : ChannelInitializer<Channel>() {
                            override fun initChannel(ch: Channel) = Unit
                        },
                    )
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

                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is Http2GoAwayFrame) {
                                goAwayReceived.complete(Unit)
                            }
                            ctx.fireChannelRead(msg)
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            upgraded.completeExceptionally(cause)
                            goAwayReceived.completeExceptionally(cause)
                            ctx.close()
                        }
                    })
                }
            })
            .connect("localhost", testPort)
            .sync()
            .channel()
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)
        request.headers().set(HttpHeaderNames.HOST, "localhost")
        channel.writeAndFlush(request).sync()
        upgraded.get(5, TimeUnit.SECONDS)
        return channel
    }

    private fun http2WebSocketClientHandler(
        accepted: CompletableFuture<Pair<String, String?>>,
        echoed: CompletableFuture<String>,
        closeCode: CompletableFuture<Int>,
        serverEndStream: CompletableFuture<Unit>,
    ): ChannelInitializer<Channel> = object : ChannelInitializer<Channel>() {
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
                            is Http2HeadersFrame -> accepted.complete(
                                msg.headers().status().toString() to
                                    msg.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)?.toString(),
                            )
                            is Http2DataFrame -> {
                                if (msg.content().isReadable) {
                                    decoder.writeInbound(msg.content().retain())
                                    while (true) {
                                        val frame = decoder.readInbound<Any>() ?: break
                                        try {
                                            when (frame) {
                                                is TextWebSocketFrame -> echoed.complete(frame.text())
                                                is NettyCloseWebSocketFrame -> {
                                                    closeCode.complete(frame.statusCode())
                                                    ctx.writeAndFlush(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
                                                }
                                            }
                                        } finally {
                                            ReferenceCountUtil.release(frame)
                                        }
                                    }
                                }
                                if (msg.isEndStream) serverEndStream.complete(Unit)
                            }
                        }
                    } catch (cause: Throwable) {
                        completeWebSocketClientFuturesExceptionally(
                            cause,
                            accepted,
                            echoed,
                            closeCode,
                            serverEndStream,
                        )
                    } finally {
                        ReferenceCountUtil.release(msg)
                    }
                }

                override fun channelInactive(ctx: ChannelHandlerContext) {
                    decoder.finishAndReleaseAll()
                    ctx.fireChannelInactive()
                }

                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    completeWebSocketClientFuturesExceptionally(
                        cause,
                        accepted,
                        echoed,
                        closeCode,
                        serverEndStream,
                    )
                    ctx.close()
                }
            })
        }
    }

    private fun encodeClientFrame(frame: io.netty.handler.codec.http.websocketx.WebSocketFrame): ByteBuf {
        val encoder = EmbeddedChannel(WebSocket13FrameEncoder(true))
        return try {
            check(encoder.writeOutbound(frame)) { "WebSocket encoder produced no output" }
            encoder.readOutbound()
        } finally {
            encoder.finishAndReleaseAll()
        }
    }

    private fun completeWebSocketClientFuturesExceptionally(
        cause: Throwable,
        accepted: CompletableFuture<*>,
        echoed: CompletableFuture<*>,
        closeCode: CompletableFuture<*>,
        serverEndStream: CompletableFuture<*>,
    ) {
        listOf(accepted, echoed, closeCode, serverEndStream).forEach { it.completeExceptionally(cause) }
    }

    private fun readHttpResponse(reader: BufferedReader): String {
        val response = StringBuilder()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: error("Connection closed before response headers completed")
            response.append(line).append("\r\n")
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
            if (line.isEmpty()) break
        }

        val body = CharArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = reader.read(body, offset, body.size - offset)
            check(count >= 0) { "Connection closed before response body completed" }
            offset += count
        }
        return response.append(body).toString()
    }
}
