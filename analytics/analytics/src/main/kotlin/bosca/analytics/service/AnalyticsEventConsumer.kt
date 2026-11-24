package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.analytics.queue.AnalyticsEventDelivery
import bosca.analytics.queue.AnalyticsEventQueue
import bosca.analytics.repository.EventRepository
import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.server.Headers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Processes claimed analytics deliveries independently of their transport. */
class AnalyticsEventConsumer(
    private val queue: AnalyticsEventQueue,
    private val eventRepository: EventRepository,
    private val eventTransforms: EventPipelineTransforms,
    private val config: EventProcessingConfiguration,
) {
    /** Runs bounded workers with backpressure, renewing ownership until durable storage completes. */
    suspend fun run(): Unit = coroutineScope {
        require(config.workerCount > 0 && config.channelCapacity >= 0 && config.natsBatchSize > 0)
        launch {
            while (isActive) {
                delay(60_000.milliseconds)
                try {
                    log.info("Analytics queue status: {}", queue.status())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Cannot read analytics queue status", e)
                }
            }
        }
        repeat(config.workerCount) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val deliveries = queue.receiveBatch(config.natsBatchSize, config.natsFetchTimeoutSeconds.seconds)
                        if (deliveries.isNotEmpty()) processDeliveries(deliveries)
                    } catch (e: CancellationException) {
                        this@coroutineScope.cancel("Analytics consumer cancelled", e)
                        throw e
                    } catch (e: Exception) {
                        log.error("Failed to receive analytics delivery", e)
                        delay(1000.milliseconds)
                    }
                }
            }
        }
    }

    internal suspend fun processDelivery(delivery: AnalyticsEventDelivery) = processDeliveries(listOf(delivery))

    internal suspend fun processDeliveries(deliveries: List<AnalyticsEventDelivery>) {
        if (deliveries.isEmpty()) return
        val successful = mutableListOf<AnalyticsEventDelivery>()
        val failed = mutableListOf<AnalyticsEventDelivery>()
        try {
            coroutineScope {
                val renewal = launch {
                    while (isActive) {
                        delay(30_000.milliseconds)
                        deliveries.forEach { it.renew() }
                    }
                }
                try {
                    withRequestCache {
                        withConnectionManager {
                            val stored = mutableListOf<Events>()
                            for (delivery in deliveries) {
                                val batches = try {
                                    delivery.batches.map { batch ->
                                        var events = batch
                                        val context = EventPipelineContext(Headers.Empty)
                                        for (transform in eventTransforms.transforms) events = transform.transform(context, events)
                                        events
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    log.error("Analytics delivery transform failed; scheduling retry", e)
                                    failed.add(delivery)
                                    continue
                                }
                                stored += batches
                                successful.add(delivery)
                            }
                            // One commit per group: a failure leaves nothing staged, so redelivery is the only retry.
                            if (successful.isNotEmpty()) eventRepository.store(stored)
                        }
                    }
                } finally {
                    renewal.cancelAndJoin()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Analytics storage failed; scheduling retry", e)
            queue.retryBatch(deliveries, 5.seconds)
            return
        }
        if (successful.isNotEmpty()) queue.acknowledgeBatch(successful)
        if (failed.isNotEmpty()) queue.retryBatch(failed, 5.seconds)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AnalyticsEventConsumer::class.java)
    }
}
