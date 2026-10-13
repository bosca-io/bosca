package bosca.artifacts.service

import bosca.artifacts.github.GitHubPublicationException
import bosca.artifacts.github.GitHubReleaseClient
import bosca.artifacts.model.*
import bosca.artifacts.repository.ArtifactPublicationRepository
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
import okhttp3.MediaType.Companion.toMediaType
import org.slf4j.LoggerFactory

@ServiceImplementation
class ArtifactPublicationServiceImpl(
    private val repository: ArtifactPublicationRepository,
    private val artifacts: ArtifactRepositoryService,
    private val blobs: BlobStorageService,
    private val secrets: PipelineSecretService,
    private val github: GitHubReleaseClient,
    private val pipelines: ObjectProvider<PipelineService>,
    private val runs: ObjectProvider<PipelineRunService>,
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
        val destination = repository.findDestination(destinationId) ?: throw NoSuchElementException("Publication destination not found")
        require(destination.enabled) { "Publication destination is disabled" }
        val version = artifacts.finalizeVersion(versionId)
        require(destination.repositoryId == version.repositoryId) { "Publication destination does not match the artifact repository" }
        val original = repository.publications(versionId, 1, 0).firstOrNull()
        verifyCompletion(version, commitSha, original)
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
        original?.let { original ->
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
        } else {
            github.verifyTarget(destination, token(destination), tag, commitSha)
            repository.create(ArtifactPublication(UUID.random(), destination.id, versionId, tag, commitSha, prerelease, files))
        }
    }

    override suspend fun publications(versionId: UUID, limit: Int, offset: Long): List<ArtifactPublication> {
        require(limit in 1..1000 && offset >= 0) { "Invalid publication pagination" }
        return repository.publications(versionId, limit, offset)
    }

    private fun verifyCompletion(version: ArtifactVersion, commitSha: String, original: ArtifactPublication?) {
        require(version.completedCommitSha != null || original != null) { "Artifact version uploads have not completed" }
        require((version.completedCommitSha ?: original?.commitSha) == commitSha) { "Publication commit does not match the completed build" }
    }

    override suspend fun publish(authentication: AuthenticationContext, destinationId: UUID, versionId: UUID, commitSha: String): UUID {
        require(commitSha.matches(Regex("[0-9a-f]{40}"))) { "Publication requires the exact Git commit SHA" }
        val candidates = pipelines.get().acceptingInput(setOf(ArtifactPublicationTarget::class.qualifiedName.orEmpty()))
            .filter { !it.triggered }
        val pipeline = candidates.singleOrNull { it.name == ArtifactPublicationService.PUBLICATION_PIPELINE_NAME }
            ?: candidates.singleOrNull()
            ?: if (candidates.isEmpty()) {
                error("No release publication pipeline accepts ArtifactPublicationTarget. Install 'Default Artifact Sync Pipelines' in System > Packages, or check the publication pipeline's input type and trigger setting.")
            } else {
                error("Multiple release publication pipelines accept ArtifactPublicationTarget. Give exactly one the name '${ArtifactPublicationService.PUBLICATION_PIPELINE_NAME}'.")
            }
        val runService = runs.get()
        return transaction {
            val destination = repository.findDestination(destinationId) ?: throw NoSuchElementException("Publication destination not found")
            check(destination.enabled) { "Publication destination is disabled" }
            val token = token(destination)
            val version = artifacts.getVersion(versionId) ?: throw NoSuchElementException("Artifact version not found")
            require(version.repositoryId == destination.repositoryId) { "Publication destination does not match the artifact repository" }
            val artifact = artifacts.getRepository(version.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
            require(artifact.type == ArtifactType.RAW.value) { "GitHub release assets require a raw artifact repository" }
            require(artifacts.getVersionBlobs(version.id).isNotEmpty()) { "An empty version cannot be published" }
            val original = repository.publications(versionId, 1, 0).firstOrNull()
            verifyCompletion(version, commitSha, original)
            original?.let { original ->
                require(original.commitSha == commitSha) { "Publication already exists with a different commit" }
            }
            github.verifyTarget(destination, token, destination.tagPrefix + version.version, commitSha)
            val target = ArtifactPublicationTarget(destination.id, version.id, commitSha, reverify = true)
            val run = runService.start(pipeline, PipelineValue.of(target, ArtifactPublicationTarget.serializer()),
                "manual", OffsetDateTime.now(), authentication)
                ?: error("Release publication pipeline is at its concurrency or rate limit")
            run.id
        }
    }

    override suspend fun publish(id: UUID) = publish(id, false)

    override suspend fun publish(id: UUID, reverify: Boolean) {
        val initial = repository.find(id) ?: return
        var failureState: ArtifactPublication? = null
        try {
            // Commit the attempt number before provider work so a failed request cannot roll it back.
            val attempt = transaction {
                artifacts.finalizeVersion(initial.versionId)
                val publication = repository.lock(id) ?: return@transaction null
                val verified = publication.verified
                val modified = publication.modified
                if (verified != null && publication.error == null && !reverify &&
                    (modified == null || !verified.isBefore(modified))) return@transaction null
                repository.attempt(id) ?: error("Publication disappeared")
            } ?: return
            failureState = attempt
            val published = transaction {
                if (repository.find(id) == null) return@transaction null
                artifacts.finalizeVersion(initial.versionId)
                val publication = repository.lock(id) ?: return@transaction null
                if (publication.attempts != attempt.attempts) return@transaction null
                failureState = publication
                val destination = repository.findDestination(publication.destinationId) ?: error("Publication destination not found")
                check(destination.enabled) { "Publication destination is disabled" }
                val releaseId = github.publish(destination, token(destination), publication)
                repository.published(id, releaseId) ?: error("Publication disappeared")
            } ?: return
            failureState = published
            transaction {
                if (repository.find(id) == null) return@transaction
                artifacts.finalizeVersion(initial.versionId)
                val publication = repository.lock(id) ?: return@transaction
                if (publication.attempts != published.attempts) return@transaction
                failureState = publication
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
                failureState?.let { state ->
                    transaction { repository.failed(id, message, state.attempts, state.modified) }
                }
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
