package bosca.artifacts.service

import bosca.artifacts.github.GitHubPublicationException
import bosca.artifacts.github.GitHubReleaseClient
import bosca.artifacts.model.*
import bosca.artifacts.repository.ArtifactPublicationRepository
import bosca.db.transaction
import bosca.pipelines.service.PipelineSecretService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import org.slf4j.LoggerFactory

@ServiceImplementation
class ArtifactPublicationServiceImpl(
    private val repository: ArtifactPublicationRepository,
    private val artifacts: ArtifactRepositoryService,
    private val blobs: BlobStorageService,
    private val secrets: PipelineSecretService,
    private val github: GitHubReleaseClient,
) : ArtifactPublicationService {
    override suspend fun createDestination(input: ArtifactPublicationDestinationInput): ArtifactPublicationDestination {
        require(input.tokenSecretName.isNotBlank()) { "A destination secret name is required" }
        require(input.key.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid destination key" }
        require(input.githubRepositoryId > 0 && input.owner.matches(Regex("[A-Za-z0-9-]{1,100}")) &&
            input.githubRepository.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid GitHub repository" }
        require(input.tagPrefix.length <= 200 && input.tagPrefix.none { it.isWhitespace() || it.code < 32 }) { "Invalid tag prefix" }
        val artifactRepository = artifacts.getRepository(input.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
        require(artifactRepository.type == ArtifactType.RAW.value) { "GitHub release assets require a raw artifact repository" }
        val id = UUID.random()
        val destination = ArtifactPublicationDestination(id, input.repositoryId, input.key, input.githubRepositoryId,
            input.owner, input.githubRepository, input.tagPrefix, input.enabled,
            input.tokenSecretName)
        github.verifyRepository(destination, token(destination))
        return repository.createDestination(destination)
    }

    override suspend fun destinations(repositoryId: UUID) = repository.destinations(repositoryId)

    override suspend fun updateDestination(id: UUID, expectedVersion: Long, enabled: Boolean, tokenSecretName: String?): ArtifactPublicationDestination {
        val current = repository.findDestination(id) ?: throw NoSuchElementException("Publication destination not found")
        val secretName = tokenSecretName ?: current.tokenSecretName
        require(secretName.isNotBlank()) { "A destination secret name cannot be blank" }
        if (enabled) github.verifyRepository(current, token(current.copy(tokenSecretName = secretName)))
        return repository.updateDestination(id, expectedVersion, enabled, secretName)
            ?: throw IllegalStateException("Publication destination changed; reload before updating")
    }

    private suspend fun token(destination: ArtifactPublicationDestination): String =
        secrets.resolve(destination.tokenSecretName)?.takeIf { it.isNotBlank() }
            ?: error("Publication secret '${destination.tokenSecretName}' is not configured")

    override suspend fun prepare(destinationId: UUID, versionId: UUID, commitSha: String, prerelease: Boolean): ArtifactPublication = transaction {
        require(commitSha.matches(Regex("[0-9a-f]{40}"))) { "Publication requires the exact Git commit SHA" }
        val version = artifacts.finalizeVersion(versionId)
        val destination = repository.findDestination(destinationId) ?: throw NoSuchElementException("Publication destination not found")
        require(destination.enabled) { "Publication destination is disabled" }
        require(destination.repositoryId == version.repositoryId) { "Publication destination does not match the artifact repository" }
        val source = artifacts.getVersionBlobs(versionId)
        require(source.isNotEmpty()) { "An empty version cannot be published" }
        val files = source.map { file ->
            val filename = file.filename ?: throw IllegalArgumentException("Release assets require filenames")
            require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && !filename.endsWith('.')) { "Unsupported release asset filename" }
            require(file.digest.matches(Regex("sha256:[0-9a-f]{64}"))) { "Release assets require SHA-256 digests" }
            val blob = blobs.get(file.digest) ?: throw NoSuchElementException("Artifact blob not found")
            val mediaType = file.mediaType ?: "application/octet-stream"
            mediaType.toMediaType()
            ArtifactPublicationFile(filename, file.digest, blob.size, mediaType)
        }.sortedBy { it.filename }
        require(files.map { it.filename }.distinct().size == files.size) { "Release asset filenames must be unique" }
        repository.publications(versionId, 1, 0).firstOrNull()?.let { original ->
            require(original.commitSha == commitSha && original.prerelease == prerelease && original.files == files) {
                "Publication already exists with different input"
            }
        }
        val tag = destination.tagPrefix + version.version
        require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/+\\-]*")) && !tag.contains("..") && !tag.contains("//") &&
            tag.split('/').none { it.startsWith('.') || it.endsWith('.') || it.endsWith(".lock") } && !tag.endsWith('/')) {
            "Invalid GitHub release tag"
        }
        val existing = repository.find(destination.id, versionId)
        if (existing != null) {
            require(existing.commitSha == commitSha && existing.prerelease == prerelease && existing.files == files && existing.tagName == tag) {
                "Publication already exists with different input"
            }
            existing
        } else repository.create(ArtifactPublication(UUID.random(), destination.id, versionId, tag, commitSha, prerelease, files))
    }

    override suspend fun publications(versionId: UUID, limit: Int, offset: Long): List<ArtifactPublication> {
        require(limit in 1..1000 && offset >= 0) { "Invalid publication pagination" }
        return repository.publications(versionId, limit, offset)
    }

    override suspend fun publish(id: UUID) {
        val initial = repository.find(id) ?: return
        var attempted = false
        try {
            transaction {
                artifacts.finalizeVersion(initial.versionId)
                val publication = repository.lock(id) ?: return@transaction
                if (publication.verified != null) return@transaction
                val destination = repository.findDestination(publication.destinationId) ?: error("Publication destination not found")
                check(destination.enabled) { "Publication destination is disabled" }
                repository.attempt(id)
                val releaseId = github.publish(destination, token(destination), publication)
                repository.published(id, releaseId)
            }
            attempted = true
            transaction {
                if (repository.find(id) == null) return@transaction
                artifacts.finalizeVersion(initial.versionId)
                val publication = repository.lock(id) ?: return@transaction
                if (publication.verified != null) return@transaction
                val destination = repository.findDestination(publication.destinationId) ?: error("Publication destination not found")
                check(destination.enabled) { "Publication destination is disabled" }
                github.verify(destination, token(destination), publication)
                repository.verified(id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (e is GitHubPublicationException) e.message.orEmpty() else "Publication failed (${e.javaClass.simpleName})"
            try {
                transaction { repository.failed(id, message, !attempted) }
            } catch (recordingFailure: CancellationException) {
                throw recordingFailure
            } catch (recordingFailure: Exception) {
                e.addSuppressed(recordingFailure)
            }
            log.warn("Artifact publication {} failed: {}", id, message)
            throw e
        }
    }

    companion object { private val log = LoggerFactory.getLogger(ArtifactPublicationServiceImpl::class.java) }
}
