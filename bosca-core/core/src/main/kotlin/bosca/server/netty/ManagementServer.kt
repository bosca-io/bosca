package bosca.server.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerKeepAliveHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultThreadFactory
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A separate listener that answers the liveness probe (`GET /api/v1/live`) on its own event loop.
 *
 * Enabled by `bosca.server.management-port`. The probe answers 200 while every request executor
 * keeps running tasks, even when they are busy, and 503 naming the stuck executors once one has
 * left a heartbeat unrun for the stall timeout. A [BlockedThreadWatchdog] on this listener's loop
 * sends those heartbeats every half stall timeout, logging the stuck threads' stack traces, and
 * the probe only reads its state: probes add no work to the request executors, however often they
 * arrive. Kubernetes restarts the pod only after the probe's failure threshold, so a short stall
 * never restarts it. The listener never runs application code. Readiness and health checks stay
 * on the main port because they check dependencies.
 *
 * In [RequestDispatcherMode.POOL] mode the request pool's heartbeat waits in the pool's queue
 * behind every request already waiting there, so a backlog longer than the stall timeout reads as
 * a stall even while requests are being served; raise the stall timeout if pool-mode servers run
 * with long backlogs.
 */
internal class ManagementServer(
    private val port: Int,
    ioHandlerFactory: IoHandlerFactory,
    private val serverChannelClass: Class<out ServerChannel>,
    requestExecutors: List<WatchedExecutor>,
    stallTimeoutMillis: Long,
    /** True once the engine has begun stopping; liveness then answers live without checking. */
    private val isStopping: () -> Boolean,
) {
    private val group = MultiThreadIoEventLoopGroup(1, DefaultThreadFactory("bosca-management", true), ioHandlerFactory)

    private val liveness = BlockedThreadWatchdog(
        stallTimeoutMillis,
        requestExecutors,
        report = { message -> log.warn("Liveness is failing: {}", message) },
        recovered = { message -> log.info("Liveness recovered: {}", message) },
    )

    @Volatile
    private var channel: Channel? = null

    private val stopped = AtomicBoolean(false)
    private val openConnections = AtomicInteger()

    /**
     * Binds the listener and returns the bound port, or null if it could not bind. A failure is
     * logged and the server continues without the management listener.
     */
    fun start(): Int? {
        return try {
            val bound = ServerBootstrap()
                .group(group)
                .channel(serverChannelClass)
                .childHandler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        // Bounded so stray clients cannot hold descriptors; a probe needs one short connection.
                        if (openConnections.incrementAndGet() > MAX_CONNECTIONS) {
                            openConnections.decrementAndGet()
                            log.debug("Refusing a management connection: {} are already open", MAX_CONNECTIONS)
                            ch.close()
                            return
                        }
                        ch.closeFuture().addListener { openConnections.decrementAndGet() }
                        ch.pipeline()
                            .addLast(IdleStateHandler(0, 0, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            .addLast(HttpServerCodec(MAX_INITIAL_LINE_LENGTH, MAX_HEADER_SIZE, MAX_CHUNK_SIZE))
                            .addLast(HttpServerKeepAliveHandler())
                            .addLast(LivenessHandler())
                    }
                })
                .bind(port)
                .sync()
                .channel()
            channel = bound
            liveness.start(group.next())
            (bound.localAddress() as InetSocketAddress).port.also { boundPort ->
                log.info("Management listener serving {} on port {}", LIVENESS_PATH, boundPort)
            }
        } catch (e: Exception) {
            log.error("Management listener could not bind port {}; continuing without it", port, e)
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS)
            null
        }
    }

    /** Stops the listener; safe to call more than once. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        liveness.stop()
        channel?.close()?.syncUninterruptibly()
        channel = null
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
    }

    /**
     * Answers the liveness path from [liveness], with the main port's body while live; everything
     * else is 404. Every answer is immediate, so responses leave in request order.
     */
    private inner class LivenessHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            try {
                if (msg is HttpRequest) answer(ctx, msg)
            } finally {
                ReferenceCountUtil.release(msg)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                ctx.close()
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        private fun answer(ctx: ChannelHandlerContext, request: HttpRequest) {
            val head = request.method() == HttpMethod.HEAD
            val isLivenessPath = (request.method() == HttpMethod.GET || head) &&
                request.uri().substringBefore('?') == LIVENESS_PATH
            when {
                request.decoderResult().isFailure ->
                    respond(ctx, HttpResponseStatus.BAD_REQUEST, BAD_REQUEST_BODY, head = false)
                        .addListener(ChannelFutureListener.CLOSE)
                !isLivenessPath -> respond(ctx, HttpResponseStatus.NOT_FOUND, NOT_FOUND_BODY, head)
                isStopping() -> respond(ctx, HttpResponseStatus.OK, LIVE_BODY, head)
                else -> {
                    val stalled = liveness.stalledExecutors()
                    if (stalled.isEmpty()) {
                        respond(ctx, HttpResponseStatus.OK, LIVE_BODY, head)
                    } else {
                        respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, stalledBody(stalled), head)
                    }
                }
            }
        }

        private fun respond(ctx: ChannelHandlerContext, status: HttpResponseStatus, body: ByteArray, head: Boolean): ChannelFuture {
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                if (head) Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(body),
            )
            response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
            return ctx.writeAndFlush(response)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            log.debug("Management connection failed", cause)
            ctx.close()
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(ManagementServer::class.java)

        private const val LIVENESS_PATH = "/api/v1/live"
        private const val MAX_INITIAL_LINE_LENGTH = 4 * 1024
        private const val MAX_HEADER_SIZE = 8 * 1024
        private const val MAX_CHUNK_SIZE = 8 * 1024

        /** Open management connections allowed at once; each probe uses one briefly. */
        const val MAX_CONNECTIONS = 32

        /** How long a management connection may sit idle before it is closed. */
        const val IDLE_TIMEOUT_SECONDS = 30L

        /** Matches the main port's `/api/v1/live` response body (`ReadyResponse("live")`). */
        private val LIVE_BODY = """{"status":"live"}""".toByteArray(StandardCharsets.UTF_8)
        private val BAD_REQUEST_BODY = """{"status":"bad request"}""".toByteArray(StandardCharsets.UTF_8)
        private val NOT_FOUND_BODY = """{"status":"not found"}""".toByteArray(StandardCharsets.UTF_8)

        /** `{"status":"stalled","executors":[...]}`; executor names are engine-assigned, never user input. */
        private fun stalledBody(executors: List<String>): ByteArray =
            executors.joinToString(",", prefix = """{"status":"stalled","executors":[""", postfix = "]}") { "\"$it\"" }
                .toByteArray(StandardCharsets.UTF_8)
    }
}
