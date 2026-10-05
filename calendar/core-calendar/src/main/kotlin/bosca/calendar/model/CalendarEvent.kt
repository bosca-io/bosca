package bosca.calendar.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A stored calendar event row. The same shape represents three iCalendar
 * concepts:
 *
 *  * **Single event** — [rrule] and [originalEventId] are both null. Renders
 *    once at [startsAt].
 *  * **Recurring master** — [rrule] is non-null. Generates multiple occurrences
 *    according to RFC 5545 expansion rules; [startsAt]/[endsAt] are the
 *    template (first occurrence). [exdates] is a JSON array of ISO-8601
 *    timestamps to skip during expansion.
 *  * **Override** — [originalEventId] points to the master and [recurrenceId]
 *    is the original start time of the occurrence this row replaces. The
 *    override has its own [startsAt]/[endsAt] which may differ from the
 *    [recurrenceId], and edits, descriptions, etc. apply only to that one
 *    occurrence.
 *
 * The (metadataId, version) pair identifies the parent calendar and that
 * metadata's permissions govern who can read or mutate this row.
 */
@Serializable
data class CalendarEvent(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val title: String,
    val description: String = "",
    val location: String = "",
    @ColumnName("all_day")
    val allDay: Boolean = false,
    @ColumnName("starts_at")
    @Contextual
    val startsAt: OffsetDateTime,
    @ColumnName("ends_at")
    @Contextual
    val endsAt: OffsetDateTime,
    /**
     * Bare RRULE body without the `RRULE:` prefix, e.g.
     * `FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10`. Null on single events and overrides.
     */
    val rrule: String? = null,
    /**
     * JSON array of ISO-8601 timestamps to exclude from rrule expansion.
     * Only meaningful on masters; null on singles and overrides.
     */
    @Contextual
    val exdates: JsonElement? = null,
    /** Master event whose occurrence this row overrides; null on masters and singles. */
    @ColumnName("original_event_id")
    @Contextual
    val originalEventId: UUID? = null,
    /** Original start time of the occurrence being overridden; paired with [originalEventId]. */
    @ColumnName("recurrence_id")
    @Contextual
    val recurrenceId: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
)
