package bosca.analytics.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@BatchKey("id")
@Serializable
data class AnalyticsQuery(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String,
    val query: String,
    val configuration: JsonElement? = null,
    /**
     * When set, cached query results become eligible for background refresh after
     * [refreshIntervalSeconds] seconds. The actual refresh timing also depends on
     * the configured sweep cadence. When `null`, results are never cached and every
     * execution runs against the analytics store directly. The minimum supported
     * interval is 60 seconds.
     */
    @ColumnName("refresh_interval_seconds")
    val refreshIntervalSeconds: Int? = null,
    /**
     * Monotonically increasing cache identity for the executable query definition.
     *
     * Any SQL, parameter, or cache-configuration edit advances this value. Cache
     * writes are accepted only when their generation still matches the database,
     * preventing an execution that raced an edit from restoring obsolete results.
     */
    @ColumnName("cache_generation")
    val cacheGeneration: Long = 0,
) : PermissibleEntity<UUID> {

    override val public: Boolean = false
    override val publicContent: Boolean = false
    override val publicList: Boolean = false
    override val publicSupplementary: Boolean = false
    override val isPublished: Boolean = true
    override val isAdvertised: Boolean = false
    override val isDeleted: Boolean = false
}
