package bosca.analytics.repository

import bosca.analytics.model.Events
import bosca.analytics.service.ErrorGroupService

/**
 * Adapts the [ErrorGroupService] to the [EventRepository] interface so that
 * error group bookkeeping can participate in the composite event repository
 * pipeline without the consumer knowing about it.
 *
 * [process] delegates to [ErrorGroupService.recordBatch], which folds error
 * events into per-fingerprint aggregate upserts. [flush] is a no-op because
 * the underlying service writes to PostgreSQL immediately on each batch.
 */
class ErrorGroupEventRepository(
    private val errorGroupService: ErrorGroupService,
) : EventRepository {

    override suspend fun process(events: Events) {
        errorGroupService.recordBatch(events)
    }

    override suspend fun flush() {
        // PostgreSQL writes are immediate; nothing to flush.
    }
}
