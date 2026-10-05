package bosca.git.service

import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineSecret
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages encrypted secrets available to pipeline jobs within a
 * repository. Values are encrypted at rest; the plaintext is never
 * exposed via the GraphQL API.
 *
 * Secrets are permissible entities: explicit group grants on the secret itself, with
 * the owning repository's permissions as the parent fallback. Job-time resolution is
 * [resolveJobSecrets] — evaluated against the RUN'S INITIATING PRINCIPAL, honoring each secret's
 * environment scope.
 */
interface PipelineSecretService : Service, PermissionService<PipelineSecret, UUID> {

    /**
     * Sets a secret, creating or updating by (repository_id, name).
     * The value is encrypted before storage. [environmentKey] scopes the secret to jobs bound to
     * that environment; null means unscoped.
     */
    suspend fun setSecret(repositoryId: UUID, name: String, value: String, environmentKey: String? = null): PipelineSecret

    /**
     * Deletes a secret by repository and name.
     */
    suspend fun deleteSecret(repositoryId: UUID, name: String)

    /**
     * Lists secrets for a repository. Returns metadata only — the
     * encrypted value is omitted.
     */
    suspend fun listSecrets(repositoryId: UUID): List<PipelineSecret>

    /**
     * Decrypts and returns the specified secrets for a repository.
     * Administrative escape hatch — job execution resolves through [resolveJobSecrets].
     */
    suspend fun decryptSecrets(repositoryId: UUID, names: List<String>): Map<String, String>

    /**
     * Resolves the secrets [job] may use, decrypted. The declared list
     * ([PipelineJob.secretNames]) selects exactly those secrets — a declared name that doesn't
     * exist, is scoped to a different environment, or that the run's initiating principal lacks
     * permission on FAILS LOUDLY naming the secret. An empty declaration falls back to every
     * repository secret the initiator may use (legacy behavior), silently excluding
     * environment-scoped ones the job isn't bound to. A run with no initiating principal cannot
     * resolve any secret — nothing executes unattributed.
     */
    suspend fun resolveJobSecrets(job: PipelineJob, run: PipelineRun): Map<String, String>

    /** Grants [groupId] an [action] on the secret itself. Idempotent. */
    suspend fun addPermission(secretId: UUID, groupId: UUID, action: PermissionAction)

    /** Revokes a grant added by [addPermission]. */
    suspend fun removePermission(secretId: UUID, groupId: UUID, action: PermissionAction)
}
