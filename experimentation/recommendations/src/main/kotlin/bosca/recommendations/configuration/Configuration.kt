package bosca.recommendations.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.recommendations.ml.TfServingConfiguration
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner

/**
 * Registers infrastructure providers for the recommendations module: the Flyway
 * migration definition, the NATS/Redis job queue for async strategy evaluation
 * and model training, and the job runner that processes enqueued recommendation jobs.
 */
@Providers
class Configuration {

    /** Provides the always-on content-model endpoint independently of recommendation-strategy activation. */
    @Provider(singleton = true)
    fun tfServingConfiguration(application: BoscaApplication) = with(application.environment.config) {
        val defaults = TfServingConfiguration()
        TfServingConfiguration(
            url = propertyOrNull("recommendations.tfServing.url")?.getString() ?: defaults.url,
            modelName = propertyOrNull("recommendations.tfServing.modelName")?.getString() ?: defaults.modelName,
            contentModelName = propertyOrNull("recommendations.tfServing.contentModelName")?.getString()
                ?: defaults.contentModelName,
            timeoutSeconds = propertyOrNull("recommendations.tfServing.timeoutSeconds")?.getString()?.toIntOrNull()
                ?: defaults.timeoutSeconds,
        )
    }

    @Provider(name = "recommendations-migrations")
    fun migration(): Migration = RecommendationsMigration()

    @Provider(singleton = true, name = JobQueueNames.recommendationsJobQueue)
    fun recommendationsJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.recommendationsQueue)

    @Provider(name = JobQueueNames.recommendationsRunner)
    fun recommendationsJobQueueRunner(
        @ProviderName(JobQueueNames.recommendationsJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)
}
