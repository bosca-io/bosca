package bosca.communications.service

import bosca.communications.model.Message
import bosca.serialization.UUID
import bosca.service.Service

/** Persists one idempotent outbox row per recipient profile and delivery channel. */
interface MessageOutboxService : Service {

    /**
     * Persists and schedules [message] once for [sourceId], splitting it into one durable row for
     * each recipient profile and channel. Repeated calls recover pending rows from that first
     * persisted fan-out; sent and terminally failed rows remain closed.
     */
    suspend fun enqueueOnce(sourceId: UUID, message: Message)

    /** Delivers a pending outbox row, or returns when it was already sent or terminally failed. */
    suspend fun deliver(outboxId: UUID)

    /** Republishes pending rows, excluding sent and terminally failed deliveries. */
    suspend fun recoverPending(limit: Int = 200): Int
}
