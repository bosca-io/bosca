package bosca.pipelines.git

import bosca.db.transaction
import bosca.git.model.TreeEntryType
import bosca.git.service.CommitFileInput
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryWriteService
import bosca.pipelines.repository.PipelineRepository
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

/**
 * Bidirectional sync between `pipelines.pipelines` rows and YAML files in a PIPELINE_PROJECT
 * repository. Push serializes a single pipeline to its `git_path` and commits; pull walks the
 * pipelines directory at a commit, validates every graph against the node registry, and upserts
 * rows keyed by `(git_repository_id, git_path)`. Mirrors `AgentGitSyncServiceImpl`.
 */
@ServiceImplementation
class PipelineGitSyncServiceImpl(
    private val pipelineService: PipelineService,
    private val repository: PipelineRepository,
    private val writeService: RepositoryWriteService,
    private val browseService: RepositoryBrowseService,
) : PipelineGitSyncService {

    private val parser = PipelineRepoFileParser()
    private val serializer = PipelineRepoFileSerializer()
    private val log = LoggerFactory.getLogger(PipelineGitSyncServiceImpl::class.java)

    override suspend fun pushToGit(
        pipelineId: UUID,
        authorName: String,
        authorEmail: String,
    ): PipelineSyncResult {
        val pipeline = pipelineService.get(pipelineId)
            ?: return PipelineSyncResult.Failure("Pipeline not found: $pipelineId")
        val repoId = pipeline.gitRepositoryId
            ?: return PipelineSyncResult.Failure("Pipeline '${pipeline.name}' is not linked to a Git repository")
        val gitPath = pipeline.gitPath
            ?: return PipelineSyncResult.Failure("Pipeline '${pipeline.name}' has no git_path")
        try {
            val graph = pipelineService.graphAsJsonElement(pipeline)
            val content = serializer.serialize(pipeline, graph)
            val result = writeService.commitFile(
                CommitFileInput(
                    repositoryId = repoId,
                    path = gitPath,
                    content = content,
                    message = "Update pipeline ${pipeline.name}",
                    authorName = authorName,
                    authorEmail = authorEmail,
                )
            )
            repository.setSyncError(pipelineId, null)
            return PipelineSyncResult.Ok(commitSha = result.commitSha)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordSyncError(pipelineId, "Failed to push pipeline '${pipeline.name}': ${e.message ?: e::class.simpleName}")
        }
    }

    override suspend fun pullFromGit(repositoryId: UUID, commitSha: String): PipelineSyncResult {
        val files = readAllFiles(repositoryId, commitSha)

        val parsed = mutableListOf<PipelineFile>()
        val errors = mutableListOf<PipelineRepoValidationError>()
        for ((path, content) in files) {
            when (val p = parser.parse(path, content)) {
                is ParsedPipelineFile.Parsed -> parsed += p.file
                is ParsedPipelineFile.ParseError -> errors += PipelineRepoValidationError(p.path, p.message)
                is ParsedPipelineFile.UnknownPath -> Unit // not a pipeline file; ignore
            }
        }
        // Validate every graph against the node registry before any write, so a tree with one bad
        // file reports all its problems at once and upserts nothing.
        for (file in parsed) {
            pipelineService.validateGraph(file.graph)?.let { errors += PipelineRepoValidationError(file.path, it) }
        }
        if (errors.isNotEmpty()) return PipelineSyncResult.ValidationFailed(errors)

        transaction {
            for (file in parsed) {
                upsert(file, repositoryId)
            }
        }
        return PipelineSyncResult.Ok(commitSha = commitSha)
    }

    override suspend fun onPushEvent(repositoryId: UUID, beforeSha: String, afterSha: String) {
        val changed = browseService.listChangedPaths(repositoryId, beforeSha, afterSha)
        if (changed.none { PipelineRepoLayout.isPipelineFile(it) }) return
        when (val result = pullFromGit(repositoryId, afterSha)) {
            is PipelineSyncResult.Ok -> Unit
            is PipelineSyncResult.ValidationFailed -> log.warn(
                "PIPELINE_PROJECT pull at {} for repo {} failed validation: {}",
                afterSha, repositoryId, result.errors.joinToString("; ") { "${it.path}: ${it.message}" }
            )
            is PipelineSyncResult.Failure -> log.error(
                "PIPELINE_PROJECT pull at {} for repo {} failed: {}", afterSha, repositoryId, result.message
            )
        }
    }

    override suspend fun backfill(
        repositoryId: UUID,
        entries: List<PipelineBackfillEntry>,
        authorName: String,
        authorEmail: String,
    ): PipelineSyncResult {
        val errors = mutableListOf<String>()
        var lastCommitSha: String? = null
        for (entry in entries) {
            repository.linkToGit(entry.pipelineId, repositoryId, entry.gitPath)
            when (val result = pushToGit(entry.pipelineId, authorName, authorEmail)) {
                is PipelineSyncResult.Ok -> result.commitSha?.let { lastCommitSha = it }
                is PipelineSyncResult.ValidationFailed -> errors += "${entry.gitPath}: " +
                    result.errors.joinToString("; ") { "${it.path}: ${it.message}" }
                is PipelineSyncResult.Failure -> errors += "${entry.gitPath}: ${result.message}"
            }
        }
        return if (errors.isEmpty()) PipelineSyncResult.Ok(commitSha = lastCommitSha)
        else PipelineSyncResult.Failure(errors.joinToString("\n"))
    }

    private suspend fun upsert(file: PipelineFile, repositoryId: UUID) {
        val existing = repository.getByGitRepository(repositoryId, file.path)
        val saved = pipelineService.save(
            id = existing?.id ?: UUID.NIL,
            name = file.name,
            description = file.description,
            acceptedInputType = file.acceptedInputType,
            triggered = file.triggered,
            version = existing?.version ?: 0,
            graph = file.graph,
            tags = file.tags,
            key = file.key,
            api = file.api,
            public = file.public,
            schedule = file.schedule,
        )
        if (existing == null) {
            repository.linkToGit(saved.id, repositoryId, file.path)
        }
        repository.setSyncError(saved.id, null)
    }

    private suspend fun recordSyncError(pipelineId: UUID, message: String): PipelineSyncResult.Failure {
        try {
            repository.setSyncError(pipelineId, message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The DB write failed; the underlying sync failure is still the thing the caller
            // needs to know about, so we return Failure(message) regardless. Log the inability
            // to persist so ops can see drift between PipelineSyncResult and last_sync_error.
            log.warn("Failed to persist sync error for pipeline {}: {}", pipelineId, e.message)
        }
        return PipelineSyncResult.Failure(message)
    }

    private suspend fun readAllFiles(repositoryId: UUID, commitSha: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val entries = browseService.listTree(repositoryId, commitSha, PipelineRepoLayout.PIPELINES_DIR)
        for (entry in entries) {
            if (entry.type != TreeEntryType.BLOB) continue
            if (!PipelineRepoLayout.isPipelineFile(entry.path)) continue
            val blob = browseService.readBlob(repositoryId, commitSha, entry.path) ?: continue
            val content = blob.content ?: continue
            out += entry.path to content
        }
        return out
    }
}
