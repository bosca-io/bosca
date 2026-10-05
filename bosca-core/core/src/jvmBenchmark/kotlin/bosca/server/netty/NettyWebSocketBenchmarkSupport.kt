package bosca.server.netty

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2SettingsFrame
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val WEBSOCKET_BENCHMARK_MAX_FRAME_SIZE = 1_048_576

private fun benchmarkWorkerThreadCount(): Int =
    minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

internal object NettyWebSocketBenchmarkPayload {
    const val text = "Hello, World!"
    val bytes: ByteArray = text.toByteArray(StandardCharsets.UTF_8)
    val maskedTextFrame: ByteArray = maskedFrame(0x1, bytes)
    val maskedCloseFrame: ByteArray = maskedFrame(0x8, byteArrayOf(0x03, 0xe8.toByte()))

    private fun maskedFrame(opcode: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 125)
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        return ByteArray(2 + mask.size + payload.size).also { frame ->
            frame[0] = (0x80 or opcode).toByte()
            frame[1] = (0x80 or payload.size).toByte()
            mask.copyInto(frame, 2)
            payload.forEachIndexed { index, byte ->
                frame[6 + index] = (byte.toInt() xor mask[index % mask.size].toInt()).toByte()
            }
        }
    }
}

/** Per-JMH-thread persistent RFC 6455 connection used by latency and load profiles. */
@State(Scope.Thread)
open class NettyWebSocketBenchmarkClient {
    private var port = -1
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val responsePayload = ByteArray(125)

    internal fun exchange(serverPort: Int): Int {
        ensureConnected(serverPort)
        val currentOutput = checkNotNull(output)
        currentOutput.write(NettyWebSocketBenchmarkPayload.maskedTextFrame)
        currentOutput.flush()
        return readTextFrame(checkNotNull(input))
    }

    @TearDown
    open fun close() {
        val currentSocket = socket
        if (currentSocket?.isClosed == false) {
            runCatching {
                output?.write(NettyWebSocketBenchmarkPayload.maskedCloseFrame)
                output?.flush()
            }
            currentSocket.close()
        }
        socket = null
        input = null
        output = null
        port = -1
    }

    private fun ensureConnected(serverPort: Int) {
        if (socket?.isClosed == false && port == serverPort) return
        close()
        val newSocket = Socket().apply {
            tcpNoDelay = true
            soTimeout = 10_000
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 5_000)
        }
        val newInput = BufferedInputStream(newSocket.getInputStream(), 64 * 1024)
        val newOutput = BufferedOutputStream(newSocket.getOutputStream(), 64 * 1024)
        val handshake =
            "GET /websocket HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$serverPort\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: AAECAwQFBgcICQoLDA0ODw==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
        newOutput.write(handshake.toByteArray(StandardCharsets.US_ASCII))
        newOutput.flush()
        val response = readHeaders(newInput)
        check(response.startsWith("HTTP/1.1 101")) { "WebSocket handshake failed: $response" }
        socket = newSocket
        input = newInput
        output = newOutput
        port = serverPort
    }

    private fun readTextFrame(currentInput: BufferedInputStream): Int {
        val first = currentInput.read()
        val second = currentInput.read()
        check(first == 0x81) { "expected a final text frame, received opcode byte $first" }
        check(second >= 0 && second and 0x80 == 0) { "server WebSocket frame was masked or truncated" }
        val length = second and 0x7f
        check(length <= responsePayload.size) { "benchmark WebSocket response was too large: $length" }
        var offset = 0
        while (offset < length) {
            val count = currentInput.read(responsePayload, offset, length - offset)
            check(count >= 0) { "WebSocket connection closed with ${length - offset} bytes remaining" }
            offset += count
        }
        check(length == NettyWebSocketBenchmarkPayload.bytes.size)
        check(NettyWebSocketBenchmarkPayload.bytes.indices.all { responsePayload[it] == NettyWebSocketBenchmarkPayload.bytes[it] })
        return length
    }

    private fun readHeaders(currentInput: BufferedInputStream): String {
        val bytes = ByteArray(16 * 1024)
        var size = 0
        var delimiter = 0
        while (delimiter < 4) {
            val value = currentInput.read()
            check(value >= 0) { "connection closed during WebSocket handshake" }
            check(size < bytes.size) { "WebSocket handshake response headers were too large" }
            bytes[size++] = value.toByte()
            delimiter = when {
                delimiter == 0 && value == '\r'.code -> 1
                delimiter == 1 && value == '\n'.code -> 2
                delimiter == 2 && value == '\r'.code -> 3
                delimiter == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
        }
        return String(bytes, 0, size, StandardCharsets.US_ASCII)
    }
}

