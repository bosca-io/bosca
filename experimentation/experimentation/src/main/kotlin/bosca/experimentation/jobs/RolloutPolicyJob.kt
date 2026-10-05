package bosca.experimentation.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Payload for a rollout-controller wake-up.
 *
 * Two wake-up shapes share one job type so the executor can be
 * written once and dispatched from both origins:
 *
 *   1. **Analysis-chained** — [scheduledStepIndex] is `null`. The
 *      analysis executor chains one of these onto the end of every
 *      run when the experiment has an attached policy. Adaptive
 *      modes act on the latest verdict; `SCHEDULED_STEPS` treats
 *      this wake as a no-op because schedule mode is time-driven.
 *   2. **Scheduled** — [scheduledStepIndex] is non-null. Enqueued by
 *      `ExperimentService.setStatus` on DRAFT→RUNNING for
 *      `SCHEDULED_STEPS` experiments, and by the executor itself
 *      after applying a step to schedule the next one. The index
 *      tells the executor which step of the policy's step list
 *      this wake is meant to apply.
 *
 * Separating these two signals keeps the executor stateless — it
 * doesn't have to read "what was the last step index?" from the
 * database, the job payload carries the answer.
 */
@Serializable
data class RolloutPolicyJob(
    @Contextual
    val experimentId: UUID,
    /**
     * Scheduled-step wake index (0-based into the policy's `steps`
     * list), or `null` for analysis-chained wakes.
     */
    val scheduledStepIndex: Int? = null,
) : IJobDefinition
