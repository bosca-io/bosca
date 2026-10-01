package bosca.git.ci.jobs

import bosca.di.provide
import bosca.git.ci.service.PipelineRunFinalizer
import bosca.git.model.AgentStatus
import bosca.git.model.PipelineRunStatus
import bosca.git.model.TransientAgentCleanupJob
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Periodic CI/CD cleanup. Handles four failure modes:
 *   1. Ephemeral agents whose VM expired or stopped heartbeating — agent is
 *      deregistered, its currently assigned job is failed, and the VM is left for the
 *      orchestrator to garbage-collect.
 *   2. Persistent agents with stale heartbeats (> 5 min) — status is
 *      transitioned to OFFLINE so the UI and queries reflect reality.
 *   3. RUNNING jobs whose assigned agent has stopped heartbeating — the job
 *      is failed so it doesn't sit orphaned forever.
 *   4. RUNNING jobs that have exceeded their configured timeout — failed as
 *      a backstop in case the agent never reported completion.
 */
@JobDefinition(TransientAgentCleanupJob::class, "git", "transient-agent-cleanup")
class TransientAgentCleanupExecutor : AbstractJobExecutor<TransientAgentCleanupJob>(TransientAgentCleanupJob.serializer()) {

    override suspend fun execute() {
        val agentService = provide<PipelineAgentService>()
        val jobService = provide<PipelineJobService>()
        val runService = provide<PipelineRunService>()
        val finalizer = PipelineRunFinalizer(jobService, runService, agentService)

        val expiredAgents = agentService.findExpiredEphemeralAgents()

        for (agent in expiredAgents) {
            log.warn(
                "Transient agent '{}' (id={}, instance={}) has expired or missed heartbeats",
                agent.name, agent.id, agent.instanceId
            )

            agentService.updateStatus(agent.id, AgentStatus.OFFLINE)

            val jobId = agent.jobId
            if (jobId != null) {
                val job = jobService.findById(jobId)
                val assignedToThisAttempt = job?.agentId == agent.id
                val active = job?.status == PipelineRunStatus.QUEUED ||
                    job?.status == PipelineRunStatus.RUNNING
                if (job != null && assignedToThisAttempt && active) {
                    jobService.cancelKubernetesDispatch(job)
                    jobService.updateStatus(
                        jobId, PipelineRunStatus.FAILURE,
                        "Transient agent '${agent.name}' expired or stopped heartbeating before the job finished"
                    )
                    finalizer.finalizeJob(jobId, PipelineRunStatus.FAILURE, releaseAgent = false)
                    log.warn("Failed job {} due to expired agent {}", jobId, agent.id)
                }
            }

            agentService.deregister(agent.id)
        }

        if (expiredAgents.isNotEmpty()) {
            log.info("Cleaned up {} expired transient agents", expiredAgents.size)
        }

        val staleAgents = agentService.findStalePersistentAgents()
        for (agent in staleAgents) {
            log.warn(
                "Persistent agent '{}' (id={}) has stale heartbeat, marking offline",
                agent.name, agent.id
            )
            agentService.updateStatus(agent.id, AgentStatus.OFFLINE)
        }
        if (staleAgents.isNotEmpty()) {
            log.info("Marked {} persistent agents offline due to stale heartbeats", staleAgents.size)
        }

        val reaped = jobService.reapStaleRunningJobs()
        for (job in reaped) {
            log.warn(
                "Failed orphaned/timed-out job {} (name={}, agent={})",
                job.id, job.name, job.agentId
            )
            finalizer.finalizeJob(job.id, PipelineRunStatus.FAILURE, releaseAgent = false)
        }
        if (reaped.isNotEmpty()) {
            log.info("Reaped {} stale running jobs", reaped.size)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TransientAgentCleanupExecutor::class.java)
    }
}
