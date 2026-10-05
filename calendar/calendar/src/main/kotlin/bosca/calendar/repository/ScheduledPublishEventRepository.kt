package bosca.calendar.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime

@Repository
interface ScheduledPublishEventRepository {

    /**
     * Returns metadata items with deferred workflow transitions to the
     * "published" or "advertised" state whose scheduled time falls within
     * the requested range. Pending transitions are identified via the
     * metadata's current `workflow_state_pending_id`; completed transitions
     * are identified by checking the item's resulting `workflow_state_id`.
     * Multiple job history entries for the same metadata at the same
     * scheduled minute are collapsed into one event, preferring the most
     * recently created entry.
     */
    @Query("""
        select distinct on (m.id, date_trunc('minute', h.delayed_until))
            m.id as id,
            m.name as title,
            coalesce(m.workflow_state_pending_id, '') as description,
            h.delayed_until as startsAt,
            h.delayed_until + interval '15 minutes' as endsAt,
            h.complete is not null as completed
        from metadata m
        join metadata_job_history h on h.id = m.id and h.version = m.version
        where h.delayed_until is not null
          and h.delayed_until >= :from
          and h.delayed_until < :to
          and (
            (h.complete is null and m.workflow_state_pending_id in ('published', 'advertised'))
            or
            (h.complete is not null and m.workflow_state_id in ('published', 'advertised'))
          )
        order by m.id, date_trunc('minute', h.delayed_until), h.created desc
    """)
    suspend fun getInRange(from: OffsetDateTime, to: OffsetDateTime): List<SyntheticEvent>
}
