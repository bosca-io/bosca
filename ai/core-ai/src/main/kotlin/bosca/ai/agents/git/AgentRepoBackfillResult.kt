package bosca.ai.agents.git

import kotlinx.serialization.Serializable

/**
 * GraphQL output for a single validation error inside an AGENT_PROJECT backfill response.
 * Wire-shape twin of the internal [RepoValidationError] (which is used by the pure
 * validator) — kept separate so adding `@Serializable` to one doesn't ripple through
 * the validator's tests.
 */
@Serializable
data class AgentRepoValidationError(val path: String, val message: String)

/**
 * GraphQL result for an `AgentRepoMutation.backfill` operation. Flattens the internal
 * [SyncResult] sealed class into a single output type so GraphQL clients see one shape.
 *
 * - `ok=true` ⇒ all entries succeeded; [commitSha] holds the last commit's SHA.
 * - `ok=false` ⇒ at least one entry failed; consult [validationErrors] and [errorMessage].
 */
@Serializable
data class AgentRepoBackfillResult(
    val ok: Boolean,
    val commitSha: String? = null,
    val validationErrors: List<AgentRepoValidationError>? = null,
    val errorMessage: String? = null,
)
