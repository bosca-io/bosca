package bosca.git.model

import kotlinx.serialization.Serializable

/**
 * Configuration for when a pipeline should be triggered. Stored as JSONB
 * in the pipelines table. Supports branch/path glob filtering for monorepo
 * workflows.
 */
@Serializable
data class PipelineTrigger(
    val type: PipelineTriggerType,
    val branches: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val pathsIgnore: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val cron: String? = null,
    /**
     * For [PipelineTriggerType.PROMOTION]: the environment keys this pipeline promotes to. A
     * promotion run for an environment outside this list is rejected at creation. Empty = any.
     */
    val environments: List<String> = emptyList(),
    /**
     * Execution-time inputs the trigger accepts, keyed by input name. The Start/Promote
     * dialogs render them; submitted values arrive as run parameters under `inputs.<name>`, defaults
     * are applied at run creation, and steps read them as the `inputs.*` expression context.
     */
    val inputs: Map<String, TriggerInput> = emptyMap()
)

/**
 * One declared trigger input: a typed, optionally defaulted value the operator supplies
 * when starting the run. [type] is `string`, `boolean`, `number`, or `choice` ([options] required for
 * choice). An input with no [default] is required — run creation fails without it.
 */
@Serializable
data class TriggerInput(
    val type: String = "string",
    val default: String? = null,
    val description: String? = null,
    val options: List<String> = emptyList()
)

/**
 * Concurrency control for pipeline runs. Runs in the same group cancel
 * each other when [cancelInProgress] is true.
 */
@Serializable
data class PipelineConcurrency(
    val group: String,
    val cancelInProgress: Boolean = false
)
