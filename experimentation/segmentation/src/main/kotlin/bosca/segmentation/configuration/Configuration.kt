package bosca.segmentation.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.segmentation.service.CampaignMessageBuilder
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import kotlinx.serialization.json.Json

@Providers
class Configuration {

    @Provider(name = "segmentation-migrations")
    fun migration(): Migration = SegmentationMigration()

    @Provider
    fun campaignMessageBuilder(json: Json): CampaignMessageBuilder = CampaignMessageBuilder(json)

    @Provider(singleton = true, name = JobQueueNames.segmentationJobQueue)
    fun segmentationJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.segmentationQueue)

    @Provider(name = JobQueueNames.segmentationRunner)
    fun segmentationJobQueueRunner(
        @ProviderName(JobQueueNames.segmentationJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)
}
