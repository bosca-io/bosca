package bosca.calendar.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A concrete instance of an event in a date range, ready to render on the
 * calendar grid. Combines a stored [CalendarEvent] row with the resolved
 * timestamps for this specific instance.
 *
 *  * For a single event the occurrence's [startsAt]/[endsAt] match the row's.
 *  * For an expanded master the occurrence's times come from the recurrence
 *    rule and [recurrenceId] equals [startsAt].
 *  * For an override the occurrence's times come from the override row, while
 *    [recurrenceId] preserves the original master-defined start so the UI can
 *    show "moved from {recurrenceId}" if desired.
 */
@Serializable
data class EventOccurrence(
    val event: CalendarEvent,
    @Contextual
    val startsAt: OffsetDateTime,
    @Contextual
    val endsAt: OffsetDateTime,
    /** Master start time this instance corresponds to; null on non-recurring singles. */
    @Contextual
    val recurrenceId: OffsetDateTime? = null,
    /** True when the underlying row is a master being expanded (or an override of one). */
    val isRecurring: Boolean = false,
    /** True when the underlying row is an override (not a passive expansion of the master). */
    val isException: Boolean = false
) {
    /** Convenience: the master event id. For overrides this is `event.originalEventId`. */
    val masterEventId: UUID? get() = event.originalEventId ?: event.id.takeIf { event.rrule != null }
}
