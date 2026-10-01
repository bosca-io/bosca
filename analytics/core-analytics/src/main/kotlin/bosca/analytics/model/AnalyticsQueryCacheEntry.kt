package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Bookkeeping record for one cached parameter combination of an analytics query.
 *
 * Each distinct set of execution parameters that has been run against a caching
 * query (one with [AnalyticsQuery.refreshIntervalSeconds] set) is tracked here so
 * the background refresh job knows which combinations to re-execute, when each
 * was last refreshed, and which combinations have gone idle and can be pruned.
 *
 * @param queryId the analytics query these parameters belong to
 * @param parametersHash stable hash of the canonical parameter set, used to address the stored result
 * @param parameters the canonical parameter set, serialized as a list of parameter/value pairs
 * @param lastRefreshedAt when the cached records for this combination were last computed
 * @param lastAccessedAt when this combination was last requested by a caller
 * @param queryGeneration executable query generation that produced the result
 * @param objectVersion immutable object version currently referenced by this row;
 * `null` only for entries created before versioned object paths were introduced
 * @param supersededObjectVersion previous immutable object version returned by the
 * atomic pointer swap so concurrent replacements can clean up the exact object
 * @param supersededLegacyObject whether the atomic swap replaced an unversioned
 * legacy object path
 */
@Serializable
data class AnalyticsQueryCacheEntry(
    @ColumnName("query_id")
    @Contextual
    val queryId: UUID,
    @ColumnName("parameters_hash")
    val parametersHash: String,
    val parameters: JsonElement,
    @ColumnName("last_refreshed_at")
    val lastRefreshedAt: OffsetDateTime,
    @ColumnName("last_accessed_at")
    val lastAccessedAt: OffsetDateTime,
    @ColumnName("query_generation")
    val queryGeneration: Long = 0,
    @ColumnName("object_version")
    @Contextual
    val objectVersion: UUID? = null,
    @ColumnName("superseded_object_version")
    @Contextual
    val supersededObjectVersion: UUID? = null,
    @ColumnName("superseded_legacy_object")
    val supersededLegacyObject: Boolean = false,
)
