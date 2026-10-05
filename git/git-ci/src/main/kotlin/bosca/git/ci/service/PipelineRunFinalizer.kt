package bosca.git.ci.service

import bosca.git.model.AgentStatus
import bosca.git.model.PipelineRunStatus
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.serialization.UUID

/**
 * Centralizes the run-level state transitions that must happen after a
 * job reaches a terminal state: cancelling blocked downstream jobs,
 * computing the aggregate run status, and updating the agent. Called
 * from both the GraphQL mutation (agent reports completion) and the
 * cleanup executor (server-side timeout / orphan reaping).
 *
 * Downstream jobs become claimable as soon as their dependencies are
 * marked SUCCESS — runners discover them via `claimJob`'s
 * `findNextAvailable` query, so the finalizer doesn't need to push.
 */
class PipelineRunFinalizer(
    private val jobService: PipelineJobService,
    private val runService: PipelineRunService,
    private val agentService: PipelineAgentService
) {

    /**
     * Finalizes run-level state after a job reaches a terminal status.
     * When [releaseAgent] is true (the default — used when the agent
     * itself reports completion), the assigned agent is transitioned
     * back to ONLINE (or removed for ephemeral agents). Server-side
     * callers (cleanup executor, reaper) should pass false and manage
     * agent status themselves, since the agent may be dead.
     */
    suspend fun finalizeJob(jobId: UUID, status: PipelineRunStatus, releaseAgent: Boolean = true) {
        val job = jobService.findById(jobId) ?: return

        if (status == PipelineRunStatus.RUNNING) {
            val run = runService.findById(job.pipelineRunId)
            if (run != null && run.status == PipelineRunStatus.QUEUED) {
                runService.updateStatus(run.id, PipelineRunStatus.RUNNING)
            }
            return
        }

        if (status != PipelineRunStatus.SUCCESS &&
            status != PipelineRunStatus.FAILURE &&
            status != PipelineRunStatus.CANCELLED
        ) return

        // SUCCESS reports are verified by the job service before they become terminal.
        val effectiveStatus = job.status

        val run = runService.findById(job.pipelineRunId) ?: return

        if (effectiveStatus == PipelineRunStatus.FAILURE || effectiveStatus == PipelineRunStatus.CANCELLED) {
            jobService.cancelBlockedJobs(run.id)
        }

        // A settled job may be the last dependency a DEFERRED-condition job was waiting on
        // (failure routing) — evaluate them now, before computing run completion, so a
        // condition that skips is already terminal and one that satisfies keeps the run open.
        jobService.evaluateDeferredConditions(run.id)

        // Steps-less GATE jobs whose gates just cleared complete server-side; finalizing each may
        // cascade further gates down the DAG (bounded by its depth).
        for (gate in jobService.completeGateJobs(run.id)) {
            finalizeJob(gate.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        }

        val allJobs = jobService.findByRun(run.id)
        val allTerminal = allJobs.all {
            it.status in TERMINAL_STATUSES
        }
        if (allTerminal) {
            val runStatus = when {
                allJobs.any { it.status == PipelineRunStatus.FAILURE } -> PipelineRunStatus.FAILURE
                allJobs.any { it.status == PipelineRunStatus.CANCELLED } -> PipelineRunStatus.CANCELLED
                else -> PipelineRunStatus.SUCCESS
            }
            // Gate cascades finalize recursively — the deepest frame may already have settled the
            // run, so only write (and re-dispatch events) when the status actually changes.
            if (runService.findById(run.id)?.status != runStatus) {
                runService.updateStatus(run.id, runStatus)
            }
        }

        if (releaseAgent) {
            val agentId = job.agentId
            if (agentId != null) {
                val agent = agentService.findById(agentId)
                if (agent != null && agent.ephemeral) {
                    agentService.deregister(agent.id)
                } else if (agent != null) {
                    agentService.updateStatus(agent.id, AgentStatus.ONLINE)
                }
            }
        }
    }

    companion object {
        private val TERMINAL_STATUSES = listOf(
            PipelineRunStatus.SUCCESS,
            PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED,
            PipelineRunStatus.SKIPPED
        )
    }
}