/** Shared persistent HTTP/2 parents with one long-lived RFC 8441 stream per JMH worker. */
@State(Scope.Benchmark)
open class NettyRfc8441BenchmarkClient(
    private val connectionCount: Int = 1,
) {
    private val eventLoopGroup = MultiThreadIoEventLoopGroup(connectionCount, NioIoHandler.newFactory())
    private val sessions = ConcurrentHashMap<Long, Rfc8441BenchmarkSession>()
    private val nextConnection = AtomicInteger()

    @Volatile
    private var port = -1

    @Volatile
    private var connections: List<Channel> = emptyList()

    internal fun exchange(serverPort: Int): Int {
        val parents = ensureConnected(serverPort)
        val session = sessions.computeIfAbsent(Thread.currentThread().threadId()) {
            val parent = parents[Math.floorMod(nextConnection.getAndIncrement(), parents.size)]
            openSession(parent)
        }
        return session.exchange()
    }

    @TearDown
    open fun close() {
        sessions.values.forEach(Rfc8441BenchmarkSession::close)
        sessions.clear()
        connections.forEach { it.close().syncUninterruptibly() }
        connections = emptyList()
        port = -1
        eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
    }

    private fun ensureConnected(serverPort: Int): List<Channel> {
        val current = connections
        if (port == serverPort && current.size == connectionCount && current.all(Channel::isActive)) return current
        return synchronized(this) {
            val synchronizedConnections = connections
            if (port == serverPort &&
                synchronizedConnections.size == connectionCount &&
                synchronizedConnections.all(Channel::isActive)
            ) {
                return@synchronized synchronizedConnections
            }
            sessions.values.forEach(Rfc8441BenchmarkSession::close)
            sessions.clear()
            synchronizedConnections.forEach { it.close().syncUninterruptibly() }
            List(connectionCount) { connect(serverPort) }.also { connected ->
                port = serverPort
                connections = connected
            }
        }
    }

    private fun connect(serverPort: Int): Channel {
        val settingsReceived = CompletableFuture<Unit>()
        val inboundStreamInitializer = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.close()
            }
        }
        val channel = Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build())
                    ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            if (msg is Http2SettingsFrame && msg.settings().connectProtocolEnabled() == true) {
                                settingsReceived.complete(Unit)
                            }
                            ctx.fireChannelRead(msg)
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            settingsReceived.completeExceptionally(cause)
                            ctx.fireExceptionCaught(cause)
                        }
                    })
                    ch.pipeline().addLast(Http2MultiplexHandler(inboundStreamInitializer))
                }
            })
            .connect(InetAddress.getLoopbackAddress(), serverPort)
            .sync()
            .channel()
        settingsReceived.get(10, TimeUnit.SECONDS)
        return channel
    }

    private fun openSession(parent: Channel): Rfc8441BenchmarkSession {
        val session = Rfc8441BenchmarkSession()
        val stream = Http2StreamChannelBootstrap(parent)
            .handler(session.initializer())
            .open()
            .sync()
            .getNow()
        session.attach(stream)
        val headers = DefaultHttp2Headers()
            .method(HttpMethod.CONNECT.asciiName())
            .scheme("http")
            .authority("127.0.0.1")
            .path("/websocket")
            .set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), "websocket")
            .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
        stream.writeAndFlush(DefaultHttp2HeadersFrame(headers, false)).sync()
        session.awaitAccepted()
        return session
    }
}

/** Four HTTP/2 parents shared by 64 persistent RFC 8441 sessions. */
@State(Scope.Benchmark)
open class NettyRfc8441FourConnectionBenchmarkClient : NettyRfc8441BenchmarkClient(4)

private class Rfc8441BenchmarkSession {
    private val accepted = CompletableFuture<Unit>()
    private val closed = CompletableFuture<Unit>()
    private val pendingResponse = AtomicReference<CompletableFuture<Int>?>()
    private lateinit var stream: Channel

    fun attach(channel: Channel) {
        stream = channel
    }

