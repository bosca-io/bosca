package bosca.artifacts.service

import bosca.artifacts.model.*
import bosca.artifacts.repository.ArtifactSyncRepository
import bosca.artifacts.sync.GhcrClient
import bosca.artifacts.sync.GhcrException
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException

@ServiceImplementation
class ArtifactSyncServiceImpl(
    private val repository: ArtifactSyncRepository,
    private val artifacts: ArtifactRepositoryService,
    private val secrets: PipelineSecretService,
    private val ghcr: GhcrClient,
    private val pipelines: ObjectProvider<PipelineService>,
    private val runs: ObjectProvider<PipelineRunService>,
) : ArtifactSyncService {
    override suspend fun createDestination(input: ArtifactSyncDestinationInput): ArtifactSyncDestination {
        validateDestination(input.key, input.remoteRepository)
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

    override suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, username: String?, tokenSecretName: String?, key: String?, remoteRepository: String?): ArtifactSyncDestination = transaction {
        val current = repository.destination(id) ?: throw NoSuchElementException("Sync destination not found")
        val user = username ?: current.username
        val secret = tokenSecretName ?: current.tokenSecretName
        val name = key ?: current.key
        val imagePath = remoteRepository ?: current.remoteRepository
        validateDestination(name, imagePath)
        validateCredentials(user, secret)
        if (enabled) token(secret)
        val updated = repository.updateDestination(id, expectedVersion, enabled, user, secret, name, imagePath)
            ?: error("Sync destination changed; reload before updating")
        if (imagePath != current.remoteRepository) repository.clearSyncs(id)
        updated
    }

    override suspend fun deleteDestination(id: UUID, expectedVersion: Long) {
        check(repository.deleteDestination(id, expectedVersion) == 1) {
            "Sync destination changed or was deleted; reload before deleting"
        }
    }

    override suspend fun prepare(target: ArtifactSyncTarget): ArtifactSync? = transaction {
        require(target.tagName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))) { "Invalid Docker tag" }
        require(target.manifestDigest.matches(Regex("sha256:[0-9a-f]{64}"))) { "Invalid Docker manifest digest" }
        val destination = repository.lockDestination(target.destinationId) ?: return@transaction null
        if (!destination.enabled || destination.remoteRepository != target.remoteRepository) return@transaction null
        val version = artifacts.getVersion(target.versionId) ?: return@transaction null
        require(version.repositoryId == destination.repositoryId && version.version == target.manifestDigest) {
            "Sync target does not match the stored Docker manifest"
        }
        val artifact = artifacts.getRepository(version.repositoryId) ?: return@transaction null
        require(artifact.type == ArtifactType.DOCKER.value) { "GHCR syncing requires a Docker repository" }
        if (artifacts.findTag(artifact.id, target.tagName)?.manifestDigest != target.manifestDigest) {
            repository.discardPending(destination.id, target.tagName, target.manifestDigest)
            return@transaction null
        }
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

    override suspend fun push(authentication: AuthenticationContext, destinationId: UUID, tagName: String): UUID {
        val candidates = pipelines.get().acceptingInput(setOf(ArtifactSyncTarget::class.qualifiedName.orEmpty()))
            .filter { !it.triggered }
        val pipeline = candidates.singleOrNull { it.name == ArtifactSyncService.PUSH_PIPELINE_NAME }
            ?: candidates.singleOrNull()
            ?: error("Docker sync pipeline is missing or ambiguous")
        val runService = runs.get()
        return transaction {
            val destination = repository.lockDestination(destinationId) ?: throw NoSuchElementException("Sync destination not found")
            check(destination.enabled) { "Sync destination is disabled" }
            token(destination.tokenSecretName)
            val tag = artifacts.findTag(destination.repositoryId, tagName) ?: throw NoSuchElementException("Docker tag not found")
            val version = artifacts.findVersion(destination.repositoryId, tag.manifestDigest) ?: throw NoSuchElementException("Docker manifest version not found")
            val target = ArtifactSyncTarget(destination.id, version.id, tag.name, tag.manifestDigest, destination.remoteRepository)
            val sync = prepare(target) ?: error("Docker tag changed; reload before pushing")
            repository.retry(sync.id) ?: throw NoSuchElementException("Artifact sync not found")
            val run = runService.start(pipeline, PipelineValue.of(target, ArtifactSyncTarget.serializer()),
                "manual", OffsetDateTime.now(), authentication)
                ?: error("Docker sync pipeline is at its concurrency or rate limit")
            run.id
        }
    }

    override suspend fun sync(id: UUID) {
        var failure: Exception? = null
        transaction {
            val sync = repository.lock(id) ?: return@transaction
            if (sync.synced != null) return@transaction
            val destination = repository.destination(sync.destinationId) ?: return@transaction
            if (!destination.enabled) return@transaction
            if (artifacts.getVersion(sync.versionId) == null ||
                artifacts.findTag(destination.repositoryId, sync.tagName)?.manifestDigest != sync.manifestDigest) {
                repository.discardPending(destination.id, sync.tagName, sync.manifestDigest)
                return@transaction
            }
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
                else repository.discardPending(destination.id, sync.tagName, sync.manifestDigest)
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

    private fun validateDestination(key: String, remoteRepository: String) {
        require(key.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid sync destination key" }
        require(
            remoteRepository.length <= 255 && remoteRepository.split('/').size >= 2 &&
                    remoteRepository.split('/').all { it.matches(Regex("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*")) }) {
            "A lowercase GHCR image path including its owner is required"
        }
    }

    private fun validateCredentials(username: String, secretName: String) {
        require(username.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "A GitHub username is required" }
        require(secretName.isNotBlank()) { "A sync secret name is required" }
    }
}
