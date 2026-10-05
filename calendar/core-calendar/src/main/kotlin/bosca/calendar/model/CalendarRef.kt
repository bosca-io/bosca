package bosca.calendar.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Identifies a specific calendar by its parent metadata id and version.
 * Mirrors the GraphQL `CalendarRef` input type and is used wherever an
 * operation needs to point at a calendar without passing the full Calendar
 * row (e.g. mutations that target a calendar by reference and queries that
 * union events across multiple calendars).
 */
@Serializable
data class CalendarRef(
    @Contextual
    val metadataId: UUID,
    val version: Int
)
