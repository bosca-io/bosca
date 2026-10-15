package bosca.analytics.repository

import bosca.analytics.model.Events
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Stores events in [storageRepository] (NATS or Iceberg), then runs [optionalRepositories],
 * such as error-group aggregation.
 *
 * Storage failures must reach the caller so HTTP clients retain their events or the NATS
 * consumer requests redelivery. Optional repository failures are logged because the events
 * are already stored; failing delivery at that point would ask the caller to send them again.
 */
class CompositeEventRepository(
    private val storageRepository: EventRepository,
    private val optionalRepositories: List<EventRepository>,
) : EventRepository {

    override suspend fun process(events: Events) {
        // Keep storage outside the catch below: a failed write must fail the delivery.
        storageRepository.process(events)
        for (optionalRepository in optionalRepositories) {
            try {
                optionalRepository.process(events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Optional repository {} failed to process batch of {} events",
                    optionalRepository::class.simpleName, events.events.size, e)
            }
        }
    }

    override suspend fun flush() {
        // Buffered storage writes must succeed too; optional aggregation cannot hide a failure.
        storageRepository.flush()
        for (optionalRepository in optionalRepositories) {
            try {
                optionalRepository.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Optional repository {} failed to flush", optionalRepository::class.simpleName, e)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CompositeEventRepository::class.java)
    }
}
