package bosca.server.netty

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.config.ApplicationConfig
import bosca.server.websocket.WebSocketFrame
import io.netty.buffer.Unpooled
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.util.ReferenceCountUtil
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object NettyHttpBenchmarkRequests {
    val plaintext = request("GET", "/plaintext")
    val preEncodedPlaintext = request("GET", "/plaintext-preencoded")
    val pathParameter = request("GET", "/users/123456?include=summary")
    val compressed = request("GET", "/compressed", "Accept-Encoding: gzip\r\n")
    val compressedStreaming = request("GET", "/compressed-streaming", "Accept-Encoding: gzip\r\n")
    val incompressible = request("GET", "/incompressible", "Accept-Encoding: gzip\r\n")
    val blocking = request("GET", "/blocking")
    val postBody: ByteArray = ByteArray(1024) { ('a'.code + it % 26).toByte() }
    val post1KiB: ByteArray = buildPostRequest("/echo", postBody)

    private fun request(method: String, path: String, extraHeaders: String = ""): ByteArray =
        "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n$extraHeaders\r\n".toByteArray(StandardCharsets.US_ASCII)

    private fun buildPostRequest(path: String, body: ByteArray): ByteArray {
        val headers =
            "POST $path HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
        return headers.toByteArray(StandardCharsets.US_ASCII) + body
    }
}

/** How long the `/blocking` benchmark route holds its request thread. */
internal const val BLOCKING_ROUTE_MILLIS = 10L

internal object NettyHttp2BenchmarkRequests {
    val plaintext = NettyHttp2BenchmarkRequest(HttpMethod.GET, "/plaintext")
}

internal data class NettyHttp2BenchmarkRequest(
    val method: HttpMethod,
    val path: String,
    val body: ByteArray? = null,
    val acceptGzip: Boolean = false,
)

internal class NettyHttpBenchmarkServer(private val requestedPort: Int = 0) {
    private lateinit var engine: NettyServerEngine
    private lateinit var thread: Thread

    val port: Int
        get() = engine.boundPort ?: error("benchmark server has not bound a port")

    fun start() {
        val availableProcessorCount = Runtime.getRuntime().availableProcessors()
        // Keep these fallbacks aligned with NettyServerEngine's internal default sizing helpers.
        val defaultWorkerThreadCount = minOf(4, availableProcessorCount.coerceAtLeast(1))
        val defaultCodecThreadCount = maxOf(4, availableProcessorCount)
        val config = ApplicationConfig.load(
            """
            bosca:
              server:
                worker-threads: ${'$'}BOSCA_BENCHMARK_WORKER_THREADS:$defaultWorkerThreadCount
                codec-threads: ${'$'}BOSCA_BENCHMARK_CODEC_THREADS:$defaultCodecThreadCount
                request-dispatcher: ${'$'}BOSCA_BENCHMARK_REQUEST_DISPATCHER:event-loop
                drain-timeout-ms: 0
                shutdown-timeout-ms: 10000
            """.trimIndent().byteInputStream(),
        )
        val application = BoscaApplication(config)
        val plaintextPayload = "Hello, World!".toByteArray(StandardCharsets.UTF_8)
        val compressedPayload = "{\"payload\":\"${"0123456789abcdef".repeat(2048)}\"}"
        val compressedPayloadBytes = compressedPayload.toByteArray(StandardCharsets.UTF_8)
        val incompressiblePayload = ByteArray(32 * 1024).also { bytes ->
            var state = 0x6d2b79f5
            for (index in bytes.indices) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                bytes[index] = state.toByte()
            }
        }
        application.routing {
            get("/plaintext") {
                call.respond(HttpStatusCode.OK, "Hello, World!")
            }
            // Deliberately blocks its request thread for 10 ms, standing in for an unwrapped
            // blocking call (synchronous HTTP client, JGit, file I/O) to measure dispatcher isolation.
            get("/blocking") {
                @Suppress("BlockingMethodInNonBlockingContext")
                Thread.sleep(BLOCKING_ROUTE_MILLIS)
                call.respond(HttpStatusCode.OK, "blocked")
            }
            get("/plaintext-preencoded") {
                call.response.header("Content-Type", "text/plain")
                call.response.commit(Unpooled.wrappedBuffer(plaintextPayload))
            }
            get("/users/{id}") {
                call.respond(HttpStatusCode.OK, call.pathParameters["id"] ?: error("missing id"))
            }
            post("/echo") {
                call.response.respondBytes(call.request.bodyBytes(), ContentType.Application.OctetStream)
            }
            get("/compressed") {
                call.response.respondText(compressedPayload, ContentType.Application.Json)
            }
            get("/compressed-streaming") {
                call.response.respondStreaming(ContentType.Application.Json) { response ->
                    val chunkSize = compressedPayloadBytes.size / 4
                    repeat(4) { index ->
                        val offset = index * chunkSize
                        val length = if (index == 3) compressedPayloadBytes.size - offset else chunkSize
                        response.write(compressedPayloadBytes, offset, length)
                    }
                }
            }
            get("/incompressible") {
                call.response.respondBytes(incompressiblePayload, ContentType.Application.OctetStream)
            }
            webSocket("/websocket") {
                for (frame in incoming) {
                    when (frame) {
                        is WebSocketFrame.Text -> {
                            val text = frame.text
                            frameConsumed()
                            send(text)
                        }
                        is WebSocketFrame.Close -> break
                        else -> frameConsumed()
                    }
                }
            }
        }
        application.freezeMiddleware()