    fun initializer(): ChannelInitializer<Channel> = object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) {
            val decoder = io.netty.channel.embedded.EmbeddedChannel(
                WebSocket13FrameDecoder(
                    WebSocketDecoderConfig.newBuilder()
                        .expectMaskedFrames(false)
                        .allowMaskMismatch(false)
                        .allowExtensions(false)
                        .maxFramePayloadLength(WEBSOCKET_BENCHMARK_MAX_FRAME_SIZE)
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
                                val status = msg.headers().status()?.toString()?.toIntOrNull()
                                if (status == 200 && !msg.isEndStream) {
                                    accepted.complete(Unit)
                                } else {
                                    accepted.completeExceptionally(
                                        IllegalStateException("RFC 8441 benchmark handshake failed with status $status"),
                                    )
                                }
                            }
                            is Http2DataFrame -> {
                                if (msg.content().isReadable) {
                                    decoder.writeInbound(msg.content().retain())
                                    while (true) {
                                        val frame = decoder.readInbound<WebSocketFrame>() ?: break
                                        try {
                                            when (frame) {
                                                is TextWebSocketFrame -> completeTextResponse(frame)
                                                is CloseWebSocketFrame -> {
                                                    ctx.writeAndFlush(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
                                                    closed.complete(Unit)
                                                }
                                            }
                                        } finally {
                                            ReferenceCountUtil.release(frame)
                                        }
                                    }
                                }
                                if (msg.isEndStream) closed.complete(Unit)
                            }
                        }
                    } finally {
                        ReferenceCountUtil.release(msg)
                    }
                }

                override fun channelInactive(ctx: ChannelHandlerContext) {
                    decoder.finishAndReleaseAll()
                    pendingResponse.getAndSet(null)?.completeExceptionally(
                        IllegalStateException("RFC 8441 stream closed before its echo completed"),
                    )
                    closed.complete(Unit)
                    ctx.fireChannelInactive()
                }

                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    accepted.completeExceptionally(cause)
                    pendingResponse.getAndSet(null)?.completeExceptionally(cause)
                    closed.completeExceptionally(cause)
                    ctx.close()
                }
            })
        }
    }

    fun awaitAccepted() {
        accepted.get(10, TimeUnit.SECONDS)
    }

    fun exchange(): Int {
        val response = CompletableFuture<Int>()
        check(pendingResponse.compareAndSet(null, response)) { "RFC 8441 benchmark issued overlapping exchanges" }
        stream.writeAndFlush(
            DefaultHttp2DataFrame(Unpooled.wrappedBuffer(NettyWebSocketBenchmarkPayload.maskedTextFrame), false),
        ).addListener { future ->
            if (!future.isSuccess) {
                pendingResponse.compareAndSet(response, null)
                response.completeExceptionally(future.cause())
            }
        }
        return response.get(10, TimeUnit.SECONDS)
    }

    fun close() {
        if (!::stream.isInitialized || !stream.isOpen) return
        runCatching {
            stream.writeAndFlush(
                DefaultHttp2DataFrame(Unpooled.wrappedBuffer(NettyWebSocketBenchmarkPayload.maskedCloseFrame), false),
            ).syncUninterruptibly()
            closed.get(5, TimeUnit.SECONDS)
        }
        if (stream.isOpen) stream.close().syncUninterruptibly()
    }

    private fun completeTextResponse(frame: TextWebSocketFrame) {
        val content = frame.content()
        check(content.readableBytes() == NettyWebSocketBenchmarkPayload.bytes.size)
        check(NettyWebSocketBenchmarkPayload.bytes.indices.all { index ->
            content.getByte(content.readerIndex() + index) == NettyWebSocketBenchmarkPayload.bytes[index]
        })
        val response = pendingResponse.getAndSet(null)
            ?: error("RFC 8441 benchmark received an unsolicited text frame")
        response.complete(content.readableBytes())
    }
}

/** Minimal RFC 6455 echo server used to isolate Bosca's route/coroutine overhead. */
internal class RawNettyWebSocketBenchmarkServer {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(
        benchmarkWorkerThreadCount(),
        NioIoHandler.newFactory(),
    )
    private lateinit var channel: Channel

    val port: Int
        get() = (channel.localAddress() as InetSocketAddress).port

