package bosca.gateway.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.security.model.PermissibleEntity
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class Gateway(
    @Contextual override val id: UUID = UUID.NIL,
    val name: String,
    val url: String,
    @ColumnName("health_check_path")
    val healthCheckPath: String? = null,
    @ColumnName("health_check_interval_secs")
    val healthCheckIntervalSecs: Int = 30,
    @ColumnName("connect_timeout_secs")
    val connectTimeoutSecs: Int = 5,
    @ColumnName("request_timeout_secs")
    val requestTimeoutSecs: Int = 300,
    @ColumnName("pool_max_idle")
    val poolMaxIdle: Int = 10,
    @ColumnName("pool_idle_timeout_secs")
    val poolIdleTimeoutSecs: Int = 90,
    val enabled: Boolean = true,
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    @ColumnName("health_status")
    val healthStatus: GatewayHealthStatus = GatewayHealthStatus.UNKNOWN,
    @ColumnName("health_status_changed_at")
    @Contextual
    val healthStatusChangedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("health_status_reason")
    val healthStatusReason: String? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
    val version: Long = 0,
) : PermissibleEntity<UUID> {
    override val isPublished: Boolean get() = enabled
    override val isAdvertised: Boolean get() = false
    override val isDeleted: Boolean get() = deletedAt != null
}

@Serializable
data class GatewayInput(
    val name: String,
    val url: String,
    val healthCheckPath: String? = null,
    val healthCheckIntervalSecs: Int = 30,
    val connectTimeoutSecs: Int = 5,
    val requestTimeoutSecs: Int = 300,
    val poolMaxIdle: Int = 10,
    val poolIdleTimeoutSecs: Int = 90,
)

/**
 * Aggregated health of a proxied upstream as last reported by the
 * proxy fleet. The proxy is authoritative — Bosca only stores the
 * latest transition so the admin UI can show "Trino is down" without
 * the proxy fleet acting as the source of truth for routing decisions.
 *
 * Wire-format casing matches the Postgres enum (lowercase). The
 * [GatewayHealthStatusMapper] handles the DB ↔ Kotlin conversion so
 * the Kotlin code can keep enum-style uppercase naming.
 */
@DbMapper(GatewayHealthStatusMapper::class)
@Serializable
enum class GatewayHealthStatus {
    UP,
    DOWN,
    UNKNOWN,
}

object GatewayHealthStatusMapper : EnumMapper<GatewayHealthStatus>({
    GatewayHealthStatus.valueOf(it.uppercase())
})

/**
 * Body the Rust proxy POSTs when its per-upstream state machine
 * detects a transition. `status` mirrors the Postgres enum values;
 * `reason` is an opaque diagnostic string (HTTP status, error
 * message, etc.) the operator can surface in dashboards.
 */
@Serializable
data class GatewayHealthReport(
    val status: GatewayHealthStatus,
    val reason: String? = null,
)
