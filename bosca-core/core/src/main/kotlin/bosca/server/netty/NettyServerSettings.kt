package bosca.server.netty

import bosca.server.config.ApplicationConfig
import io.netty.handler.codec.http2.Http2CodecUtil
import org.slf4j.LoggerFactory

/**
 * Transport settings read from `bosca.server.*`.
 *
 * A missing key uses its default. An unparseable value, or one outside the range a setting
 * documents, logs a warning naming the key and falls back to the default, so a configuration typo
 * never prevents startup.
 */
internal data class NettyServerSettings(
    /** Socket I/O event loops. */
    val workerThreads: Int,
    /** Threads that compress responses off the I/O event loops. */
    val codecThreads: Int,
    /** Threads that run request handlers (routes, SSE, WebSocket sessions) in [RequestDispatcherMode.POOL] mode. */
    val requestThreads: Int,
    /** Where request handlers run. */
    val requestDispatcher: RequestDispatcherMode,
    /** Whether the [BlockedThreadWatchdog] diagnostic runs. Off unless configured. */
    val blockedThreadWatchdogEnabled: Boolean,
    /** How long a request executor may stay in one task before the watchdog reports it. */
    val blockedThreadWatchdogThresholdMillis: Long,
    /** Port of the [ManagementServer] liveness listener, or null when it is disabled (the default). */
    val managementPort: Int?,
    /**
     * How long a request executor may leave a liveness heartbeat unrun before liveness answers 503.
     * Heartbeats go out every half of it.
     */
    val managementLivenessStallTimeoutMillis: Long,
    /** Largest accepted request body, in bytes. */
    val maxRequestSize: Long,
    /** Connection idle timeout (no reads or writes), in seconds. */
    val idleTimeoutSeconds: Long,
    /** Grace period for in-flight requests after shutdown begins, in milliseconds. */
    val drainTimeoutMs: Long,
    /**
     * Upper bound, after the drain period, on cancelling remaining requests and shutting the
     * application down, in milliseconds.
     */
    val shutdownTimeoutMs: Long,
    /** Whether eligible responses are compressed. */
    val compressionEnabled: Boolean,
    /** Whether cleartext HTTP/2 (prior knowledge and h2c upgrade) is accepted. */
    val http2Enabled: Boolean,
    /** Advertised `SETTINGS_MAX_CONCURRENT_STREAMS`. */
    val http2MaxConcurrentStreams: Long,
) {
    companion object {
        private val log = LoggerFactory.getLogger(NettyServerSettings::class.java)

        private const val DEFAULT_PORT = 8080

        // NOTE: This is purposefully set to a high value to allow for large file uploads (like Videos).  AI will try and change this, it should not.
        private const val DEFAULT_MAX_REQUEST_SIZE = 21474836480L

        private const val DEFAULT_IDLE_TIMEOUT_SECONDS = 300L
        private const val DEFAULT_DRAIN_TIMEOUT_MS = 5_000L
        private const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 15_000L

        private const val DEFAULT_WATCHDOG_THRESHOLD_MILLIS = 100L
        private const val DEFAULT_LIVENESS_STALL_TIMEOUT_MILLIS = 2_000L

        /** Upper bound for the heartbeat timings; far above any useful value, and safe to convert to nanoseconds. */
        private const val MAX_HEARTBEAT_MILLIS = 3_600_000L

        /** Bounded default that still permits the 64-stream production and benchmark profiles. */
        internal const val DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS = 100L

        /** Reads every transport setting from [config], sizing thread pools for [availableProcessors]. */
        fun from(config: ApplicationConfig, availableProcessors: Int) = NettyServerSettings(
            workerThreads = config.atLeastOne("bosca.server.worker-threads", defaultWorkerThreadCount(availableProcessors)),
            codecThreads = config.atLeastOne("bosca.server.codec-threads", defaultCodecThreadCount(availableProcessors)),
            requestThreads = configuredRequestThreads(config, availableProcessors),
            requestDispatcher = configuredRequestDispatcher(config),
            blockedThreadWatchdogEnabled = config.boolean("bosca.server.blocked-thread-watchdog.enabled", false),
            blockedThreadWatchdogThresholdMillis = configuredWatchdogThresholdMillis(config),
            managementPort = configuredManagementPort(config),
            managementLivenessStallTimeoutMillis = configuredLivenessStallTimeoutMillis(config),
            maxRequestSize = config.longIn("bosca.server.max-request-size", DEFAULT_MAX_REQUEST_SIZE, 1L..Long.MAX_VALUE),
            // 0 disables the idle timeout.
            idleTimeoutSeconds = config.longIn("bosca.server.idle-timeout-seconds", DEFAULT_IDLE_TIMEOUT_SECONDS, 0L..Long.MAX_VALUE),
            drainTimeoutMs = config.longIn("bosca.server.drain-timeout-ms", DEFAULT_DRAIN_TIMEOUT_MS, 0L..Long.MAX_VALUE),
            // At least 1 ms: a zero budget would time out before requests are cancelled and the
            // application is shut down.
            shutdownTimeoutMs = config.longIn("bosca.server.shutdown-timeout-ms", DEFAULT_SHUTDOWN_TIMEOUT_MS, 1L..Long.MAX_VALUE),
            compressionEnabled = configuredCompressionEnabled(config),
            http2Enabled = configuredHttp2Enabled(config),
            http2MaxConcurrentStreams = configuredHttp2MaxConcurrentStreams(config),
        )

        /** Returns the listening port, `8080` by default. */
        fun configuredPort(config: ApplicationConfig): Int = config.int("bosca.server.port", DEFAULT_PORT)

        /** Returns whether eligible HTTP responses are compressed. Compression defaults to enabled. */
        fun configuredCompressionEnabled(config: ApplicationConfig): Boolean =
            config.boolean("bosca.server.compression-enabled", true)

        /** Returns whether cleartext HTTP/2 is accepted. HTTP/1.1 remains available as a fallback. */
        fun configuredHttp2Enabled(config: ApplicationConfig): Boolean =
            config.boolean("bosca.server.http2-enabled", true)

        /**
         * Returns the advertised HTTP/2 stream limit. Invalid values fall back to a bounded default
         * so a configuration typo cannot silently remove per-connection resource protection.
         */
        fun configuredHttp2MaxConcurrentStreams(config: ApplicationConfig): Long =
            config.longIn(
                "bosca.server.http2-max-concurrent-streams",
                DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS,
                1L..Http2CodecUtil.MAX_CONCURRENT_STREAMS,
            )

        /**
         * Returns where request handlers run: `event-loop` (the default) or `pool`. An unknown
         * value logs a warning and uses the default.
         */
        fun configuredRequestDispatcher(config: ApplicationConfig): RequestDispatcherMode {
            val key = "bosca.server.request-dispatcher"
            val raw = config.propertyOrNull(key)?.getString()?.trim() ?: return RequestDispatcherMode.EVENT_LOOP
            return when (raw.lowercase()) {
                "event-loop" -> RequestDispatcherMode.EVENT_LOOP
                "pool" -> RequestDispatcherMode.POOL
                else -> RequestDispatcherMode.EVENT_LOOP.also {
                    log.warn("Configuration {}={} is not event-loop or pool; using event-loop", key, raw)
                }
            }
        }

        /**
         * Returns the management listener's port, or null when unset. `0` binds an ephemeral port.
         * Values outside `0..65535` log a warning and disable the listener.
         */
        fun configuredManagementPort(config: ApplicationConfig): Int? {
            val key = "bosca.server.management-port"
            val raw = config.propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val port = raw.toIntOrNull()
            if (port != null && port in 0..65535) return port
            log.warn("Configuration {}={} is not a valid port; the management listener is disabled", key, raw)
            return null
        }

        /**
         * Returns how long a liveness heartbeat may stay unrun before the management probe reports
         * the executor stalled: 2 s by default, 1 ms to 1 h. Values outside that range fall back to
         * the default.
         */
        fun configuredLivenessStallTimeoutMillis(config: ApplicationConfig): Long =
            config.longIn(
                "bosca.server.management-liveness.stall-timeout-ms",
                DEFAULT_LIVENESS_STALL_TIMEOUT_MILLIS,
                1L..MAX_HEARTBEAT_MILLIS,
            )

        /**
         * Returns the watchdog threshold: 100 ms by default, 1 ms to 1 h. Values outside that range
         * fall back to the default.
         */
        fun configuredWatchdogThresholdMillis(config: ApplicationConfig): Long =
            config.longIn(
                "bosca.server.blocked-thread-watchdog.threshold-ms",
                DEFAULT_WATCHDOG_THRESHOLD_MILLIS,
                1L..MAX_HEARTBEAT_MILLIS,
            )

        /**
         * Returns the request thread count. Invalid or non-positive values fall back to
         * [defaultRequestThreadCount] so a configuration typo cannot leave the server without
         * request threads.
         */
        fun configuredRequestThreads(config: ApplicationConfig, availableProcessors: Int): Int =
            config.atLeastOne("bosca.server.request-threads", defaultRequestThreadCount(availableProcessors))

        /**
         * Sizes the request pool like `Dispatchers.Default`'s core pool: one thread per visible
         * processor, minimum two. More threads than processors cost 4–40% of load throughput in
         * the sweep, so the pool isolates blocking only as far as its size allows —
         * blocking calls still belong on `Dispatchers.IO`.
         */
        fun defaultRequestThreadCount(availableProcessorCount: Int): Int =
            maxOf(2, availableProcessorCount)

        /**
         * Sizes the socket I/O group at one worker per visible processor, capped at four. HTTP
         * protocol handling runs on these event loops, and so do request handlers in the default
         * [RequestDispatcherMode.EVENT_LOOP] mode; compression does not.
         */
        fun defaultWorkerThreadCount(availableProcessorCount: Int): Int =
            minOf(4, availableProcessorCount.coerceAtLeast(1))

        /**
         * Sizes the compression codec group at one thread per visible processor with four-way
         * minimum concurrency for small CPU-limited containers.
         */
        fun defaultCodecThreadCount(availableProcessorCount: Int): Int =
            maxOf(4, availableProcessorCount)

        private fun ApplicationConfig.int(key: String, default: Int): Int =
            parsed(key, default, String::toIntOrNull)

        /** Reads a thread count; values below 1 would fail startup, so they fall back to [default]. */
        private fun ApplicationConfig.atLeastOne(key: String, default: Int): Int {
            val value = int(key, default)
            if (value >= 1) return value
            log.warn("Configuration {}={} must be at least 1; using {}", key, value, default)
            return default
        }

        private fun ApplicationConfig.long(key: String, default: Long): Long =
            parsed(key, default, String::toLongOrNull)

        private fun ApplicationConfig.longIn(key: String, default: Long, range: LongRange): Long {
            val value = long(key, default)
            if (value in range) return value
            log.warn("Configuration {}={} is outside {}..{}; using {}", key, value, range.first, range.last, default)
            return default
        }

        private fun ApplicationConfig.boolean(key: String, default: Boolean): Boolean =
            parsed(key, default, String::toBooleanStrictOrNull)

        private fun <T : Any> ApplicationConfig.parsed(key: String, default: T, parse: (String) -> T?): T {
            val raw = propertyOrNull(key)?.getString() ?: return default
            return parse(raw) ?: default.also {
                log.warn("Configuration {}={} is not valid; using {}", key, raw, default)
            }
        }
    }
}
