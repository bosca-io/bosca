package bosca.analytics.server

import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException

/**
 * HTTP-backed [ServerAnalyticsClient] for JVM services that are not
 * co-located with the analytics ingestion pipeline.
 *
 * Posts batches to `${baseUrl}/api/v1/events`. Events are buffered in
 * a bounded in-memory queue and flushed either when the buffer reaches
 * [maxBatchSize] or every [flushIntervalMs] milliseconds, whichever
 * happens first. Failures are retried with exponential backoff up to
 * [maxRetries] times before the batch is dropped.
 *
 * Like the in-process client this class never throws on capture: any
 * unhandled error from OkHttp or the JSON encoder is logged and the
 * batch is discarded so analytics emission cannot crash the caller.
 * [CancellationException] is always re-thrown so cooperative
 * cancellation (shutdown, test cleanup) propagates.
 *
 * Call [close] (or use as an `AutoCloseable`) during graceful shutdown
 * to drain the buffer and stop the background flusher. Failing to call
 * [close] leaks the flusher coroutine until the JVM exits.
 */
class HttpServerAnalyticsClient(
    baseUrl: String,
    private val appId: String? = null,
    private val apiKey: String? = null,
    private val maxBatchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val maxBufferedEvents: Int = DEFAULT_MAX_BUFFERED_EVENTS,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson,
    private val sleeper: suspend (Long) -> Unit = ::delay,
) : ServerAnalyticsClient, AutoCloseable {

    private val endpoint: String = baseUrl.trimEnd('/') + "/api/v1/events"

    private val mutex = Mutex()
    private val pending = ArrayDeque<Event>()
    private val droppedCount = AtomicLong()
    private val closed = AtomicBoolean(false)

    // Validate before allocating the coroutine scope so a failed
    // require() cannot leak a SupervisorJob.
    init {
        require(maxBatchSize > 0) { "maxBatchSize must be > 0" }
        require(flushIntervalMs > 0) { "flushIntervalMs must be > 0" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(maxBufferedEvents > 0) { "maxBufferedEvents must be > 0" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flusherJob: Job = scope.launch {
        while (true) {
            try {
                sleeper(flushIntervalMs)
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error("scheduled flush failed", e)
            }
        }
    }

    override suspend fun capture(event: Event) {
        if (closed.get()) {
            droppedCount.incrementAndGet()
            log.debug("analytics client closed; dropping event created={}", event.created)
            return
        }
        val toFlush: List<Event>? = mutex.withLock {
            if (pending.size >= maxBufferedEvents) {
                // Buffer is full. Drop the oldest so the most recent
                // signal survives — operators care about the latest
                // errors more than the oldest.
                val dropped = pending.removeFirst()
                droppedCount.incrementAndGet()
                log.warn("analytics buffer full; dropped oldest event created={}", dropped.created)
            }
            pending.addLast(event)
            if (pending.size >= maxBatchSize) drainLocked() else null
        }
        if (toFlush != null) sendBatch(toFlush)
    }

    override suspend fun captureForSubject(
        event: Event,
        userId: String?,
        installationId: String?,
        device: Device?,
    ) {
        if (closed.get()) {
            droppedCount.incrementAndGet()
            log.debug("analytics client closed; dropping subject event created={}", event.created)
            return
        }
        val configuredAppId = appId
        if (configuredAppId == null) {
            droppedCount.incrementAndGet()
            log.error("analytics app id is required for subject events; dropping event created={}", event.created)
            return
        }
        sendEnvelope(
            Events(
                context = ServerEventContext.build(
                    appId = configuredAppId,
                    userId = userId,
                    installationId = installationId,
                    device = device,
                ),
                events = listOf(event),
                sent = System.currentTimeMillis(),
                sentMicros = SentMicros.next(),
            )
        )
    }

    override suspend fun capture(events: Events) {
        // For pre-built batches we send directly without buffering;
        // the caller has already decided on a batch boundary.
        sendEnvelope(events)
    }

    override suspend fun captureException(
        throwable: Throwable,
        fatal: Boolean,
        appId: String?,
        sessionId: String?,
        userId: String?,
        context: Map<String, Any?>,
    ) {
        val ambientContext = AnalyticsErrorContextResolver.resolveAmbient(analyticsContext().toMap(), context)
        val merged: Map<String, Any?> = if (appId == null && sessionId == null && userId == null) {
            ambientContext
        } else {
            val out = LinkedHashMap<String, Any?>(ambientContext.size + 3)
            out.putAll(ambientContext)
            if (appId != null) out["app_id"] = appId
            if (sessionId != null) out["session_id"] = sessionId
            if (userId != null) out["user_id"] = userId
            out
        }
        capture(ThrowableErrorInfoMapper.toEvent(throwable, fatal, context = merged))
    }

    override suspend fun flush() {
        val toFlush: List<Event> = mutex.withLock { drainLocked() }
        if (toFlush.isNotEmpty()) sendBatch(toFlush)
    }

    /**
     * Tears down the background flusher and drains any remaining
     * events via a final synchronous flush. Implements [AutoCloseable]
     * so callers can use try-with-resources / `use { }`.
     *
     * After [close] returns, subsequent [capture] calls are rejected
     * and the events are counted as dropped.
     */
    override fun close() {
        closed.set(true)
        flusherJob.cancel()
        runBlocking { flush() }
        val dropped = droppedCount.get()
        if (dropped > 0) {
            log.warn("analytics client closed with {} total dropped events", dropped)
        }
        scope.cancel()
    }

    /** Number of events dropped (buffer overflow or exhausted retries). */
    val droppedEvents: Long get() = droppedCount.get()

    private fun drainLocked(): List<Event> {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList<Event>(pending.size)
        out.addAll(pending)
        pending.clear()
        return out
    }

    private suspend fun sendBatch(events: List<Event>) {
        val envelope = Events(
            context = appId?.let { ServerEventContext.build(it) },
            events = events,
            sent = System.currentTimeMillis(),
            sentMicros = SentMicros.next(),
        )
        sendEnvelope(envelope)
    }

    private suspend fun sendEnvelope(envelope: Events) {
        val body = try {
            json.encodeToString(Events.serializer(), envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("failed to serialize analytics events; dropping batch of {} events", envelope.events.size, e)
            droppedCount.addAndGet(envelope.events.size.toLong())
            return
        }

        var attempt = 0
        while (true) {
            try {
                val request = Request.Builder().apply {
                    url(endpoint)
                    post(body.toRequestBody(JSON_MEDIA_TYPE))
                    if (apiKey != null) header("Authorization", "Bearer $apiKey")
                    envelope.context?.let { context ->
                        header("X-App-ID", context.appId)
                        header("X-App-Version", context.appVersion)
                        header("X-Installation-ID", context.device.installationId)
                        header("X-BA-Session-ID", context.sessionId)
                    }
                }.build()
                val response = httpClient.newCall(request).executeAsync()
                response.use {
                    if (it.isSuccessful) return
                    val status = it.code
                    if (status in 400..499 && status != 408 && status != 429) {
                        // Permanent client error — no point retrying.
                        log.error(
                            "analytics ingestion rejected batch with HTTP {}: dropping {} events",
                            status, envelope.events.size,
                        )
                        droppedCount.addAndGet(envelope.events.size.toLong())
                        return
                    }
                    throw IOException("ingestion HTTP $status")
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation must never be caught and
                // retried; it means shutdown or client disconnect.
                throw e
            } catch (e: Throwable) {
                attempt += 1
                if (attempt > maxRetries) {
                    log.error(
                        "analytics ingestion failed after {} attempts; dropping {} events",
                        attempt, envelope.events.size, e,
                    )
                    droppedCount.addAndGet(envelope.events.size.toLong())
                    return
                }
                val baseBackoff = (1L shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)) * BASE_BACKOFF_MS
                val backoff = (baseBackoff * (0.5 + Math.random() * 0.5)).toLong()
                log.warn(
                    "analytics ingestion attempt {} failed; retrying in {}ms: {}",
                    attempt, backoff, e.message,
                )
                sleeper(backoff)
            }
        }
    }

    /**
     * Suspending wrapper around OkHttp's [Call.enqueue], matching the
     * pattern used by [bosca.http.Client.executeAsync]. Replaces the
     * blocking [Call.execute] so we don't park a `Dispatchers.IO`
     * thread for the duration of the round trip and so that
     * cancellation actually cancels the in-flight request.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.executeAsync(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            runCatching { cancel() }
        }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, _, _ -> response.closeQuietly() }
                }
            },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(HttpServerAnalyticsClient::class.java)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_BATCH_SIZE: Int = 50
        const val DEFAULT_FLUSH_INTERVAL_MS: Long = 5_000L
        const val DEFAULT_MAX_RETRIES: Int = 3
        const val DEFAULT_MAX_BUFFERED_EVENTS: Int = 10_000
        private const val BASE_BACKOFF_MS = 200L
        private const val MAX_BACKOFF_SHIFT = 6 // cap at 64 * BASE_BACKOFF_MS

        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build()
    }
}
