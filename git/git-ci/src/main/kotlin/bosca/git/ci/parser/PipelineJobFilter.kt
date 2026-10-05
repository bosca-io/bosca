package bosca.git.ci.parser

import bosca.git.model.JobDefinition
import org.slf4j.LoggerFactory

/**
 * Creation-time job selection: evaluates each job's `if:` condition against the run context and
 * drops the jobs whose condition is false — how one pipeline file serves multiple triggers with
 * disjoint job subsets (`if: event == 'promotion'`, `if: contains(release.projects,
 * 'server')`). A condition that fails to evaluate drops the job (and logs) rather than running work
 * the author gated.
 *
 * A DEFERRED condition — one that references `needs.*` or uses `always()`/`failure()`/`cancelled()`
 * — cannot be decided at creation (no dependency has run yet), so the job is KEPT with its
 * condition intact; the server evaluates it once the job's dependencies are terminal (failure
 * routing). A consumed (creation-time) condition is cleared from the surviving job, so
 * downstream a non-null condition always means "deferred".
 *
 * `needs:` edges that point at dropped jobs are pruned from the survivors — otherwise a surviving
 * job would wait forever on a dependency that was never created.
 */
object PipelineJobFilter {

    fun filter(
        jobs: Map<String, JobDefinition>,
        context: ExpressionContext,
        parser: PipelineExpressionParser = PipelineExpressionParser(),
    ): Map<String, JobDefinition> {
        val kept = jobs.mapNotNull { (name, job) ->
            val condition = job.condition ?: return@mapNotNull name to job
            if (isDeferred(condition)) return@mapNotNull name to job
            val passed = try {
                parser.evaluateBoolean(condition, context)
            } catch (e: Exception) {
                log.warn("Failed to evaluate if condition '{}' on job '{}': {}", condition, name, e.message)
                false
            }
            if (passed) name to job.copy(condition = null) else null
        }.toMap()
        if (kept.size == jobs.size && jobs.keys == kept.keys) {
            return kept
        }
        return pruneNeeds(kept)
    }

    /** Drops `needs:` references to jobs absent from [jobs] — run after any selection pass. */
    fun pruneNeeds(jobs: Map<String, JobDefinition>): Map<String, JobDefinition> =
        jobs.mapValues { (_, job) ->
            if (job.needs.all { it in jobs }) job else job.copy(needs = job.needs.filter { it in jobs })
        }

    /**
     * True when [condition] depends on how the job's dependencies FINISHED — undecidable at run
     * creation. Substring detection is deliberately broad: a false positive merely defers the
     * evaluation to the settle point, where the creation-time context is a subset of what's known.
     */
    fun isDeferred(condition: String): Boolean =
        "needs." in condition ||
            "always()" in condition ||
            "failure()" in condition ||
            "cancelled()" in condition

    private val log = LoggerFactory.getLogger(PipelineJobFilter::class.java)
}
