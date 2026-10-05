package bosca.security.jobs

import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.configuration.JobQueueNames

/** Executes the durable periodic cleanup of expired authentication records. */
@JobDefinition(
    definition = DeleteExpiredSecurityTokensJob::class,
    queue = JobQueueNames.commonJobQueue,
    name = "delete-expired-security-tokens",
)
class DeleteExpiredSecurityTokensExecutor(
    private val securityService: SecurityService,
) : AbstractJobExecutor<DeleteExpiredSecurityTokensJob>(DeleteExpiredSecurityTokensJob.serializer()) {
    override suspend fun execute() {
        securityService.deleteExpiredRefreshToken()
    }
}
