package bosca.gateway.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

@Serializable
data class GatewayRoute(
    @Contextual val id: UUID = UUID.NIL,
    @ColumnName("gateway_id")
    @Contextual
    val gatewayId: UUID,
    @ColumnName("path_pattern")
    val pathPattern: String,
    /**
     * Host patterns this route accepts. Empty list = match any host —
     * the prior single-host-per-instance behaviour. Each entry is a
     * literal host (`api.bosca.io`) or a leading-`*.` wildcard
     * (`*.bosca.io`, matching one label). The Rust proxy resolves
     * precedence as host-literal > host-wildcard > host-any.
     */
    val hosts: List<String> = emptyList(),
    @ColumnName("auth_method")
    val authMethod: GatewayAuthMethod,
    @ColumnName("strip_prefix")
    val stripPrefix: Boolean = false,
    @ColumnName("read_groups")
    val readGroups: List<String> = emptyList(),
    @ColumnName("write_groups")
    val writeGroups: List<String> = emptyList(),
    @ColumnName("inject_headers")
    @Contextual
    val injectHeaders: JsonElement = JsonObject(emptyMap()),
    @ColumnName("sort_order")
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
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
)

@DbMapper(GatewayAuthMethodMapper::class)
@Serializable
enum class GatewayAuthMethod {
    OAUTH2,
    JWT,
    BASIC,
    NONE,
}

object GatewayAuthMethodMapper : EnumMapper<GatewayAuthMethod>({ GatewayAuthMethod.valueOf(it.uppercase()) })

@Serializable
data class GatewayRouteInput(
    @Contextual val gatewayId: UUID,
    val pathPattern: String,
    val hosts: List<String> = emptyList(),
    val authMethod: GatewayAuthMethod,
    val stripPrefix: Boolean = false,
    val readGroups: List<String> = emptyList(),
    val writeGroups: List<String> = emptyList(),
    @Contextual val injectHeaders: JsonElement = JsonObject(emptyMap()),
    val sortOrder: Int = 0,
)
