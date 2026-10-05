package bosca.git.ci.jobs

import bosca.di.provide
import bosca.git.ci.service.PipelineRequirementChecker
import bosca.git.model.RequirementCheckJob
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/**
 * Scheduled sweep over requirement-gated CI jobs. Idempotent against the registry
 * publish-event listener — both converge on the same guarded satisfied-stamp — and the only path
 * that fails a job past its requirement deadline, so a gated job can never hang silently even if
 * every event is missed.
 */
@JobDefinition(RequirementCheckJob::class, "git", "pipeline-requirement-check")
class RequirementCheckExecutor : AbstractJobExecutor<RequirementCheckJob>(RequirementCheckJob.serializer()) {

    override suspend fun execute() {
        provide<PipelineRequirementChecker>().checkAwaiting()
    }
}
