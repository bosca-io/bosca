package bosca.git.service

import bosca.git.model.RepositoryContentType
import bosca.serialization.UUID

/**
 * Validates the proposed state of an `AGENT_PROJECT` / `SCRIPT_PROJECT` / etc. repository
 * before its push lands in history. Plug-in point used by `GitPreReceiveHook` — each
 * domain module can register a validator for its [contentType] without the hook needing
 * direct knowledge of the module's types.
 *
 * The hook does the JGit tree-walking and hands the validator a [Map] of every relevant
 * file's content. Validators stay JGit-unaware and trivially testable.
 */
interface RepositoryContentValidator {

    /** The [RepositoryContentType] this validator handles. */
    val contentType: RepositoryContentType

    /**
     * Directory prefixes (e.g. `agents/`, `tools/`) the validator cares about. The hook
     * filters the tree to files whose path starts with one of these prefixes before
     * calling [validate]. Empty list means "read the whole tree."
     */
    val pathPrefixes: List<String>

    /**
     * Validate the proposed tree. Receives a `path → content` map of every file in
     * the new tree that matched one of [pathPrefixes]. Returns one error per violation;
     * an empty list means accept.
     */
    suspend fun validate(
        repositoryId: UUID,
        files: Map<String, String>,
    ): List<RepositoryContentValidationError>
}

/** A single validation error attributed to the file at [path]. */
data class RepositoryContentValidationError(val path: String, val message: String)
