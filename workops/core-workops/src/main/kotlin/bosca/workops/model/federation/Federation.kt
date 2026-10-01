package bosca.workops.model.federation

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * R37 — every external system Work Ops federates with. Each
 * variant has a per-kind adapter; `WORKOPS_INSTANCE` carries
 * Bosca↔Bosca peering with Ed25519 signatures.
 */
@Serializable
enum class FederationPeerKind {
    WORKOPS_INSTANCE,
    JIRA_CLOUD,
    JIRA_DATA_CENTER,
    GITHUB_ISSUES,
    GITLAB_ISSUES,
    LINEAR,
    AZURE_DEVOPS,
    CUSTOM,
}

@Serializable
enum class PrincipalMappingPolicy {
    STRICT_EMAIL_MATCH,
    EMAIL_OR_PLACEHOLDER,
    MANUAL_MAPPING_REQUIRED,
}

/**
 * R37 — credentials each adapter uses against its peer. Stored
 * on the peer row's `auth_payload` jsonb; the adapter decodes
 * the variant relevant to its kind.
 */
@Serializable
sealed class FederationAuth {
    @Serializable @SerialName("BearerToken")
    data class BearerToken(val token: String) : FederationAuth()

    @Serializable @SerialName("OauthClient")
    data class OauthClient(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
    ) : FederationAuth()

    @Serializable @SerialName("MutualTls")
    data class MutualTls(
        val instanceId: String,
        val ed25519PublicKey: String,
    ) : FederationAuth()

    @Serializable @SerialName("BasicAuth")
    data class BasicAuth(val username: String, val password: String) : FederationAuth()
}

@Serializable
data class FederationPeer(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String? = null,
    val kind: FederationPeerKind,
    @ColumnName("base_url")
    val baseUrl: String,
    @ColumnName("auth_payload")
    @Contextual
    val authPayload: JsonElement = JsonObject(emptyMap()),
    @ColumnName("principal_mapping_policy")
    val principalMappingPolicy: PrincipalMappingPolicy = PrincipalMappingPolicy.EMAIL_OR_PLACEHOLDER,
    @ColumnName("sync_interval_seconds")
    val syncIntervalSeconds: Int = 60,
    val enabled: Boolean = true,
    @ColumnName("last_sync_at")
    @Contextual
    val lastSyncAt: OffsetDateTime? = null,
    @ColumnName("last_sync_etag")
    val lastSyncEtag: String? = null,
    val version: Long = 0,
) {
    init {
        require(name.isNotBlank()) { "federation peer name must not be blank" }
        require(baseUrl.isNotBlank()) { "federation peer baseUrl must not be blank" }
        require(syncIntervalSeconds > 0) { "syncIntervalSeconds must be positive" }
    }
}

/**
 * R37 — bitmask describing which fields the peer owns. Inbound
 * pulls overwrite the masked fields; outbound pushes filter by
 * the same mask. Bits land here so admins can author them per
 * (project, peer) pair.
 */
object FederationFieldMask {
    const val SUMMARY: Long = 1L shl 0
    const val DESCRIPTION: Long = 1L shl 1
    const val STATUS: Long = 1L shl 2
    const val PRIORITY: Long = 1L shl 3
    const val ASSIGNEE: Long = 1L shl 4
    const val LABELS: Long = 1L shl 5
    const val DUE_DATE: Long = 1L shl 6
    const val COMMENTS: Long = 1L shl 7
    const val ATTACHMENTS: Long = 1L shl 8
    const val LINKS: Long = 1L shl 9
    const val WATCHERS: Long = 1L shl 10
    const val WORKLOG: Long = 1L shl 11
    const val ALL: Long =
        SUMMARY or DESCRIPTION or STATUS or PRIORITY or ASSIGNEE or LABELS or
        DUE_DATE or COMMENTS or ATTACHMENTS or LINKS or WATCHERS or WORKLOG
}

@Serializable
data class FederationProjectFieldMask(
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("peer_id")
    @Contextual
    val peerId: UUID,
    val mask: Long,
)

/**
 * R37 — recorded conflict between local and remote field
 * values. The resolver (manual or automatic) writes
 * `resolution`; the row stays for audit.
 */
@Serializable
enum class FederationConflictResolution {
    PEER_WON,
    LOCAL_WON,
    MERGED,
    UNRESOLVED,
}

@Serializable
data class FederationConflict(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("peer_id")
    @Contextual
    val peerId: UUID,
    @ColumnName("field_key")
    val fieldKey: String,
    @ColumnName("old_value")
    @Contextual
    val oldValue: JsonElement = JsonObject(emptyMap()),
    @ColumnName("new_value")
    @Contextual
    val newValue: JsonElement = JsonObject(emptyMap()),
    val resolution: FederationConflictResolution = FederationConflictResolution.UNRESOLVED,
    @ColumnName("triggered_by")
    val triggeredBy: String,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("resolved_at")
    @Contextual
    val resolvedAt: OffsetDateTime? = null,
) {
    init {
        require(fieldKey.isNotBlank()) { "conflict fieldKey must not be blank" }
        require((resolution == FederationConflictResolution.UNRESOLVED) == (resolvedAt == null)) {
            "resolvedAt must be null when UNRESOLVED and non-null when resolved"
        }
    }
}

/**
 * R37 — adapter-produced normalized shape. Adapters convert
 * peer payloads into this view; the sync engine projects it
 * onto local task fields per the field mask.
 */
@Serializable
data class RemoteTaskSnapshot(
    val remoteId: String,
    val remoteKey: String?,
    val canonicalUrl: String,
    val summary: String,
    val descriptionMarkdown: String?,
    val statusName: String?,
    val priorityName: String?,
    val assigneeRemoteUserId: String?,
    val labels: List<String> = emptyList(),
    val dueDate: String? = null,
    val etag: String? = null,
    val extraFields: JsonElement = JsonObject(emptyMap()),
)
