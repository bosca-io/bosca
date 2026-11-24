package bosca.sharedqueue.jobs.postgres

import bosca.db.migrations.Migration

/** Schema owned by the PostgreSQL job queue, registered by each composing application. */
class PostgresJobQueueMigration : Migration {
    override val schema: String = "sharedqueue"
    override val resources: List<String> = listOf("V1__postgres_job_queue.sql", "V2__postgres_job_queue_claim_index.sql")
}
