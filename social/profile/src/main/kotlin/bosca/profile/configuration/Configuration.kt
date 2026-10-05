package bosca.profile.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.profile.organization.service.OrganizationService
import bosca.profile.persona.repository.ProfileMigration
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.server.BoscaApplication

object JobQueueNames {
    const val profileJobQueue = "profileQueue"
    const val profileRunner = "profileQueueRunner"
    const val profileQueue = "profile"
}

@Providers
class Configuration {

    @Provider(name = "profile-migrations")
    fun migration(): Migration = ProfileMigration()

    @Provider(singleton = true)
    fun socialNotificationConfiguration(application: BoscaApplication): SocialNotificationConfiguration {
        val config = application.environment.config
        val applicationUrl = config.propertyOrNull("social.notifications.url")?.getString()
            ?.takeIf { it.isNotBlank() }
            ?: config.property("app.url").getString()
        val profileApplicationUrl = config.propertyOrNull("social.notifications.profile-url")?.getString()
            ?.takeIf { it.isNotBlank() }
            ?: applicationUrl
        return SocialNotificationConfiguration(applicationUrl, profileApplicationUrl)
    }

    @Provider(singleton = true)
    fun profilePermissionEvaluator(
        service: ProfileService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = ProfilePermissionEvaluator(
        service,
        securityService,
        groupEvaluator
    )

    @Provider(singleton = true)
    fun organizationPermissionEvaluator(
        service: OrganizationService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = OrganizationPermissionEvaluator(
        service,
        securityService,
        groupEvaluator
    )

    @Provider(singleton = true, name = JobQueueNames.profileJobQueue)
    fun profileJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.profileQueue)

    @Provider(name = JobQueueNames.profileRunner)
    fun profileJobQueueRunner(
        @ProviderName(JobQueueNames.profileJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )
}
