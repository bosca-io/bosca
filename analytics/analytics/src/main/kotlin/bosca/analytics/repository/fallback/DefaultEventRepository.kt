package bosca.analytics.repository.fallback

import bosca.analytics.model.Events
import bosca.analytics.repository.EventRepository
import org.slf4j.LoggerFactory

/**
 * Default in-memory event repository for development and testing.
 * This is a fallback implementation when no specific analytics storage is configured.
 */
class DefaultEventRepository : EventRepository {

    private val logger = LoggerFactory.getLogger(DefaultEventRepository::class.java)

    override suspend fun process(events: Events) {
        logger.debug("Default EventRepository: saving ${events.events.size} events (events will not be persisted)")

        // For the default implementation, we just log and return the events
        // without actually persisting them anywhere
        events.events.forEach { event ->
            logger.trace("Event: {} - {} from client {}", event.type, event.element, event.clientId)
        }
    }

    override suspend fun flush() {

    }
}