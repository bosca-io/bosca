package bosca.pipelines.git

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Bidirectional sync between `pipelines.pipelines` rows and YAML files in a
 * PIPELINE_PROJECT Git repository. Models the same role `AgentGitSyncService`
 * plays for the AI agent stack: push serializes a single pipeline to its
 * `git_path` and commits; pull parses every pipeline file at a commit,
 * validates each graph against the node registry, and upserts the rows.
 */
interface PipelineGitSyncService : Service {

    /** Serialize a single pipeline to its `git_path` and commit. Returns the new commit SHA. */
    suspend fun pushToGit(
        pipelineId: UUID,
        authorName: String,
        authorEmail: String,
    ): PipelineSyncResult

    /** Parse the pipelines directory at [commitSha], validate, and upsert the DB rows. */
    suspend fun pullFromGit(repositoryId: UUID, commitSha: String): PipelineSyncResult

    /** Webhook entry — calls [pullFromGit] if any file under the pipelines directory changed. */
    suspend fun onPushEvent(repositoryId: UUID, beforeSha: String, afterSha: String)

    /** Bulk export: link each [PipelineBackfillEntry] to its target path and push it. */
    suspend fun backfill(
        repositoryId: UUID,
        entries: List<PipelineBackfillEntry>,
        authorName: String,
        authorEmail: String,
    ): PipelineSyncResult
}

/** One entry in a [PipelineGitSyncService.backfill] request: where in the repo to write a pipeline. */
data class PipelineBackfillEntry(val pipelineId: UUID, val gitPath: String)

/** A single validation error attributed to a file path in a PIPELINE_PROJECT repo. */
data class PipelineRepoValidationError(val path: String, val message: String)

/** Outcome of a pipeline sync operation. */
sealed class PipelineSyncResult {
    /** Sync succeeded. [commitSha] is set when the operation produced a commit. */
    data class Ok(val commitSha: String? = null) : PipelineSyncResult()

    /** Pre-validate caught structural problems; nothing was written. */
    data class ValidationFailed(val errors: List<PipelineRepoValidationError>) : PipelineSyncResult()

    /** Anything else (missing pipeline, IO failure, parse error). */
    data class Failure(val message: String) : PipelineSyncResult()
}

/** Canonical directory and extension constants for the PIPELINE_PROJECT repository layout. */
object PipelineRepoLayout {
    const val PIPELINES_DIR = "pipelines"
    const val YAML_EXTENSION = ".yaml"
    const val YML_EXTENSION = ".yml"

    /** Whether [path] is a pipeline definition file this sync owns. */
    fun isPipelineFile(path: String): Boolean =
        path.startsWith("$PIPELINES_DIR/") &&
            (path.endsWith(YAML_EXTENSION) || path.endsWith(YML_EXTENSION))
}

/** Canonical path for a pipeline file given its file name (without extension). */
fun pipelineRepoPath(fileName: String): String =
    "${PipelineRepoLayout.PIPELINES_DIR}/$fileName${PipelineRepoLayout.YAML_EXTENSION}"
