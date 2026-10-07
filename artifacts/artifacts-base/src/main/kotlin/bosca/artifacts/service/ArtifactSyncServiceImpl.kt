package bosca.artifacts.service

import bosca.artifacts.model.*
import bosca.artifacts.repository.ArtifactSyncRepository
import bosca.artifacts.sync.GhcrClient
import bosca.artifacts.sync.GhcrException
import bosca.db.transaction
import bosca.pipelines.service.PipelineSecretService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException

@ServiceImplementation
class ArtifactSyncServiceImpl(
    private val repository: ArtifactSyncRepository,
    private val artifacts: ArtifactRepositoryService,
    private val secrets: PipelineSecretService,
    private val ghcr: GhcrClient,
) : ArtifactSyncService {
    override suspend fun createDestination(input: ArtifactSyncDestinationInput): ArtifactSyncDestination {
        require(input.key.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid sync destination key" }
        require(
            input.remoteRepository.length <= 255 && input.remoteRepository.split('/').size >= 2 &&
                    input.remoteRepository.split('/').all { it.matches(Regex("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*")) }) {
            "A lowercase GHCR image path including its owner is required"
        }
        validateCredentials(input.username, input.tokenSecretName)
        val artifact = artifacts.getRepository(input.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
        require(artifact.type == ArtifactType.DOCKER.value) { "GHCR syncing requires a Docker repository" }
        if (input.enabled) token(input.tokenSecretName)
        return repository.createDestination(
            ArtifactSyncDestination(
                UUID.random(), input.repositoryId, input.key,
                input.remoteRepository, input.username, input.tokenSecretName, input.enabled
            )
        )
    }

    override suspend fun destinations(repositoryId: UUID) = repository.destinations(repositoryId)

    override suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, username: String?, tokenSecretName: String?): ArtifactSyncDestination {
        val current = repository.destination(id) ?: throw NoSuchElementException("Sync destination not found")
        val user = username ?: current.username
        val secret = tokenSecretName ?: current.tokenSecretName
        validateCredentials(user, secret)
        if (enabled) token(secret)
        return repository.updateDestination(id, expectedVersion, enabled, user, secret)
            ?: error("Sync destination changed; reload before updating")
    }

    override suspend fun prepare(target: ArtifactSyncTarget): ArtifactSync? = transaction {
        require(target.tagName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))) { "Invalid Docker tag" }
        require(target.manifestDigest.matches(Regex("sha256:[0-9a-f]{64}"))) { "Invalid Docker manifest digest" }
        val destination = repository.lockDestination(target.destinationId) ?: return@transaction null
        if (!destination.enabled) return@transaction null
        val version = artifacts.getVersion(target.versionId) ?: return@transaction null
        require(version.repositoryId == destination.repositoryId && version.version == target.manifestDigest) {
            "Sync target does not match the stored Docker manifest"
        }
        val artifact = artifacts.getRepository(version.repositoryId) ?: return@transaction null
        require(artifact.type == ArtifactType.DOCKER.value) { "GHCR syncing requires a Docker repository" }
        if (artifacts.findTag(artifact.id, target.tagName)?.manifestDigest != target.manifestDigest) return@transaction null
        repository.request(ArtifactSync(UUID.random(), destination.id, version.id, target.tagName, target.manifestDigest))
    }

    override suspend fun syncs(repositoryId: UUID, limit: Int, offset: Long): List<ArtifactSync> {
        require(limit in 1..1000 && offset >= 0) { "Invalid sync pagination" }
        return repository.syncs(repositoryId, limit, offset)
    }

    override suspend fun retry(id: UUID): ArtifactSync = transaction {
        val current = repository.find(id) ?: throw NoSuchElementException("Artifact sync not found")
        val destination = repository.destination(current.destinationId) ?: throw NoSuchElementException("Sync destination not found")
        check(destination.enabled) { "Sync destination is disabled" }
        val tag = artifacts.findTag(destination.repositoryId, current.tagName) ?: throw NoSuchElementException("Docker tag not found")
        val version = artifacts.findVersion(destination.repositoryId, tag.manifestDigest) ?: throw NoSuchElementException("Docker manifest version not found")
        val sync = repository.retry(id) ?: throw NoSuchElementException("Artifact sync not found")
        ArtifactTagPublished(UUID.random(), destination.repositoryId, version.id, tag.name, tag.manifestDigest).dispatch()
        sync
    }

    override suspend fun sync(id: UUID) {
        var failure: Exception? = null
        transaction {
            val sync = repository.lock(id) ?: return@transaction
            if (sync.synced != null) return@transaction
            val destination = repository.destination(sync.destinationId) ?: return@transaction
            if (artifacts.getVersion(sync.versionId) == null || !destination.enabled ||
                artifacts.findTag(destination.repositoryId, sync.tagName)?.manifestDigest != sync.manifestDigest) return@transaction
            repository.attempt(id, sync.manifestDigest)
            try {
                val manifest = artifacts.getVersionBlobs(sync.versionId).firstOrNull {
                    it.role == "manifest" && it.digest == sync.manifestDigest
                }
                val copied = ghcr.push(destination, token(destination.tokenSecretName), sync.tagName, sync.manifestDigest, manifest?.mediaType) {
                    repository.destination(sync.destinationId)?.enabled == true &&
                            artifacts.findTag(destination.repositoryId, sync.tagName)?.manifestDigest == sync.manifestDigest
                }
                if (copied) repository.synced(id, sync.manifestDigest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is GhcrException) e.message.orEmpty() else "Artifact sync failed (${e.javaClass.simpleName})"
                try {
                    repository.failed(id, sync.manifestDigest, message)
                } catch (recordingFailure: CancellationException) {
                    throw recordingFailure
                } catch (recordingFailure: Exception) {
                    e.addSuppressed(recordingFailure)
                    throw e
                }
                failure = e
            }
        }
        failure?.let { throw it }
    }

    private suspend fun token(name: String): String = secrets.resolve(name)?.takeIf { it.isNotBlank() }
        ?: error("Artifact sync secret '$name' is not configured")

    private fun validateCredentials(username: String, secretName: String) {
        require(username.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "A GitHub username is required" }
        require(secretName.isNotBlank()) { "A sync secret name is required" }
    }
}
