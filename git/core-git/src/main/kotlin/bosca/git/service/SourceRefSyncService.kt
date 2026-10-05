package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Synchronizes git-backed source refs when a push lands on a watched branch.
 * For each source ref pointing at a changed file, reads the new content from
 * the DFS repository and returns update commands for downstream processing.
 * The caller is responsible for writing the new source to the domain entity
 * and triggering cache invalidation via PubSub.
 */
interface SourceRefSyncService : Service {

    /**
     * Finds scripts whose source ref points at a changed file in this push,
     * reads the new content at [afterSha], and returns update commands. The
     * impl computes the diff between [beforeSha] and [afterSha] internally;
     * a zero-id [beforeSha] (branch creation) means every linked file is
     * treated as changed, and a zero-id [afterSha] (branch deletion) yields
     * an empty result.
     */
    suspend fun findAffectedScripts(
        repositoryId: UUID,
        pushedRef: String,
        beforeSha: String,
        afterSha: String
    ): List<ScriptSourceUpdate>

    /**
     * Finds analytics queries whose source ref points at a changed file in
     * this push, reads the new content at [afterSha], and returns update
     * commands. Same diff semantics as [findAffectedScripts].
     */
    suspend fun findAffectedQueries(
        repositoryId: UUID,
        pushedRef: String,
        beforeSha: String,
        afterSha: String
    ): List<QuerySourceUpdate>

    /**
     * Reads every analytics query source ref in [repositoryId] at its
     * branch's current HEAD and returns the resulting updates. Intended
     * for manual backfill / re-sync flows where there is no prior commit
     * to diff against. Source refs whose branch cannot be resolved are
     * skipped (logged), as are binary or missing files.
     */
    suspend fun findAllQueriesAtHead(repositoryId: UUID): List<QuerySourceUpdate>

    /**
     * Reads the analytics query source ref for [queryId] at its branch's
     * current HEAD and returns the update, or null when the query is not
     * linked or the file cannot be read.
     */
    suspend fun findQueryAtHead(queryId: UUID): QuerySourceUpdate?

    /**
     * Reads every script source ref in [repositoryId] at its branch's
     * current HEAD and returns the resulting updates. Same semantics as
     * [findAllQueriesAtHead] but for the scripting module's
     * [bosca.git.model.ScriptSourceRef] table.
     */
    suspend fun findAllScriptsAtHead(repositoryId: UUID): List<ScriptSourceUpdate>

    /**
     * Reads the script source ref for [scriptId] at its branch's current
     * HEAD and returns the update, or null when the script is not linked
     * or the file cannot be read.
     */
    suspend fun findScriptAtHead(scriptId: UUID): ScriptSourceUpdate?
}

data class ScriptSourceUpdate(
    val scriptId: UUID,
    val newSource: String,
    val commitSha: String
)

data class QuerySourceUpdate(
    val queryId: UUID,
    val newQuery: String,
    val commitSha: String
)
