package bosca.analytics.jobs

import bosca.analytics.configuration.JobQueueNames
import bosca.db.ConnectionPool
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

@JobDefinition(SqlJob::class, JobQueueNames.analyticsJobQueue, "analytics-sql-execution")
class SqlJobExecutor : AbstractJobExecutor<SqlJob>(SqlJob.serializer()) {

    override suspend fun execute() {
        val jobConfiguration = getJobDefinition()
        val connectionPool: ConnectionPool = provide(name = "trino-admin")

        connectionPool.connection().useStatement(jobConfiguration.sql) { stmt ->
            stmt.execute()
        }
    }
}
