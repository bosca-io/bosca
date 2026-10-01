package bosca.git.model

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Periodic re-evaluation of CI jobs gated on artifact requirements: the backstop for
 * a missed registry publish event, and the enforcement point for requirement deadlines (a job
 * still unsatisfied past its deadline fails loudly). The registry's publish-event listener is the
 * low-latency common path; this sweep is the floor.
 *
 * A parameterless tick — the scheduler's `job_parameters` is `{}` and the executor reads nothing
 * from the payload.
 */
@Serializable
class RequirementCheckJob : IJobDefinition
