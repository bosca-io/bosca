package bosca.ai.kit.harness

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Durable generation and checkpoint ownership for a harness-backed conversation. */
@Serializable
data class KitRuntimeSession(
    @Contextual @ColumnName("session_id") val sessionId: UUID,
    @Contextual @ColumnName("principal_id") val principalId: UUID,
    val configuration: JsonElement,
    val authority: JsonElement,
    @ColumnName("requested_settings") val requestedSettings: JsonElement? = null,
    @ColumnName("resolved_settings") val resolvedSettings: JsonElement? = null,
    @ColumnName("restore_workspace_only") val restoreWorkspaceOnly: Boolean = false,
    @ColumnName("context_manifest") val contextManifest: JsonElement? = null,
    @ColumnName("context_reset") val contextReset: String? = null,
    val state: String = "STOPPED",
    val generation: Long = 0,
    val epoch: Long = 0,
    @ColumnName("needs_context") val needsContext: Boolean = true,
    @Contextual val owner: UUID? = null,
    @Contextual @ColumnName("lease_until") val leaseUntil: OffsetDateTime? = null,
    @Contextual @ColumnName("dispatch_id") val dispatchId: UUID? = null,
    val endpoint: String? = null,
    /** Supervisor process admitted for this generation, pinned before hydration or ACP work. */
    @ColumnName("runtime_instance_id") val runtimeInstanceId: String? = null,
    val secrets: JsonElement? = null,
    @ColumnName("credential_id") val credentialId: Long? = null,
    @ColumnName("native_session_id") val nativeSessionId: String? = null,
    @Contextual @ColumnName("active_turn") val activeTurn: UUID? = null,
    @Contextual @ColumnName("cancel_requested_at") val cancelRequestedAt: OffsetDateTime? = null,
    @Contextual @ColumnName("idle_at") val idleAt: OffsetDateTime,
    @ColumnName("archive_key") val archiveKey: String? = null,
    @ColumnName("archive_sha256") val archiveSha256: String? = null,
    @ColumnName("archive_size") val archiveSize: Long? = null,
    val failure: String? = null,
    @Contextual @ColumnName("deleted_at") val deletedAt: OffsetDateTime? = null,
    val version: Long = 0,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual @ColumnName("archive_created") val archiveCreated: OffsetDateTime? = null,
    @ColumnName("archive_failure") val archiveFailure: String? = null,
)
