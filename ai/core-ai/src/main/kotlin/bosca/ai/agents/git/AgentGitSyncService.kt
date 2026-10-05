@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.service.Service
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Bidirectional sync between PostgreSQL rows (agents, agent_tools, mcp_server_registrations,
 * prompts) and Markdown files in an AGENT_PROJECT Git repository. Models the same role
 * `SpecGitSyncService` plays for specs.
 */
interface AgentGitSyncService : Service {

    /** Serialize a single DB row to its `git_path` and commit. Returns the new commit SHA. */
    suspend fun pushToGit(
        entityType: AgentEntityType,
        entityId: Uuid,
        authorName: String,
        authorEmail: String
    ): SyncResult

    /** Parse the four directories at [commitSha], validate, and upsert the DB rows. */
    suspend fun pullFromGit(repositoryId: Uuid, commitSha: String): SyncResult

    /** Webhook entry — calls [pullFromGit] if any file under the four known directories changed. */
    suspend fun onPushEvent(repositoryId: Uuid, beforeSha: String, afterSha: String)

    /** Bulk export: serialize each [BackfillEntry] and commit them all in a single batch. */
    suspend fun backfill(
        repositoryId: Uuid,
        entries: List<BackfillEntry>,
        authorName: String,
        authorEmail: String
    ): SyncResult
}

/** The file-backed entity types covered by AGENT_PROJECT sync. */
enum class AgentEntityType { AGENT, AGENT_TOOL, MCP_SERVER, PROMPT, AGENT_RESOURCE }

/** One entry in a [AgentGitSyncService.backfill] request: where in the repo to write a given DB row. */
data class BackfillEntry(val entityType: AgentEntityType, val entityId: Uuid, val gitPath: String)

/** Outcome of a sync operation. */
sealed class SyncResult {
    /** Sync succeeded. [commitSha] is set when the operation produced a commit. */
    data class Ok(val commitSha: String? = null) : SyncResult()

    /** Pre-validate caught structural problems; nothing was written. */
    data class ValidationFailed(val errors: List<RepoValidationError>) : SyncResult()

    /** Anything else (missing entity, IO failure, parse error). */
    data class Failure(val message: String) : SyncResult()
}
