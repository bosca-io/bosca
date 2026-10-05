package bosca.server.netty

import bosca.jmx.JmxModule
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame
import io.netty.handler.codec.http2.Http2Error
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import kotlin.coroutines.EmptyCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/** Point-in-time ownership state for the Netty transport and its application coroutines. */
internal data class NettyTransportSnapshot(
    val activeConnections: Int,
    val activeHttp2Streams: Int,
    val activeApplicationJobs: Int,
    val bossGroupTerminated: Boolean,
    val workerGroupTerminated: Boolean,
    val codecGroupTerminated: Boolean,
)

/**
 * Bootstraps and manages the Netty HTTP server.
 *
 * Uses [MultiThreadIoEventLoopGroup] with the Linux epoll transport ([EpollIoHandler]) when
 * available, falling back to portable [NioIoHandler] otherwise (see [createTransport]). HTTP
 * protocol work stays on each channel's I/O event loop. Application routes (including long-lived
 * SSE and WebSocket handlers) run where [RequestDispatcherMode] says: by default on the same event
 * loop, which avoids a thread handoff in and out of every request, or on a CPU-sized
 * `bosca-request` pool. In either mode, blocking calls must run on `Dispatchers.IO` and database
 * work on its own dispatcher; both suspend, so the request thread keeps serving other connections.
 * Connection pipelines are assembled by [HttpChannelInitializer]; settings come from
 * `bosca.server.*` via [NettyServerSettings].
 *
 * **Note:** TLS/SSL is not configured in the pipeline. The server is designed to run behind
 * a reverse proxy (e.g., nginx, Envoy, or a cloud load balancer) that terminates TLS.
 */