        engine = NettyServerEngine(application, requestedPort)
        thread = Thread({ engine.start() }, "netty-http-benchmark-server").apply {
            isDaemon = true
            start()
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (engine.boundPort == null && thread.isAlive && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        check(thread.isAlive) { "benchmark server stopped before binding" }
        check(engine.boundPort != null) { "benchmark server did not bind within 10 seconds" }
    }

    fun stop() {
        engine.stopWithoutHalting()
        thread.join(TimeUnit.SECONDS.toMillis(15))
        check(!thread.isAlive) { "benchmark server did not stop within 15 seconds" }
    }
}

/** Per-JMH-thread persistent HTTP/1.1 connection used by both latency and load benchmarks. */
@State(Scope.Thread)
open class NettyHttpBenchmarkClient {
    private var port = -1
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val headerBuffer = ByteArray(16 * 1024)
    private val bodyBuffer = ByteArray(64 * 1024)

    internal fun exchange(serverPort: Int, request: ByteArray): Int {
        ensureConnected(serverPort)
        val currentOutput = output ?: error("benchmark connection has no output")
        currentOutput.write(request)
        currentOutput.flush()
        return readResponse(input ?: error("benchmark connection has no input"))
    }

    @TearDown
    open fun close() {
        socket?.close()
        socket = null
        input = null
        output = null
        port = -1
    }

    private fun ensureConnected(serverPort: Int) {
        if (socket?.isClosed == false && port == serverPort) return
        close()
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 5_000)
        newSocket.soTimeout = 10_000
        socket = newSocket
        input = BufferedInputStream(newSocket.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(newSocket.getOutputStream(), 64 * 1024)
        port = serverPort
    }

