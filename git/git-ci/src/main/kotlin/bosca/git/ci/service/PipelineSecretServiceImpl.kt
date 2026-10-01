package bosca.git.ci.service

import bosca.di.provide
import bosca.git.ci.repository.PipelineSecretPermissionRepository
import bosca.git.ci.repository.PipelineSecretRepository
import bosca.git.ci.security.PipelineSecretPermissionEvaluator
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineSecret
import bosca.git.service.PipelineSecretService
import bosca.git.service.RepositoryService
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ServiceImplementation
class PipelineSecretServiceImpl(
    private val secretRepository: PipelineSecretRepository,
    private val permissionRepository: PipelineSecretPermissionRepository,
    private val repositoryService: RepositoryService,
    private val repositoryPermissionEvaluator: bosca.git.security.RepositoryPermissionEvaluator,
    private val securityService: SecurityService,
) : PipelineSecretService {

    private val encryptionKey: SecretKey by lazy {
        val encoded = System.getenv("PIPELINE_SECRET_KEY")
            ?: DEV_DEFAULT_KEY
        val keyBytes = Base64.getDecoder().decode(encoded)
        SecretKeySpec(keyBytes, "AES")
    }

    override suspend fun getPermissions(entity: PipelineSecret): List<EntityPermission> =
        permissionRepository.findBySecretId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = permissionRepository.findBySecretIds(batch.keys)
        val grouped = permissions.groupBy { it.secretId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: PipelineSecret,
        action: PermissionAction,
    ): Boolean {
        val repository = repositoryService.findById(entity.repositoryId) ?: return false
        return repositoryPermissionEvaluator.isAllowed(authentication, repository, action)
    }

    override suspend fun setSecret(
        repositoryId: UUID,
        name: String,
        value: String,
        environmentKey: String?,
    ): PipelineSecret {
        val encrypted = encrypt(value)
        return secretRepository.upsert(
            PipelineSecret(
                repositoryId = repositoryId,
                name = name,
                encryptedValue = encrypted,
                environmentKey = environmentKey?.ifBlank { null },
            )
        )
    }

    override suspend fun deleteSecret(repositoryId: UUID, name: String) {
        secretRepository.delete(repositoryId, name)
    }

    override suspend fun listSecrets(repositoryId: UUID): List<PipelineSecret> {
        return secretRepository.findByRepository(repositoryId)
    }

    override suspend fun decryptSecrets(repositoryId: UUID, names: List<String>): Map<String, String> {
        val secrets = secretRepository.findAllByRepository(repositoryId)
        return secrets
            .filter { it.name in names }
            .associate { it.name to decrypt(it.encryptedValue) }
    }

    override suspend fun resolveJobSecrets(job: PipelineJob, run: PipelineRun): Map<String, String> {
        val declared = job.secretNames
        val candidates = if (declared.isEmpty()) {
            // Legacy fallback: no declaration means every repository secret, but an
            // environment-scoped one only reaches a job bound to its environment — the scope is a
            // security property, not part of the declaration opt-in.
            secretRepository.findAllByRepository(run.repositoryId)
                .filter { it.environmentKey == null || it.environmentKey == job.environment }
        } else {
            declared.map { name ->
                val secret = secretRepository.findByName(run.repositoryId, name)
                    ?: throw IllegalStateException(
                        "Secret '$name' is declared by the pipeline but does not exist in this repository"
                    )
                if (secret.environmentKey != null && secret.environmentKey != job.environment) {
                    throw IllegalStateException(
                        "Secret '$name' is scoped to environment '${secret.environmentKey}' — " +
                            "job '${job.name}' is " +
                            (job.environment?.let { "bound to '$it'" } ?: "not environment-bound")
                    )
                }
                secret
            }
        }
        if (candidates.isEmpty()) return emptyMap()

        // The evaluation subject is the run's INITIATING principal — resolved lazily so a legacy
        // job touching only legacy secrets needs no attribution, exactly as before this feature.
        var initiator: AuthenticationContext? = null
        suspend fun initiator(): AuthenticationContext = initiator ?: run {
            val principalId = run.triggeredBy
                ?: throw IllegalStateException(
                    "Run #${run.number} has no initiating principal — its secrets cannot resolve; " +
                        "nothing executes unattributed"
                )
            securityService.impersonate(principalId).also { initiator = it }
        }
        val evaluator = provide<PipelineSecretPermissionEvaluator>()

        val resolved = mutableMapOf<String, String>()
        for (secret in candidates) {
            // Strict evaluation applies to everything under the new model: declared secrets,
            // environment-scoped secrets, and secrets carrying explicit grants. A legacy secret
            // (unscoped, grant-less, undeclared) resolves as it always has, so turning this on
            // breaks no existing pipeline.
            val strict = declared.isNotEmpty() ||
                secret.environmentKey != null ||
                getPermissions(secret).isNotEmpty()
            if (strict && !evaluator.isAllowed(initiator(), secret, PermissionAction.EXECUTE)) {
                throw IllegalStateException(
                    "Secret '${secret.name}' is not permitted for the run's initiating principal"
                )
            }
            resolved[secret.name] = decrypt(secret.encryptedValue)
        }
        return resolved
    }

    override suspend fun addPermission(secretId: UUID, groupId: UUID, action: PermissionAction) =
        permissionRepository.add(secretId, groupId, action)

    override suspend fun removePermission(secretId: UUID, groupId: UUID, action: PermissionAction) =
        permissionRepository.delete(secretId, groupId, action)

    private fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encrypted: String): String {
        val combined = Base64.getDecoder().decode(encrypted)
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext))
    }

    companion object {
        private const val DEV_DEFAULT_KEY = "dGhpcy1pcy1hLWRldi1vbmx5LXNlY3JldC1rZXktMzI="
    }
}
