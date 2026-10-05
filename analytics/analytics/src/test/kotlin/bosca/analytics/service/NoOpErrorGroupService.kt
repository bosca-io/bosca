package bosca.analytics.service

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.model.Events
import bosca.serialization.UUID

/**
 * Test stub for [ErrorGroupService] that does nothing. Used by event
 * processing tests that don't care about per-fingerprint aggregation.
 */
internal object NoOpErrorGroupService : ErrorGroupService {
    override suspend fun recordBatch(events: Events) = Unit
    override suspend fun getByFingerprint(fingerprint: String): ErrorGroup? = null
    override suspend fun list(
        appId: String?,
        status: ErrorGroupStatus?,
        fatal: Boolean?,
        search: String?,
        offset: Long,
        limit: Int,
    ): List<ErrorGroup> = emptyList()
    override suspend fun count(
        appId: String?,
        status: ErrorGroupStatus?,
        fatal: Boolean?,
        search: String?,
    ): Long = 0L
    override suspend fun setStatus(fingerprint: String, status: ErrorGroupStatus): ErrorGroup =
        error("not implemented in test stub")
    override suspend fun assign(fingerprint: String, assigneeId: UUID?): ErrorGroup =
        error("not implemented in test stub")
    override suspend fun setAiSummary(fingerprint: String, summary: String): ErrorGroup =
        error("not implemented in test stub")
}
