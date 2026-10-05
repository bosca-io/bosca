package bosca.forms.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner

@Providers
class Configuration {

    @Provider(name = JobQueueNames.formsRunner)
    fun formsJobQueueRunner(
        @ProviderName(JobQueueNames.formsJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)

    @Provider(singleton = true, name = JobQueueNames.formsJobQueue)
    fun formsJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.formsQueueName)

    @Provider(singleton = true)
    fun formSchemaPermissionEvaluator(
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator,
        service: FormSchemaService,
    ) = FormSchemaPermissionEvaluator(
        service,
        securityService,
        groupEvaluator,
    )
}
