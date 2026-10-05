package bosca.search.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.search.configuration.JobQueueNames
import bosca.search.index.IndexInitializer
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(InitializeJob::class, JobQueueNames.indexJobQueue, "initialize-index")
class InitializeExecutor : AbstractJobExecutor<InitializeJob>(InitializeJob.serializer()) {

    override suspend fun execute() {
        val configuration = getJobDefinition()
        val initializer = provide<IndexInitializer>()
        initializer.execute(configuration.id)
        log.info("Initialized index for storage system ${configuration.id}")
    }

    companion object {

        private val log = LoggerFactory.getLogger(InitializeJob::class.java)
    }
}
