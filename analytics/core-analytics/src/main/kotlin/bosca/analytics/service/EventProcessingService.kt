package bosca.analytics.service

import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.service.Service

/**
 * Service responsible for ingesting and buffering analytics events for downstream processing.
 *
 * Events are accepted along with their pipeline context (which carries HTTP request metadata
 * such as headers) and queued for batch processing. Calling [flush] forces any buffered
 * events to be written to the underlying event store.
 */
interface EventProcessingService : Service {

    /**
     * Enqueues a batch of analytics events for asynchronous processing.
     *
     * @param context the pipeline context carrying request-scoped metadata (e.g., HTTP headers)
     *   that may be used by downstream transforms for enrichment
     * @param events the batch of events to queue, including timestamps and an optional event context
     */
    suspend fun queue(context: EventPipelineContext, events: Events)

    /**
     * Forces all currently buffered events to be written to the underlying event store.
     * This ensures that any events accepted via [queue] but not yet persisted are flushed immediately.
     */
    suspend fun flush()
}