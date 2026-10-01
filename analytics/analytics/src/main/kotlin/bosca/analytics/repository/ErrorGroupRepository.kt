package bosca.analytics.repository

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Persists per-fingerprint error group aggregates in Postgres.
 *
 * The pipeline calls [recordOccurrence] once per fingerprint per
 * ingestion batch (not per event), passing the per-batch occurrence
 * count so a single upsert covers many events. The `on conflict`
 * clause:
 *
 * - Increments `event_count` by the per-batch count.
 * - Advances `last_seen` / rewinds `first_seen` via `greatest` / `least`.
 * - Always refreshes `message`, `sample_event_id`, and `sample_stack`
 *   with the latest batch's representative event — this keeps the UI's
 *   stack trace pane current as the group evolves, and matches the
 *   caller's in-memory aggregation in [ErrorGroupServiceImpl] which
 *   already selects the newest event in the batch.
 * - Flips a previously [ErrorGroupStatus.RESOLVED] group back to
 *   [ErrorGroupStatus.OPEN] on regression. An [ErrorGroupStatus.IGNORED]
 *   group stays ignored even when new occurrences arrive — that's the
 *   whole point of IGNORED.
 *
 * The `:status` and `:fatal` filters on [list] and [count] are
 * repeated in both the null check and the equality comparison. The
 * bosca KSP query processor supports repeated named parameters and
 * binds the same value at each occurrence.
 */
@Repository
interface ErrorGroupRepository {

    /** Fetches a single error group by its fingerprint, or null if none
     *  exists. */
    @Query("select * from error_groups where fingerprint = :fingerprint")
    suspend fun getByFingerprint(fingerprint: String): ErrorGroup?

    /**
     * Returns a page of error groups matching the given filters,
     * ordered by `last_seen` descending. All filter parameters are
     * optional — passing null disables that filter entirely.
     *
     * @param appId restrict to groups belonging to a single app id
     * @param status restrict to a specific lifecycle status
     * @param fatal true for fatal-only, false for non-fatal-only,
     *     null for "any"
     * @param search case-insensitive substring match against the
     *     group's type or latest message. Callers must pre-escape
     *     LIKE wildcards (`%`, `_`, `\`) via
     *     [ErrorGroupServiceImpl.escapeLikePattern][bosca.analytics.service.ErrorGroupServiceImpl.Companion.escapeLikePattern]
     *     before passing the value — the SQL uses `escape '\'` and
     *     expects the backslash escaping convention.
     */
    @Query(
        """
        select * from error_groups
         where (:appId::text is null or app_id = :appId)
           and (:status::error_group_status is null or status = (:status)::error_group_status)
           and (:fatal::boolean is null or fatal = :fatal)
           and (:search::text is null or message ilike '%' || :search || '%' escape '\' or type ilike '%' || :search || '%' escape '\')
         order by last_seen desc
         offset :offset limit :limit
        """
    )
    suspend fun list(
        appId: String?,
        status: ErrorGroupStatus?,
        fatal: Boolean?,
        search: String?,
        offset: Long,
        limit: Int,
    ): List<ErrorGroup>

    /** Counts error groups matching the same filters used by [list].
     *  The [search] parameter must be pre-escaped — see [list] KDoc. */
    @Query(
        """
        select count(*) from error_groups
         where (:appId::text is null or app_id = :appId)
           and (:status::error_group_status is null or status = (:status)::error_group_status)
           and (:fatal::boolean is null or fatal = :fatal)
           and (:search::text is null or message ilike '%' || :search || '%' escape '\' or type ilike '%' || :search || '%' escape '\')
        """
    )
    suspend fun count(
        appId: String?,
        status: ErrorGroupStatus?,
        fatal: Boolean?,
        search: String?,
    ): Long

    /**
     * Upserts a single fingerprint's batch occurrence into the
     * aggregate row. Called once per fingerprint per pipeline batch.
     * See the class-level KDoc for the full on-conflict semantics.
     */
    @Query(
        """
        insert into error_groups (
            fingerprint, app_id, type, message, fatal,
            first_seen, last_seen, event_count,
            sample_event_id, sample_stack, status
        ) values (
            :fingerprint, :appId, :type, :message, :fatal,
            :firstSeen, :lastSeen, :count,
            :sampleEventId, :sampleStack, 'open'
        )
        on conflict (fingerprint) do update set
            event_count     = error_groups.event_count + excluded.event_count,
            last_seen       = greatest(error_groups.last_seen, excluded.last_seen),
            first_seen      = least(error_groups.first_seen, excluded.first_seen),
            message         = excluded.message,
            sample_event_id = excluded.sample_event_id,
            sample_stack    = excluded.sample_stack,
            fatal           = excluded.fatal,
            status          = case when error_groups.status = 'resolved'
                                   then 'open'::error_group_status
                                   else error_groups.status end,
            modified        = now()
        """
    )
    suspend fun recordOccurrence(
        fingerprint: String,
        appId: String,
        type: String,
        message: String,
        fatal: Boolean,
        firstSeen: OffsetDateTime,
        lastSeen: OffsetDateTime,
        count: Long,
        sampleEventId: String?,
        sampleStack: String?,
    )

    /** Updates the lifecycle status and returns the full updated row
     *  in a single round trip. */
    @Query(
        """
        update error_groups
           set status = (:status)::error_group_status,
               modified = now()
         where fingerprint = :fingerprint
        returning *
        """
    )
    suspend fun setStatus(fingerprint: String, status: ErrorGroupStatus): ErrorGroup?

    /** Sets or clears the assignee and returns the full updated row. */
    @Query(
        """
        update error_groups
           set assignee_id = :assigneeId,
               modified = now()
         where fingerprint = :fingerprint
        returning *
        """
    )
    suspend fun setAssignee(fingerprint: String, assigneeId: UUID?): ErrorGroup?

    /**
     * Caches the AI summary on the row and stamps `ai_summary_at`.
     * Callers must bound [summary] length (the service caps at
     * `ErrorGroupServiceImpl.MAX_AI_SUMMARY_CHARS`).
     */
    @Query(
        """
        update error_groups
           set ai_summary = :summary,
               ai_summary_at = now(),
               modified = now()
         where fingerprint = :fingerprint
        returning *
        """
    )
    suspend fun setAiSummary(fingerprint: String, summary: String): ErrorGroup?
}
