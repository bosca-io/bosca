package bosca.analytics.service

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

@ServiceImplementation
class EventProcessingServiceImpl(
    application: BoscaApplication,
    private val eventRepository: EventRepository,
    private val eventTransforms: EventPipelineTransforms,
    configuration: EventProcessingConfiguration
) : EventProcessingService {
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val channel = Channel<EventProcessingRequest>(capacity = configuration.channelCapacity)

    private val workers: List<Job>

    init {
        workers = List(configuration.workerCount) {
            processingScope.launch {
                for (request in channel) {
                    processRequest(request.context, request.events)
                }
            }
        }
        application.onShutdown {
            log.info("Draining analytics event channel...")
            channel.close()
            workers.joinAll()
            eventRepository.flush()
            log.info("Analytics event channel drained")
        }
    }

    override suspend fun queue(context: EventPipelineContext, events: Events) {
        val now = Instant.now()
        val request = EventProcessingRequest(
            context,
            events.copy(
                received = now.toEpochMilli(),
                receivedMicros = ((now.nano / 1000) % 1000).toLong()
            )
        )
        channel.send(request)
    }

    override suspend fun flush() {
        eventRepository.flush()
    }

    internal suspend fun processRequest(context: EventPipelineContext, events: Events) = withRequestCache {
        withConnectionManager {
            var transformedEvents = events
            for (transform in eventTransforms.transforms) {
                try {
                    transformedEvents = transform.transform(context, transformedEvents)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to transform events: {}", e.message, e)
                }
            }
            eventRepository.process(transformedEvents)
        }
    }

    private data class EventProcessingRequest(
        val context: EventPipelineContext,
        val events: Events
    )

    companion object {
        private val log = LoggerFactory.getLogger(EventProcessingService::class.java)
    }
}
