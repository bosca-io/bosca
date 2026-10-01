package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.security.model.EntityPermission
import bosca.security.model.PermissibleEntity
import bosca.security.model.PermissionAction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * An encrypted secret available to pipeline jobs within a repository.
 * Values are encrypted at rest using AES-256-GCM. The plaintext value
 * is never exposed via the GraphQL API — only the name and metadata.
 *
 * A [PermissibleEntity]: using a secret is evaluated against the RUN'S INITIATING
 * PRINCIPAL with standard Bosca entity-permission semantics — explicit group grants on the secret
 * itself, falling back to the owning repository's permissions when the secret carries no grants of
 * its own. An optional [environmentKey] scopes the secret to jobs bound to that environment: a
 * production store credential scoped to `production` cannot be pulled into an arbitrary branch
 * pipeline that happens to name it.
 */
@Serializable
data class PipelineSecret(
    @Contextual override val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val name: String,
    @ColumnName("encrypted_value") val encryptedValue: String,
    /** When set, the secret resolves ONLY for jobs bound to this environment key. */
    @ColumnName("environment_key") val environmentKey: String? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updated: OffsetDateTime = OffsetDateTime.now()
) : PermissibleEntity<UUID> {

    // Secrets are never public in any dimension.
    override val public: Boolean get() = false
    override val publicContent: Boolean get() = false
    override val publicList: Boolean get() = false
    override val publicSupplementary: Boolean get() = false
    override val isPublished: Boolean get() = true
    override val isAdvertised: Boolean get() = false
    override val isDeleted: Boolean get() = false
}

/** Per-secret permission row — same `(entity, group, action)` shape as repository permissions. */
@Serializable
data class PipelineSecretPermission(
    @Contextual @ColumnName("secret_id") val secretId: UUID,
    @Contextual @ColumnName("group_id") override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission {
    override val entityId: UUID get() = secretId
}