    fun start() {
        val protocolConfig = WebSocketServerProtocolConfig.newBuilder()
            .websocketPath("/websocket")
            .checkStartsWith(false)
            .allowExtensions(false)
            .maxFramePayloadLength(WEBSOCKET_BENCHMARK_MAX_FRAME_SIZE)
            .build()
        channel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpServerCodec(16384, 16384, 65536))
                        .addLast(HttpObjectAggregator(1_048_576))
                        .addLast(WebSocketServerProtocolHandler(protocolConfig))
                        .addLast(RawWebSocketEchoHandler())
                }
            })
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .bind(0)
            .sync()
            .channel()
    }

    fun stop() {
        channel.close().sync()
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
    }
}

/** RFC 8441 transport-only echo server used to isolate Bosca's application layer. */
internal class TransportOnlyRfc8441BenchmarkServer {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(
        benchmarkWorkerThreadCount(),
        NioIoHandler.newFactory(),
    )
    private lateinit var channel: Channel

    val port: Int
        get() = (channel.localAddress() as InetSocketAddress).port

    fun start() {
        channel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val streamInitializer = object : ChannelInitializer<Channel>() {
                        override fun initChannel(ch: Channel) {
                            ch.pipeline().addLast(RawRfc8441EchoHandler())
                        }
                    }
                    ch.pipeline()
                        .addLast(
                            Http2FrameCodecBuilder.forServer()
                                .initialSettings(Http2Settings.defaultSettings().connectProtocolEnabled(true))
                                .build(),
                        )
                        .addLast(Http2MultiplexHandler(streamInitializer))
                }
            })
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .bind(0)
            .sync()
            .channel()
    }

    fun stop() {
        channel.close().sync()
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
    }
}

private class RawWebSocketEchoHandler : SimpleChannelInboundHandler<WebSocketFrame>() {
    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        when (frame) {
            is TextWebSocketFrame -> ctx.writeAndFlush(TextWebSocketFrame(frame.content().retain()))
            is CloseWebSocketFrame -> ctx.writeAndFlush(frame.retain()).addListener(ChannelFutureListener.CLOSE)
        }
    }
}

private class RawRfc8441EchoHandler : SimpleChannelInboundHandler<Any>() {
    private val decoder = io.netty.channel.embedded.EmbeddedChannel(
        WebSocket13FrameDecoder(
            WebSocketDecoderConfig.newBuilder()
                .expectMaskedFrames(true)
                .allowMaskMismatch(false)
                .allowExtensions(false)
                .maxFramePayloadLength(WEBSOCKET_BENCHMARK_MAX_FRAME_SIZE)
                .closeOnProtocolViolation(true)
                .withUTF8Validator(true)
                .build(),
        ),
    )
    private val encoder = io.netty.channel.embedded.EmbeddedChannel(WebSocket13FrameEncoder(false))

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is Http2HeadersFrame -> {
                val headers = msg.headers()
                val valid = headers.method()?.toString().equals(HttpMethod.CONNECT.name(), true) &&
                    headers.get(Http2Headers.PseudoHeaderName.PROTOCOL.value())?.toString().equals("websocket", true) &&
                    !msg.isEndStream
                if (valid) {
                    ctx.writeAndFlush(
                        DefaultHttp2HeadersFrame(DefaultHttp2Headers().status("200"), false),
                    )
                } else {
                    ctx.writeAndFlush(
                        DefaultHttp2HeadersFrame(DefaultHttp2Headers().status("400"), true),
                    ).addListener(ChannelFutureListener.CLOSE)
                }
            }
            is Http2DataFrame -> {
                if (msg.content().isReadable) {
                    decoder.writeInbound(msg.content().retain())
                    while (true) {
                        val frame = decoder.readInbound<WebSocketFrame>() ?: break
                        try {
                            check(encoder.writeOutbound(frame.retain()))
                            while (true) {
                                val encoded = encoder.readOutbound<io.netty.buffer.ByteBuf>() ?: break
                                val close = frame is CloseWebSocketFrame
                                ctx.write(DefaultHttp2DataFrame(encoded, close))
                                if (close) ctx.flush()
                            }
                        } finally {
                            frame.release()
                        }
                    }
                }
                if (msg.isEndStream) ctx.close()
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        decoder.finishAndReleaseAll()
        encoder.finishAndReleaseAll()
        ctx.fireChannelInactive()
    }
}