    private fun readResponse(currentInput: BufferedInputStream): Int {
        var headerSize = 0
        var delimiter = 0
        while (delimiter < 4) {
            val value = currentInput.read()
            check(value >= 0) { "connection closed while reading response headers" }
            check(headerSize < headerBuffer.size) { "response headers exceed ${headerBuffer.size} bytes" }
            headerBuffer[headerSize++] = value.toByte()
            delimiter = when {
                delimiter == 0 && value == '\r'.code -> 1
                delimiter == 1 && value == '\n'.code -> 2
                delimiter == 2 && value == '\r'.code -> 3
                delimiter == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
        }

        check(headerSize >= 12 && headerBuffer[9] == '2'.code.toByte()) {
            "benchmark request failed with ${String(headerBuffer, 0, headerSize, StandardCharsets.US_ASCII)}"
        }

        val contentLength = findDecimalHeader(headerSize, CONTENT_LENGTH)
        return if (contentLength >= 0) {
            readExactly(currentInput, contentLength)
            contentLength
        } else {
            check(containsHeaderValue(headerSize, TRANSFER_ENCODING, CHUNKED)) {
                "response has neither Content-Length nor chunked framing"
            }
            readChunked(currentInput)
        }
    }

    private fun readChunked(currentInput: BufferedInputStream): Int {
        var total = 0
        while (true) {
            val chunkSize = readHexLine(currentInput)
            if (chunkSize == 0) {
                readTrailers(currentInput)
                return total
            }
            readExactly(currentInput, chunkSize)
            check(currentInput.read() == '\r'.code && currentInput.read() == '\n'.code) { "malformed chunk terminator" }
            total += chunkSize
        }
    }

    private fun readHexLine(currentInput: BufferedInputStream): Int {
        var value = 0
        var sawDigit = false
        var inExtension = false
        while (true) {
            val byte = currentInput.read()
            check(byte >= 0) { "connection closed while reading chunk size" }
            if (byte == '\r'.code) {
                check(currentInput.read() == '\n'.code) { "malformed chunk-size line" }
                check(sawDigit) { "empty chunk-size line" }
                return value
            }
            if (byte == ';'.code) {
                inExtension = true
                continue
            }
            if (inExtension) continue
            val digit = when (byte) {
                in '0'.code..'9'.code -> byte - '0'.code
                in 'a'.code..'f'.code -> byte - 'a'.code + 10
                in 'A'.code..'F'.code -> byte - 'A'.code + 10
                else -> error("invalid hexadecimal chunk size")
            }
            sawDigit = true
            value = value * 16 + digit
        }
    }

    private fun readTrailers(currentInput: BufferedInputStream) {
        var delimiter = 0
        while (delimiter < 2) {
            val value = currentInput.read()
            check(value >= 0) { "connection closed while reading chunk trailers" }
            delimiter = when {
                delimiter == 0 && value == '\r'.code -> 1
                delimiter == 1 && value == '\n'.code -> 2
                value == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun readExactly(currentInput: BufferedInputStream, byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val read = currentInput.read(bodyBuffer, 0, minOf(remaining, bodyBuffer.size))
            check(read >= 0) { "connection closed with $remaining response bytes remaining" }
            remaining -= read
        }
    }

    private fun findDecimalHeader(headerSize: Int, name: ByteArray): Int {
        val valueStart = findHeaderValueStart(headerSize, name)
        if (valueStart < 0) return -1
        var value = 0
        var index = valueStart
        while (index < headerSize) {
            val byte = headerBuffer[index].toInt() and 0xff
            if (byte !in '0'.code..'9'.code) break
            value = value * 10 + byte - '0'.code
            index++
        }
        return value
    }

    private fun containsHeaderValue(headerSize: Int, name: ByteArray, expected: ByteArray): Boolean {
        val valueStart = findHeaderValueStart(headerSize, name)
        if (valueStart < 0 || valueStart + expected.size > headerSize) return false
        return expected.indices.all { index -> asciiEqualsIgnoreCase(headerBuffer[valueStart + index], expected[index]) }
    }

    private fun findHeaderValueStart(headerSize: Int, name: ByteArray): Int {
        var index = 0
        while (index + name.size + 1 < headerSize) {
            val atLineStart = index == 0 || (
                index >= 2 &&
                    headerBuffer[index - 2] == '\r'.code.toByte() &&
                    headerBuffer[index - 1] == '\n'.code.toByte()
                )
            val nameMatches = name.indices.all { offset ->
                asciiEqualsIgnoreCase(headerBuffer[index + offset], name[offset])
            }
            if (atLineStart && nameMatches) {
                var valueStart = index + name.size
                if (headerBuffer[valueStart] != ':'.code.toByte()) {
                    index++
                    continue
                }
                valueStart++
                while (valueStart < headerSize && headerBuffer[valueStart] == ' '.code.toByte()) valueStart++
                return valueStart
            }
            index++
        }
        return -1
    }

    private fun asciiEqualsIgnoreCase(left: Byte, right: Byte): Boolean {
        fun lower(value: Byte): Int {
            val unsigned = value.toInt() and 0xff
            return if (unsigned in 'A'.code..'Z'.code) unsigned + ('a'.code - 'A'.code) else unsigned
        }
        return lower(left) == lower(right)
    }

    private companion object {
        val CONTENT_LENGTH = "Content-Length".toByteArray(StandardCharsets.US_ASCII)
        val TRANSFER_ENCODING = "Transfer-Encoding".toByteArray(StandardCharsets.US_ASCII)
        val CHUNKED = "chunked".toByteArray(StandardCharsets.US_ASCII)
    }
}

/**
 * A fixed set of persistent HTTP/2 connections shared by all JMH threads in a trial.
 *
 * Each invocation opens a new stream and waits for that stream's response. In the load profile,
 * 64 benchmark threads therefore exercise 64 concurrent streams distributed over [connectionCount]
 * TCP connections, matching the multiplexed connections Bosca expects from an h2c-capable Gateway.
 */
@State(Scope.Benchmark)
open class NettyHttp2BenchmarkClient(
    private val connectionCount: Int = 1,
) {
    private val eventLoopGroup = MultiThreadIoEventLoopGroup(connectionCount, NioIoHandler.newFactory())
    private val nextConnection = AtomicInteger()

    @Volatile
    private var port = -1

    @Volatile
    private var connections: List<Channel> = emptyList()

    internal fun exchange(serverPort: Int, request: NettyHttp2BenchmarkRequest): Int {
        val response = CompletableFuture<Int>()
        val availableConnections = ensureConnected(serverPort)
        val parent = availableConnections[Math.floorMod(nextConnection.getAndIncrement(), availableConnections.size)]
        val stream = Http2StreamChannelBootstrap(parent)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(false))
                    ch.pipeline().addLast(Http2BenchmarkResponseHandler(response))
                }
            })
            .open()
            .sync()
            .getNow()

