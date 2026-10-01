package bosca.mux.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.mux.client.MuxClient
import bosca.mux.jobs.MuxJobQueueNames
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import kotlinx.serialization.json.Json

/**
 * Dependency-injection configuration for the Mux integration module.
 *
 * Registers the [MuxClient], the dedicated Mux job queue, and the
 * corresponding job runner so that Mux-related background jobs are
 * processed independently of the main content queue.
 */
@Providers
class Configuration {

    /**
     * Provides a singleton [MuxClient] instance used for all Mux API calls
     * within this integration module.
     */
    @Provider(singleton = true)
    fun muxClient(json: Json) = MuxClient(json = json)

    /**
     * Creates the dedicated job queue that Mux jobs are enqueued to and
     * consumed from.
     */
    @Provider(singleton = true, name = MuxJobQueueNames.muxJobQueue)
    fun muxJobQueue(factory: JobQueueFactory): JobQueue =
        factory.create(MuxJobQueueNames.muxQueue)

    /**
     * Creates the job runner that polls the Mux queue and dispatches
     * jobs to the appropriate executors.
     */
    @Provider(singleton = true, name = MuxJobQueueNames.muxRunner)
    fun muxJobQueueRunner(
        @ProviderName(MuxJobQueueNames.muxJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        10,
        distributedLockFactory,
        errorCapture,
    )
}
