package bosca.atlas.server

import bosca.di.provide
import bosca.di.provideProvider
import bosca.git.ci.configuration.GitCiModule
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.configuration.PipelinesModule
import bosca.scheduler.configuration.SchedulerConfiguration
import bosca.scheduler.runner.SchedulerRunner
import bosca.server.BoscaApplication
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.JobQueueNames

/** Starts only the selected queue consumers and their worker-owned listeners. */
internal suspend fun BoscaApplication.startAtlasWorkers(configuration: AtlasConfiguration) {
    if (!configuration.runnerEnabled) return
    val locks = provide<DistributedLockFactory>()
    val errors = provideProvider<ErrorCapture>()
    for (name in configuration.runnerQueues) {
        val providerName = when (name) {
            "git" -> "git"
            "pipelines" -> PipelinesJobQueueNames.jobQueue
            "common" -> JobQueueNames.commonJobQueue
            else -> error("Unknown Atlas runner queue: $name")
        }
        val runner = JobRunner(provide<JobQueue>(providerName), configuration.runnerConcurrency, locks, errors)
        onShutdown { runner.shutdown() }
        runner.run()
        log.info("Atlas job consumer started: {} (concurrency {})", name, configuration.runnerConcurrency)
    }
    if (configuration.runnerListeners) {
        install(GitCiModule())
        install(PipelinesModule())
    }
    if (provide<SchedulerConfiguration>().enabled) {
        val scheduler = provide<SchedulerRunner>()
        onShutdown { scheduler.stop() }
        scheduler.start()
        log.info("Atlas scheduler started")
    }
}
