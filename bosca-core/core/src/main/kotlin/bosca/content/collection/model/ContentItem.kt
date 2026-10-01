package bosca.content.collection.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workflow.event.model.EventItem
import kotlinx.serialization.json.JsonElement

/**
 * Represents a content entity (metadata or collection) that participates in the
 * workflow system and carries user-defined attributes.
 *
 * Extends [EventItem] to provide identity and versioning for workflow event tracking.
 * This interface is the shared contract between metadata items and collections,
 * unifying their workflow state and attribute access.
 */
interface ContentItem : EventItem {

    /** The unique identifier of this content item. */
    override val id: UUID

    /** The version number of this content item, or `null` if unversioned. */
    override val version: Int?

    /** The BCP-47 language tag for this content item, or `null` if language-independent. */
    val languageTag: String?

    /** User-defined JSON attributes associated with this content item. */
    val attributes: JsonElement?

    /** Additional JSON attributes specific to this item's position within a collection. */
    val itemAttributes: JsonElement?

    /** The current workflow state identifier (e.g., "pending", "published"). */
    val workflowStateId: String

    /** The workflow state this item is transitioning to, or `null` if no transition is pending. */
    val workflowStatePendingId: String?

    /** The timestamp when this content item was marked as ready, or `null` if not yet ready. */
    val ready: OffsetDateTime?
}