package bosca.analytics.model

import kotlinx.serialization.Serializable

/**
 * Describes a single output column of an analytics query, derived from the query's result-set
 * metadata. Lets clients discover what a query returns without executing it for data — e.g. to
 * filter the queries that are compatible with a given consumer (a recommendation strategy that
 * needs `metadata_id`/`collection_id`, an item-to-item strategy that needs `source_id`/`related_id`).
 */
@Serializable
data class AnalyticsQueryColumn(
    /** The output column name, as returned by the query (e.g. `metadata_id`). */
    val name: String,
    /** The SQL type name of the column (e.g. `varchar`, `bigint`, `double`). */
    val typeName: String,
    /** Whether the column may contain nulls (best-effort, from result-set metadata). */
    val nullable: Boolean,
)
