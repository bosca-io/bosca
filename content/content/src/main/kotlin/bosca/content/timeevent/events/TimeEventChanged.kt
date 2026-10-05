package bosca.content.timeevent.events

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Pub/sub channel for real-time notifications when time events are added,
 * modified, or removed on a metadata item's timeline.
 */
const val TIME_EVENT_CHANGED_CHANNEL = "bosca.content.timeevent.changed"

/**
 * Notification emitted when one or more time events on a metadata item's timeline
 * have been created, updated, or deleted.
 *
 * Published to [TIME_EVENT_CHANGED_CHANNEL] by background job executors (such as
 * PDF timeline import) and mutation operations, allowing subscribed clients to
 * refresh their timeline views in real time.
 */
@Serializable
data class TimeEventChanged(
    @Contextual
    val metadataId: UUID,
    val metadataVersion: Int,
    val eventCount: Int,
)
