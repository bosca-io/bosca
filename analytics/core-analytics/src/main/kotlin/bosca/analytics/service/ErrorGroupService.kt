package bosca.analytics.service

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.model.Events
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Records and queries aggregated error group state.
 *
 * The composite event repository delegates to [recordBatch] once per
 * ingestion batch via [bosca.analytics.repository.ErrorGroupEventRepository].
 * The service groups error events in the batch by their fingerprint and
 * issues a single upsert per fingerprint, so update load on the
 * `error_groups` table is bounded by batch flush cadence rather than
 * raw error event rate.
 */
interface ErrorGroupService : Service {

    /**
     * Folds the error events in [events] into per-fingerprint aggregate
     * upserts. Non-error events and error events that lack a fingerprint
     * (the fingerprint transform should always populate this — a missing
     * fingerprint indicates a bug or out-of-order pipeline configuration)
     * are skipped silently.
     *
     * Safe to call with batches that contain no error events: in that
     * case the method returns immediately without touching the database.
     */
    suspend fun recordBatch(events: Events)

    /** Looks up a single group by its fingerprint, or returns null if none exists. */
    suspend fun getByFingerprint(fingerprint: String): ErrorGroup?

    /** Returns a paginated, filtered list of groups for display in the admin UI. */
    suspend fun list(
        appId: String? = null,
        status: ErrorGroupStatus? = null,
        fatal: Boolean? = null,
        search: String? = null,
        offset: Long = 0L,
        limit: Int = 50,
    ): List<ErrorGroup>

    /** Counts groups matching the same filters used by [list]. */
    suspend fun count(
        appId: String? = null,
        status: ErrorGroupStatus? = null,
        fatal: Boolean? = null,
        search: String? = null,
    ): Long

    /** Sets the lifecycle status of a group (open, resolved, ignored). */
    suspend fun setStatus(fingerprint: String, status: ErrorGroupStatus): ErrorGroup

    /** Assigns or unassigns the operator responsible for a group. */
    suspend fun assign(fingerprint: String, assigneeId: UUID?): ErrorGroup

    /**
     * Caches an AI-generated root-cause summary on the group row and
     * stamps `aiSummaryAt`. Used by [ErrorGroupAnalysisService]
     * implementations after a successful LLM call.
     */
    suspend fun setAiSummary(fingerprint: String, summary: String): ErrorGroup
}
