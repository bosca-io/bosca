package bosca.analytics.delivery

import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventFactory
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.AnalyticsChildScope
import bosca.analytics.api.Events
import bosca.analytics.api.Geo
import bosca.analytics.persistence.AnalyticsEventStore
import bosca.analytics.persistence.EventQueue
import bosca.analytics.platform.currentMicros
import bosca.core.analytics.InstallationIdProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Bosca collector sink with sessions, batching, retry, and durable offline storage. */
class BoscaSink(
    private val config: BoscaSinkConfig,
    client: HttpClient,
    eventStore: AnalyticsEventStore,
    private val installationProvider: InstallationIdProvider,
    scope: CoroutineScope,
    eventFactory: AnalyticsEventFactory,
    private val logger: AnalyticsLogger,
    private val userIdProvider: AnalyticsUserIdProvider? = null,
) : AnalyticsEventSink() {
    private val ownedScope = AnalyticsChildScope(scope)
    private val scope = ownedScope.scope
    private val sender = BoscaEventSender(config, client)
    private val queue = EventQueue(eventStore, config.flushBatchSize)
    private val flushMutex = Mutex()
    private val context = AnalyticsContextState(config)
    private val flushScheduler = AnalyticsFlushScheduler(scope, logger, ::flush)
    private val sessionState = createBoscaSessionState(scope, config, context, eventFactory, logger, ::add)
    private var retryDelay = 3.seconds
    private var flushedCount = 0L
    private var failureCount = 0L

    val flushed: Long
        get() = flushedCount

    val failures: Long
        get() = failureCount

    init {
        if (config.sessionTracking) sessionState.start()
        if (config.autoFlush) {
            scope.launchHandled("offline event flush") {
                if (queue.size() > 0) scheduleFlush(Duration.ZERO)
            }
        }
    }

    /** Returns the stable installation ID, registering it on first use. */
    suspend fun installationId(): String = installationProvider.getOrCreate()

    /** Returns the current analytics session identifier without starting a new session. */
    fun sessionId(): String = context.sessionId()

    fun setUserId(userId: String?) {
        context.setUserId(userId)
    }

    fun setGeo(geo: Geo) {
        context.setGeo(geo)
    }

    suspend fun pendingSize(): Int = queue.size()

    suspend fun pause() = sessionState.pause()

    suspend fun resume() = sessionState.resume()

    override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
        if (config.sessionTracking) scope.launchHandled("session event") { sessionState.onEvent() }
        val deliveryEvent = event.copy(
            error = event.error?.copy(stackTrace = event.error.stackTrace?.take(MAX_STACK_TRACE_LENGTH)),
        )
        if (config.debug) {
            logger.log(
                "[bosca-analytics] event ${deliveryEvent.type.wireName} " +
                    "${deliveryEvent.element.type} ${deliveryEvent.element.id}",
                null,
            )
        }
        val snapshot = userIdProvider?.let { context.snapshot(it.currentUserId()) } ?: context.snapshot()
        queue.add(snapshot.id, snapshot.value, deliveryEvent)
        if (config.autoFlush) {
            val delay = if (event.type == AnalyticsEventType.ERROR) Duration.ZERO else config.flushDelay
            scheduleFlush(delay)
        }
    }

    /** Flushes the current backlog in bounded batches. Failed groups remain queued for retry. */
    override suspend fun flush() {
        flushMutex.withLock {
            val initializedContext = initializeContext()
            var remaining = queue.size()
            var hasMore = remaining > 0
            var hadErrors = false
            try {
                while (remaining > 0) {
                    val pending = queue.get() ?: return
                    var batchFailed = false
                    try {
                        pending.groups.forEach { group ->
                            val groupContext = if (group.context.device.installationId.isBlank()) {
                                group.context.copy(device = initializedContext.device)
                            } else {
                                group.context
                            }
                            try {
                                sender.send(Events(groupContext, nowMillis(), currentMicros(), group.events))
                                pending.finish(group)
                                flushedCount += group.events.size
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                batchFailed = true
                                failureCount += group.events.size
                                logger.log("[bosca-analytics] failed to flush ${group.events.size} events", error)
                            }
                        }
                    } finally {
                        hasMore = pending.close()
                    }
                    remaining -= pending.eventCount
                    if (batchFailed) {
                        hadErrors = true
                        break
                    }
                }
            } finally {
                scheduleNextFlush(hasMore, hadErrors)
            }
        }
    }

    /** Flushes once and stops delivery and session jobs without closing injected dependencies. */
    override suspend fun close() {
        flushScheduler.cancel()
        try {
            flush()
        } finally {
            try {
                sessionState.end()
            } finally {
                ownedScope.close()
            }
        }
    }

    private suspend fun initializeContext() = context.initialize(installationProvider)

    private suspend fun scheduleNextFlush(changed: Boolean, hadErrors: Boolean) {
        if (!config.autoFlush) return
        when {
            hadErrors -> {
                retryDelay = minOf(retryDelay * 2, 60.seconds)
                flushScheduler.schedule(retryDelay)
            }
            changed -> flushScheduler.schedule(config.flushDelay)
            else -> retryDelay = 3.seconds
        }
    }

    private suspend fun scheduleFlush(delay: Duration) {
        flushScheduler.schedule(delay)
    }

    private fun CoroutineScope.launchHandled(label: String, block: suspend CoroutineScope.() -> Unit): Job = launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.log("[bosca-analytics] $label failed", error)
        }
    }

    private companion object {
        const val MAX_STACK_TRACE_LENGTH = 8192

        fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
