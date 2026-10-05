package bosca.calendar.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime

@Repository
interface ScheduledContentEventRepository {

    /**
     * Returns campaigns whose scheduled or active window overlaps the half-open
     * range `[from, to)`. Campaigns without a scheduled time are excluded; when
     * a campaign has no end timestamp it is treated as a one-hour event so it
     * still renders on the calendar.
     */
    @Query("""
        select
            c.id as id,
            c.name as title,
            coalesce(c.channel::text, '') as description,
            c.scheduled_at as startsAt,
            coalesce(c.ended_at, c.scheduled_at + interval '1 hour') as endsAt,
            c.ended_at is not null as completed
        from segmentation.campaigns c
        where c.scheduled_at is not null
          and c.scheduled_at < :to
          and coalesce(c.ended_at, c.scheduled_at + interval '1 hour') >= :from
        order by c.scheduled_at asc
    """)
    suspend fun getInRange(from: OffsetDateTime, to: OffsetDateTime): List<SyntheticEvent>
}
