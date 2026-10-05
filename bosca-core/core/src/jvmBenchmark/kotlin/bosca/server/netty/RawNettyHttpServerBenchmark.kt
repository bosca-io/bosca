package bosca.server.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerExpectContinueHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.DefaultThreadFactory
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.openjdk.jmh.annotations.Threads
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Minimal Netty HTTP-codec control used to separate transport cost from Bosca's request path. */
@State(Scope.Benchmark)
open class RawNettyHttpServerBenchmark {
    private val server = RawNettyBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.plaintext)
}

/** Concurrent minimal-Netty control using the same 64 persistent clients as Bosca's load profile. */
@State(Scope.Benchmark)
open class RawNettyHttpServerLoadBenchmark {
    private val server = RawNettyBenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64Connections(client: NettyHttpBenchmarkClient): Int =
        client.exchange(server.port, NettyHttpBenchmarkRequests.plaintext)
}

/** Minimal HTTP/2 control using the same multiplexed client as Bosca's HTTP/2 latency benchmark. */
@State(Scope.Benchmark)
open class RawNettyHttp2ServerBenchmark {
    private val server = RawNettyHttp2BenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/** Minimal HTTP/2 control with 64 streams distributed over one or four TCP connections. */
@State(Scope.Benchmark)
open class RawNettyHttp2ServerLoadBenchmark {
    private val server = RawNettyHttp2BenchmarkServer()

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64StreamsOnOneConnection(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64StreamsOnFourConnections(client: NettyHttp2FourConnectionBenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/**
 * HTTP/2 control that adds only Bosca's event-loop-to-codec-executor handoff around the
 * application handler. Comparing this with [RawNettyHttp2ServerLoadBenchmark] isolates the
 * scheduling boundary from routing, request objects, coroutines, and response construction.
 */
@State(Scope.Benchmark)
open class RawNettyOffloadedHttp2ServerLoadBenchmark {
    private val server = RawNettyHttp2BenchmarkServer(RawHttp2Pipeline.OFFLOADED_HANDLER)

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64StreamsOnFourConnections(client: NettyHttp2FourConnectionBenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/** Single-stream latency control for Bosca's event-loop-to-codec-executor handoff. */
@State(Scope.Benchmark)
open class RawNettyOffloadedHttp2ServerBenchmark {
    private val server = RawNettyHttp2BenchmarkServer(RawHttp2Pipeline.OFFLOADED_HANDLER)

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/**
 * HTTP/2 control with Bosca's per-stream codec pipeline but without its request abstractions,
 * coroutine dispatch, routing, middleware, or response implementation.
 */
@State(Scope.Benchmark)
open class RawNettyBoscaPipelineHttp2ServerLoadBenchmark {
    private val server = RawNettyHttp2BenchmarkServer(RawHttp2Pipeline.BOSCA_CODEC_PIPELINE)

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    @Threads(64)
    open fun plaintextAcross64StreamsOnFourConnections(client: NettyHttp2FourConnectionBenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/** Single-stream latency control for Bosca's complete per-stream codec pipeline. */
@State(Scope.Benchmark)
open class RawNettyBoscaPipelineHttp2ServerBenchmark {
    private val server = RawNettyHttp2BenchmarkServer(RawHttp2Pipeline.BOSCA_CODEC_PIPELINE)

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

/**
 * Single-stream latency control for Bosca's per-stream handlers without an executor handoff.
 * Its delta from raw Netty is handler work; its delta from the offloaded pipeline is scheduling.
 */
@State(Scope.Benchmark)
open class RawNettyEventLoopBoscaPipelineHttp2ServerBenchmark {
    private val server = RawNettyHttp2BenchmarkServer(RawHttp2Pipeline.EVENT_LOOP_BOSCA_CODEC_PIPELINE)

    @Setup
    open fun setup() = server.start()

    @TearDown
    open fun tearDown() = server.stop()

    @Benchmark
    open fun plaintext(client: NettyHttp2BenchmarkClient): Int =
        client.exchange(server.port, NettyHttp2BenchmarkRequests.plaintext)
}

private class RawNettyBenchmarkServer {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(
        maxOf(1, Runtime.getRuntime().availableProcessors() / 4),
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
                    ch.pipeline()
                        .addLast("httpCodec", HttpServerCodec(16384, 16384, 65536))
                        .addLast("handler", RawPlaintextHandler())
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

private enum class RawHttp2Pipeline {
    EVENT_LOOP,
    EVENT_LOOP_BOSCA_CODEC_PIPELINE,
    OFFLOADED_HANDLER,
    BOSCA_CODEC_PIPELINE,
}

private class RawNettyHttp2BenchmarkServer(
    private val pipelineType: RawHttp2Pipeline = RawHttp2Pipeline.EVENT_LOOP,
) {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(
        maxOf(1, Runtime.getRuntime().availableProcessors() / 4),
        NioIoHandler.newFactory(),
    )
    private val codecGroup = if (
        pipelineType == RawHttp2Pipeline.EVENT_LOOP ||
        pipelineType == RawHttp2Pipeline.EVENT_LOOP_BOSCA_CODEC_PIPELINE
    ) {
        null
    } else {
        DefaultEventExecutorGroup(
            benchmarkCodecThreadCount(),
            DefaultThreadFactory("raw-netty-codec", true),
        )
    }
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
                            val pipeline = ch.pipeline()
                            pipeline.addLast(Http2StreamFrameToHttpObjectCodec(true))
                            when (pipelineType) {
                                RawHttp2Pipeline.EVENT_LOOP -> pipeline.addLast(RawPlaintextHandler())
                                RawHttp2Pipeline.EVENT_LOOP_BOSCA_CODEC_PIPELINE -> {
                                    pipeline.addLast(HttpServerExpectContinueHandler())
                                    pipeline.addLast(SelectiveContentCompressor())
                                    pipeline.addLast(ChunkedWriteHandler())
                                    pipeline.addLast(RawPlaintextHandler())
                                }
                                RawHttp2Pipeline.OFFLOADED_HANDLER ->
                                    pipeline.addLast(checkNotNull(codecGroup), "handler", RawPlaintextHandler())
                                RawHttp2Pipeline.BOSCA_CODEC_PIPELINE -> {
                                    pipeline.addLast(HttpServerExpectContinueHandler())
                                    pipeline.addLast(
                                        checkNotNull(codecGroup),
                                        "compressor",
                                        SelectiveContentCompressor(),
                                    )
                                    pipeline.addLast(checkNotNull(codecGroup), "chunkedWriter", ChunkedWriteHandler())
                                    pipeline.addLast(checkNotNull(codecGroup), "handler", RawPlaintextHandler())
                                }
                            }
                        }
                    }
                    ch.pipeline()
                        .addLast(Http2FrameCodecBuilder.forServer().build())
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
        codecGroup?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.sync()
    }

    private fun benchmarkCodecThreadCount(): Int =
        System.getenv("BOSCA_BENCHMARK_CODEC_THREADS")?.toIntOrNull()
            ?: maxOf(4, Runtime.getRuntime().availableProcessors())
}

private class RawPlaintextHandler : SimpleChannelInboundHandler<HttpObject>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        if (msg !is HttpRequest) return
        val content = Unpooled.wrappedBuffer(PLAINTEXT_BYTES)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        ctx.writeAndFlush(response)
    }

    private companion object {
        val PLAINTEXT_BYTES = "Hello, World!".toByteArray(StandardCharsets.UTF_8)
    }
}