class NettyServerEngine(
    val application: BoscaApplication,
    private val port: Int = 8080
) {
    private val availableProcessorCount = Runtime.getRuntime().availableProcessors()
    private val settings = NettyServerSettings.from(application.config, availableProcessorCount)

    // Event-loop transport: prefer Linux's epoll (faster than NIO), fall back to portable NIO.
    private val transport = createTransport()

    // Compression is CPU-heavy. Run inline on an I/O event loop, it would stall that loop and every
    // channel pinned to it (health probes included) while a large response is encoded. Only
    // responses that will actually be encoded are submitted here; negotiation and pass-through
    // responses stay on the channel event loop. Daemon threads; shut down in [stop].
    private val codecGroup = DefaultEventExecutorGroup(settings.codecThreads, DefaultThreadFactory("bosca-codec", true))

    // Threads for the engine scope's dispatcher. The cached pool itself is unbounded; [requestPool]
    // caps how many of its threads run at once. Daemon threads; closed in [stop].
    private val requestExecutor = Executors.newCachedThreadPool(DefaultThreadFactory(REQUEST_THREAD_PREFIX, true))
        .asCoroutineDispatcher()

    // The engine scope's dispatcher, capped at `request-threads` (by default the CPU count, like
    // Dispatchers.Default's core pool). It runs request handlers in POOL mode, and engine-level work
    // (error reporting, WebSocket session bases) in either mode. Measured equal to Default (better on
    // RFC 8441) when no larger than the CPU count. Blocking calls still belong on Dispatchers.IO: no
    // CPU-sized pool can absorb them. Each channel owns a child job (see NettyHttpHandler.channelActive).
    private val requestPool = requestExecutor.limitedParallelism(settings.requestThreads)
    private val scope = CoroutineScope(requestPool + SupervisorJob())
    private val httpHandler = NettyHttpHandler(application, scope, settings.maxRequestSize, settings.requestDispatcher)

    // Executors that run request work; heartbeats to them back the watchdog and management liveness.
    private val requestExecutors = watchedRequestExecutors()

    // Opt-in diagnostic (bosca.server.blocked-thread-watchdog.enabled); nothing runs when disabled.
    private val watchdog: BlockedThreadWatchdog? = if (settings.blockedThreadWatchdogEnabled) {
        BlockedThreadWatchdog(settings.blockedThreadWatchdogThresholdMillis, requestExecutors)
    } else {
        null
    }

    // Cross-thread lifecycle state: written by stop() and read by event loops and diagnostics.
    private val stopped = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val acceptedChannels = ConcurrentHashMap.newKeySet<Channel>()
    private val activeHttp2Streams = AtomicInteger()

    /** The actual bound port, populated once [start] has successfully bound the server socket. */
    @Volatile
    var boundPort: Int? = null
        private set

    @Volatile
    private var serverChannel: Channel? = null

    @Volatile
    private var managementServer: ManagementServer? = null

    /** The management listener's bound port, when `bosca.server.management-port` enabled it. */
    @Volatile
    internal var boundManagementPort: Int? = null
        private set

    /** Resolved event-loop transport: the boss/worker groups, server channel type, and a name for logging. */
    private class Transport(
        val bossGroup: MultiThreadIoEventLoopGroup,
        val workerGroup: MultiThreadIoEventLoopGroup,
        val serverChannelClass: Class<out ServerChannel>,
        val name: String,
        /** Creates I/O handlers of this transport for additional groups (the management listener). */
        val newIoHandlerFactory: () -> IoHandlerFactory,
    )

    /**
     * Builds the event-loop groups, preferring epoll and falling back to NIO. As defense in depth
     * we don't rely on [Epoll.isAvailable] alone: we attempt to create the epoll groups and fall
     * back to NIO if that throws — shutting down any partially created group first — so a blocked
     * or broken native transport can never prevent the server from starting.
     */
    private fun createTransport(): Transport {
        if (Epoll.isAvailable()) {
            var bossGroup: MultiThreadIoEventLoopGroup? = null
            try {
                bossGroup = MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())
                val workerGroup = MultiThreadIoEventLoopGroup(settings.workerThreads, workerThreadFactory(), EpollIoHandler.newFactory())
                return Transport(bossGroup, workerGroup, EpollServerSocketChannel::class.java, "epoll", EpollIoHandler::newFactory)
            } catch (e: Exception) {
                log.warn("epoll reported available but failed to initialize; falling back to NIO", e)
                bossGroup?.shutdownGracefully()
            }
        }
        return Transport(
            MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()),
            MultiThreadIoEventLoopGroup(settings.workerThreads, workerThreadFactory(), NioIoHandler.newFactory()),
            NioServerSocketChannel::class.java,
            "NIO",
            NioIoHandler::newFactory,
        )
    }

    /**
     * Every I/O event loop, plus the request pool when handlers run there. A stall dumps the thread
     * that last ran the executor's heartbeat, or, before its first heartbeat, every thread of its kind.
     */
    private fun watchedRequestExecutors(): List<WatchedExecutor> = buildList {
        transport.workerGroup.forEachIndexed { index, loop ->
            add(WatchedExecutor("event loop ${index + 1}", loop::execute) { last -> last?.let(::listOf) ?: threadsNamed(WORKER_THREAD_PREFIX) })
        }
        if (settings.requestDispatcher == RequestDispatcherMode.POOL) {
            add(
                WatchedExecutor("bosca-request pool", { requestPool.dispatch(EmptyCoroutineContext, it) }) {
                    threadsNamed(REQUEST_THREAD_PREFIX)
                },
            )
        }
    }

    private fun threadsNamed(prefix: String): List<Thread> =
        Thread.getAllStackTraces().keys.filter { thread -> thread.name.startsWith("$prefix-") }

    // Named so a stall report can find the worker threads; non-daemon, like Netty's default.
    private fun workerThreadFactory() = DefaultThreadFactory(WORKER_THREAD_PREFIX)

    /**
     * Starts the Netty server, binding to the configured port and beginning to accept connections.
     * Blocks the calling thread until the server is shut down.
     */
    fun start() {
        val bootstrap = ServerBootstrap()
            .group(transport.bossGroup, transport.workerGroup)
            .channel(transport.serverChannelClass)
            .childHandler(
                HttpChannelInitializer(
                    settings,
                    codecGroup,
                    httpHandler,
                    isDraining = draining::get,
                    onConnectionAccepted = { channel ->
                        acceptedChannels.add(channel)
                        channel.closeFuture().addListener { acceptedChannels.remove(channel) }
                    },
                    onStreamOpened = { activeHttp2Streams.incrementAndGet() },
                    onStreamClosed = { activeHttp2Streams.decrementAndGet() },
                ),
            )
            .option(ChannelOption.SO_BACKLOG, SERVER_SOCKET_BACKLOG)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)

        // Liveness comes up first, so it is answering by the time the main port accepts traffic.
        startManagementServer()
        val future = try {
            bootstrap.bind(port).sync()
        } catch (e: Exception) {
            // Without the main listener this server can never serve, so liveness must stop passing
            // and the engine's threads must not keep the process alive.
            log.error("Bosca server could not bind port {}; stopping", port, e)
            releaseAfterFailedStart()
            throw e
        }
        serverChannel = future.channel()
        boundPort = (future.channel().localAddress() as InetSocketAddress).port
        watchdog?.let {
            it.start()
            log.info("Blocked-thread watchdog enabled with a {} ms threshold", settings.blockedThreadWatchdogThresholdMillis)
        }
        // A stop() that ran during startup stopped the watchdog before it started.
        if (stopped.get()) watchdog?.stop()
        log.info(
            "Bosca server started on port {} with {} worker threads, {} codec threads and {} request threads " +
                "(handlers run on the {} dispatcher) " +
                "for {} available processors using {} transport; HTTP/2 is {} with at most {} concurrent streams " +
                "per connection and response compression is {}",
            boundPort,
            settings.workerThreads,
            settings.codecThreads,
            settings.requestThreads,
            settings.requestDispatcher,
            availableProcessorCount,
            transport.name,
            if (settings.http2Enabled) "enabled" else "disabled",
            settings.http2MaxConcurrentStreams,
            if (settings.compressionEnabled) "enabled" else "disabled",
        )
        future.channel().closeFuture().sync()
    }

    /**
     * Gracefully shuts down the server, draining in-flight requests before cancelling.
     * Idempotent — safe to call multiple times.
     */
    fun stop() {
        stop(haltJvm = true)
    }

    /**
     * Gracefully shuts down an engine hosted inside another JVM without terminating that JVM.
     * This is used by tests and benchmarks that own the engine but not the process.
     */
    fun stopWithoutHalting() {
        stop(haltJvm = false)
    }

    private fun stop(haltJvm: Boolean) {
        if (!stopped.compareAndSet(false, true)) return
        log.info("Shutting down Bosca server, draining in-flight requests...")
        watchdog?.stop()
        draining.set(true)
        signalConnectionDraining()
        // Close the listener immediately. Accepted channels remain alive for the configured drain
        // window, but their drain handlers now reject new work and HTTP/2 peers have received GOAWAY.
        serverChannel?.close()?.sync()
        serverChannel = null
        transport.bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync()
        // Allow in-flight requests a grace period to complete, then cancel remaining coroutines and
        // wait for their cancellation handlers. The shutdown timeout bounds that second phase on its
        // own, so a drain as long as the timeout can never skip application shutdown; it prevents
        // hanging on library threads (e.g. NATS reconnect threads) that may not stop from close().
        try {
            runBlocking {
                delay(settings.drainTimeoutMs.milliseconds)
                withTimeout(settings.shutdownTimeoutMs.milliseconds) {
                    scope.coroutineContext.job.cancelAndJoin()
                    application.shutdown()
                }
            }
        } catch (e: TimeoutCancellationException) {
            log.warn("Shutdown timed out after {}ms, forcing exit", settings.shutdownTimeoutMs, e)
        } catch (e: Exception) {
            log.error("Application shutdown failed, forcing exit", e)
        }
        // Request coroutines were cancelled and joined above, unless the shutdown timed out or
        // failed; either way, close() only stops the pool from taking new tasks.
        requestExecutor.close()
        transport.workerGroup.shutdownGracefully(2, 10, TimeUnit.SECONDS).sync()
        codecGroup.shutdownGracefully().sync()
        // Liveness keeps answering until everything else has stopped.
        managementServer?.stop()
        managementServer = null
        log.info("Bosca server stopped")
        if (haltJvm) {
            Runtime.getRuntime().halt(0)
        }
    }

    /**
     * Releases everything [start] created before its main bind failed, including the application's
     * own resources (database pool, messaging), since the later [stop] this marks as done will not
     * run them.
     */
    private fun releaseAfterFailedStart() {
        // A stop() that already began (a shutdown signal during the bind) owns the shutdown.
        if (!stopped.compareAndSet(false, true)) return
        managementServer?.stop()
        managementServer = null
        boundManagementPort = null
        watchdog?.stop()
        try {
            runBlocking {
                withTimeout(settings.shutdownTimeoutMs.milliseconds) {
                    scope.coroutineContext.job.cancelAndJoin()
                    application.shutdown()
                }
            }
        } catch (e: Exception) {
            // Also catches the timeout's TimeoutCancellationException: this is a plain thread, not a
            // coroutine, so there is no cancellation to propagate; the failed start is rethrown by start().
            log.error("Application shutdown after a failed start did not complete", e)
        }
        requestExecutor.close()
        transport.bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS)
        transport.workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS)
        codecGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS)
    }

    private fun startManagementServer() {
        val managementPort = settings.managementPort ?: return
        if (managementPort != 0 && managementPort == port) {
            log.warn("bosca.server.management-port {} is the main port; the management listener is disabled", managementPort)
            return
        }
        val server = ManagementServer(
            managementPort,
            transport.newIoHandlerFactory(),
            transport.serverChannelClass,
            requestExecutors,
            settings.managementLivenessStallTimeoutMillis,
            isStopping = stopped::get,
        )
        val bound = server.start() ?: return
        managementServer = server
        boundManagementPort = bound
        // A stop() that ran while the listener was binding found no listener to stop.
        if (stopped.get()) server.stop()
    }

    private fun signalConnectionDraining() {
        acceptedChannels.forEach { channel ->
            val notify = Runnable {
                if (channel.isActive && channel.pipeline().get(PipelineHandlerNames.HTTP2_FRAME_CODEC) != null) {
                    channel.writeAndFlush(DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR))
                }
            }
            if (channel.eventLoop().inEventLoop()) {
                notify.run()
            } else {
                try {
                    channel.eventLoop().execute(notify)
                } catch (cause: Throwable) {
                    log.warn("Unable to mark connection as draining: {}", channel, cause)
                }
            }
        }
    }

    /** Returns a read-only lifecycle snapshot used by transport soak tests and diagnostics. */
    internal fun transportSnapshot(): NettyTransportSnapshot = NettyTransportSnapshot(
        activeConnections = acceptedChannels.size,
        activeHttp2Streams = activeHttp2Streams.get(),
        activeApplicationJobs = scope.coroutineContext.job.children.count(),
        bossGroupTerminated = transport.bossGroup.isTerminated,
        workerGroupTerminated = transport.workerGroup.isTerminated,
        codecGroupTerminated = codecGroup.isTerminated,
    )

    companion object {
        private val log = LoggerFactory.getLogger(NettyServerEngine::class.java)

        /** Pending-connection queue length of the listening socket. */
        private const val SERVER_SOCKET_BACKLOG = 1024

        /** Thread-name prefixes of the I/O event loops and the request pool. */
        private const val WORKER_THREAD_PREFIX = "bosca-worker"
        private const val REQUEST_THREAD_PREFIX = "bosca-request"

        // Published pipeline names, kept for API compatibility. [PipelineHandlerNames] is the single definition.

        /** Alias of [PipelineHandlerNames.COMPRESSOR]. */
        const val COMPRESSOR_HANDLER_NAME = PipelineHandlerNames.COMPRESSOR

        /** Alias of [PipelineHandlerNames.HTTP_CODEC]. */
        const val HTTP_CODEC_HANDLER_NAME = PipelineHandlerNames.HTTP_CODEC

        /** Alias of [PipelineHandlerNames.HTTP2_CLEARTEXT]. */
        const val HTTP2_CLEARTEXT_HANDLER_NAME = PipelineHandlerNames.HTTP2_CLEARTEXT

        /** Alias of [PipelineHandlerNames.HTTP2_FRAME_CODEC]. */
        const val HTTP2_FRAME_CODEC_HANDLER_NAME = PipelineHandlerNames.HTTP2_FRAME_CODEC

        /** Alias of [PipelineHandlerNames.HTTP2_MULTIPLEX]. */
        const val HTTP2_MULTIPLEX_HANDLER_NAME = PipelineHandlerNames.HTTP2_MULTIPLEX

        /** Alias of [PipelineHandlerNames.HTTP1_SEQUENCER]. */
        const val HTTP1_SEQUENCER_HANDLER_NAME = PipelineHandlerNames.HTTP1_SEQUENCER

        /** Alias of [PipelineHandlerNames.HTTP1_SELECTED]. */
        const val HTTP1_SELECTED_HANDLER_NAME = PipelineHandlerNames.HTTP1_SELECTED

        /** Alias of [PipelineHandlerNames.CHUNKED_WRITER]. */
        const val CHUNKED_WRITER_HANDLER_NAME = PipelineHandlerNames.CHUNKED_WRITER

        /** Alias of [PipelineHandlerNames.HTTP_HANDLER]. */
        const val HTTP_HANDLER_NAME = PipelineHandlerNames.HTTP_HANDLER

        /**
         * Creates and starts a [NettyServerEngine] using the given configuration resource.
         *
         * Accepts optional command-line [args] to override the config file location.
         * Use `-config=/path/to/application.yaml` to load from the filesystem instead
         * of the classpath. Falls back to the classpath resource when no argument is provided.
         */
        fun start(
            configResource: String = "application.yaml",
            args: Array<String> = emptyArray(),
            module: suspend BoscaApplication.() -> Unit
        ) {
            val engine = create(configResource, args, module)

            Runtime.getRuntime().addShutdownHook(Thread {
                engine.stop()
            })

            engine.start()
        }

        /** Builds and initializes an engine without binding or installing a JVM-halting shutdown hook. */
        internal fun create(
            configResource: String = "application.yaml",
            args: Array<String> = emptyArray(),
            module: suspend BoscaApplication.() -> Unit,
        ): NettyServerEngine {
            val configPath = args.firstOrNull { it.startsWith("-config=") }?.substringAfter("=")
            val config = if (configPath != null) {
                log.info("Loading configuration from file: {}", configPath)
                ApplicationConfig.loadFromFile(configPath)
            } else {
                log.info("Loading configuration from classpath: {}", configResource)
                ApplicationConfig.loadFromClasspath(configResource)
            }
            val port = NettyServerSettings.configuredPort(config)
            val application = BoscaApplication(config)

            val engine = NettyServerEngine(application, port)

            // Run the module initialization, then register JMX MBean
            runBlocking {
                application.module()
                application.install(JmxModule(port, engine.settings.workerThreads))
            }

            application.freezeMiddleware()
            return engine
        }
    }
}
