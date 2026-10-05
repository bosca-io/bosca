package bosca.search.index

import bosca.di.annotation.ProviderName
import bosca.search.configuration.JobQueueNames
import bosca.search.jobs.InitializeExecutor
import bosca.search.jobs.InitializeJob
import bosca.search.service.IndexInitializerJobFactory
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue

class IndexInitializerJobFactoryImpl(
    @ProviderName(JobQueueNames.indexJobQueue)
    private val jobQueue: JobQueue
) : IndexInitializerJobFactory {


    override suspend fun enqueueJob(storageSystemId: UUID) {
        InitializeJob(storageSystemId).enqueue(
            jobQueue,
            InitializeExecutor::class
        )
    }
}