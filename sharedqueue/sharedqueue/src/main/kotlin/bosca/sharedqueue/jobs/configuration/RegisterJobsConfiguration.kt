package bosca.sharedqueue.jobs.configuration

import bosca.di.provide
import bosca.di.provideProvider
import bosca.di.provides
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.jobs.MultiJobExecutor
import bosca.sharedqueue.jobs.jobs.MultiJobExecutorEnqueuer
import bosca.sharedqueue.jobs.listeners.NotifyJobCompleteListener
import bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener
import bosca.sharedqueue.jobs.listeners.NotifyParentListener
import bosca.observability.ErrorCapture
import bosca.sharedqueue.jobs.listeners.RunChildOnCompleteListener

object JobQueueNames {
    const val commonJobQueue = "commonQueue"
    const val commonRunner = "commonQueueRunner"
    const val commonQueue = "common"
}

fun RegisterJobsConfiguration() {
    provides { NotifyParentListener() }
    provides { RunChildOnCompleteListener(provide()) }
    provides { NotifyJobCompleteListener(provide()) }
    provides { NotifyJobStatusListener(provide()) }

    provides<MultiJobExecutorEnqueuer>(singleton = true) { MultiJobExecutorEnqueuer() }
    provides<JobConfigurationEnqueuer>(name = "multi-job", singleton = true) { provide<MultiJobExecutorEnqueuer>() }
    provides<MultiJobExecutor> { MultiJobExecutor() }
    provides<JobQueue>(name = JobQueueNames.commonJobQueue, singleton = true) { provide<JobQueueFactory>().create(JobQueueNames.commonQueue) }
    provides<JobRunner>(name = JobQueueNames.commonRunner, singleton = true) {
        JobRunner(
            provide(name = JobQueueNames.commonJobQueue),
            100,
            provide(),
            provideProvider<ErrorCapture>(),
        )
    }
}
