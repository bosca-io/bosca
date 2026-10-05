package bosca.workops.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.service.EnvironmentService
import bosca.workops.service.HttpHealthProbe
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * The durable "wait until the deployment is healthy" job a
 * [bosca.workops.pipeline.WaitForHealthyNode] enqueues as a correlated child of the release run.
 *
 * On each delivery the deployment's durable [HealthCheckStatus] is the source of truth: HEALTHY
 * completes the job (the engine then resumes the parked run, passing the deployment through). The
 * probe-once semantics live in [EnvironmentService.probeHealth] — shared with the CI
 * verify-deployment gate, so both observe health identically: a declared
 * `healthCheckUrl` is probed (2xx records HEALTHY); a deployment with no URL waits passively for
 * something that *can* observe health (a Helm Status node, the health-check mutation) to record it.
 * The run service's stuck-suspended sweep bounds the total wait — this is a machine wait, not an
 * approval gate.
 */
@JobDefinition(WaitForHealthyJob::class, "workops", WaitForHealthyExecutor.NAME)
class WaitForHealthyExecutor :
    AbstractJobExecutor<WaitForHealthyJob>(WaitForHealthyJob.serializer()) {

    override suspend fun execute() = awaitHealthy(getJobDefinition().deploymentId)

    /**
     * The job's logic, separated from the [getJobDefinition] plumbing so it is directly
     * unit-testable; [probe] is the HTTP GET (2xx = healthy), injectable for tests.
     */
    suspend fun awaitHealthy(deploymentId: UUID, probe: suspend (String) -> Boolean = HttpHealthProbe::probe) {
        val service = provide<EnvironmentService>()
        if (service.getDeployment(deploymentId) == null) {
            throw FailException("environment deployment $deploymentId not found")
        }
        val status = service.probeHealth(deploymentId, probe)
        if (status != HealthCheckStatus.HEALTHY) throw DelayException(POLL)
        log.info("deployment {} is healthy", deploymentId)
    }

    companion object {
        const val NAME = "environment-wait-healthy"

        /** Re-probe cadence while the deployment is not yet healthy. */
        private val POLL = 30.seconds

        private val log = LoggerFactory.getLogger(WaitForHealthyExecutor::class.java)
    }
}
