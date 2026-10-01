package bosca.content.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Represents a named, directional relationship between two content entities.
 *
 * Content relationships link one entity ([id1]) to another ([id2]) with a
 * descriptive [relationship] type (e.g., "related", "parent", "translation")
 * and optional arbitrary [attributes] stored as JSON.
 */
interface ContentRelationship {

    /** The UUID of the source (origin) entity in the relationship. */
    val id1: UUID
    /** The UUID of the target (destination) entity in the relationship. */
    val id2: UUID
    /** The type or name of the relationship (e.g., "related", "parent"). */
    val relationship: String
    /** Optional JSON attributes providing additional metadata about this relationship. */
    val attributes: JsonElement?
}