package bosca.calendar.repository

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A read-only event projected from another module. These do not live in
 * the calendar database; they are constructed on demand by querying the source
 * module's tables (e.g. `segmentation.campaigns`, `scheduler.scheduled_jobs`).
 */
@Serializable
data class SyntheticEvent(
    @Contextual
    val id: UUID,
    val title: String,
    val description: String = "",
    @Contextual
    val startsAt: OffsetDateTime,
    @Contextual
    val endsAt: OffsetDateTime,
    val completed: Boolean = false,
)
