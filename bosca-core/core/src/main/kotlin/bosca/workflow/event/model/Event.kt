package bosca.workflow.event.model

import bosca.serialization.UUID

/**
 * Represents an identifiable, versioned entity that can participate in workflow events.
 *
 * This is the base contract for content items (metadata, collections) whose state transitions
 * are tracked through the workflow event system.
 */
interface EventItem {
    /** The unique identifier of this item. */
    val id: UUID
    /** The version number of this item, or `null` if the item is unversioned. */
    val version: Int?
}