        val body = request.body
        val content = if (body == null) Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(body)
        val httpRequest = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, request.method, request.path, content)
        httpRequest.headers().set(HttpHeaderNames.HOST, "127.0.0.1")
        if (body != null) {
            httpRequest.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
        }
        if (request.acceptGzip) {
            httpRequest.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
        }

        stream.writeAndFlush(httpRequest).addListener { future ->
            if (!future.isSuccess) response.completeExceptionally(future.cause())
        }
        return response.get(10, TimeUnit.SECONDS)
    }

    @TearDown
    open fun close() {
        connections.forEach { it.close().sync() }
        connections = emptyList()
        port = -1
        eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
    }

    private fun ensureConnected(serverPort: Int): List<Channel> {
        val currentConnections = connections
        if (port == serverPort && currentConnections.size == connectionCount && currentConnections.all { it.isActive }) {
            return currentConnections
        }
        return synchronized(this) {
            val synchronizedConnections = connections
            if (port == serverPort &&
                synchronizedConnections.size == connectionCount &&
                synchronizedConnections.all { it.isActive }
            ) {
                return@synchronized synchronizedConnections
            }
            synchronizedConnections.forEach { it.close().sync() }
            val connected = List(connectionCount) {
                connect(serverPort)
            }
            port = serverPort
            connections = connected
            connected
        }
    }

    private fun connect(serverPort: Int): Channel {
        val inboundStreamInitializer = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.close()
            }
        }
        return Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build())
                    ch.pipeline().addLast(Http2MultiplexHandler(inboundStreamInitializer))
                }
            })
            .connect(InetAddress.getLoopbackAddress(), serverPort)
            .sync()
            .channel()
    }
}

/** Four persistent HTTP/2 connections shared by the 64 load-generating JMH threads. */
@State(Scope.Benchmark)
open class NettyHttp2FourConnectionBenchmarkClient : NettyHttp2BenchmarkClient(4)

private class Http2BenchmarkResponseHandler(
    private val response: CompletableFuture<Int>,
) : ChannelInboundHandlerAdapter() {
    private var bodyBytes = 0

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        try {
            if (msg is HttpResponse && msg.status().code() !in 200..299) {
                response.completeExceptionally(IllegalStateException("benchmark request failed with ${msg.status()}"))
            }
            if (msg is HttpContent) {
                bodyBytes += msg.content().readableBytes()
                if (msg is LastHttpContent) {
                    response.complete(bodyBytes)
                    ctx.close()
                }
            }
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (!response.isDone) {
            response.completeExceptionally(IllegalStateException("HTTP/2 stream closed before its response completed"))
        }
        ctx.fireChannelInactive()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        response.completeExceptionally(cause)
        ctx.close()
    }
}
