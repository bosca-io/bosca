package bosca.analytics.repository

import bosca.analytics.model.Events
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Delegates [process] and [flush] calls to every repository in [repositories],
 * in order. Each sink is isolated: a failure in one sink is logged and does not
 * prevent subsequent sinks from running. This allows the event pipeline to write
 * to multiple sinks (e.g., Iceberg storage and error group aggregation) through
 * a single [EventRepository] reference, keeping the consumer decoupled from
 * individual sink concerns.
 */
class CompositeEventRepository(
    private val repositories: List<EventRepository>,
) : EventRepository {

    override suspend fun process(events: Events) {
        for (repository in repositories) {
            try {
                repository.process(events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Sink {} failed to process batch of {} events",
                    repository::class.simpleName, events.events.size, e)
            }
        }
    }

    override suspend fun flush() {
        for (repository in repositories) {
            try {
                repository.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Sink {} failed to flush", repository::class.simpleName, e)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CompositeEventRepository::class.java)
    }
}
