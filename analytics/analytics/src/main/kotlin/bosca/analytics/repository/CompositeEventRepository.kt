package bosca.analytics.repository

import bosca.analytics.model.Events
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Writes to [primary] before optional [repositories]. Primary storage failures propagate
 * so callers cannot acknowledge unstored events. Additional sink failures are logged
 * independently and do not turn successful storage into a failed delivery.
 */
class CompositeEventRepository(
    private val primary: EventRepository,
    private val repositories: List<EventRepository>,
) : EventRepository {

    override suspend fun process(events: Events) {
        primary.process(events)
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
        primary.flush()
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
