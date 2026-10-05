package bosca.calendar.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input describing a single-occurrence override on a recurring master. Used by
 * "edit this occurrence only" — the supplied fields replace the master's
 * defaults for the occurrence whose original start matches `recurrenceId`.
 */
@Serializable
data class OccurrenceInput(
    val title: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    @Contextual
    val startsAt: OffsetDateTime,
    @Contextual
    val endsAt: OffsetDateTime
)
