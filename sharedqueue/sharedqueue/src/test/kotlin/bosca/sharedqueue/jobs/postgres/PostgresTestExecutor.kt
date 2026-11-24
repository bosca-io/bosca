package bosca.sharedqueue.jobs.postgres

import bosca.sharedqueue.jobs.JobExecutor

internal class PostgresTestExecutor : JobExecutor {
    override suspend fun execute() {}
}
