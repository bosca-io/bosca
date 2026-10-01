package bosca.community.ai.configuration

import bosca.community.ai.installer.QuietCompanionInstaller
import bosca.community.configuration.JobQueueNames
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.di.ObjectProvider
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.server.BoscaApplication

@Providers
class CommunityAIConfiguration {

    @Provider(singleton = true, name = "quiet-companion")
    fun quietCompanionInstaller(
        application: BoscaApplication,
        securityService: SecurityService,
        profileService: ProfileService
    ): PackageInstaller = QuietCompanionInstaller(
        securityService,
        profileService,
        application
    )

    @Provider(singleton = true, name = JobQueueNames.communityJobQueue)
    fun communityJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.communityQueue)

    @Provider(singleton = true, name = JobQueueNames.communityRunner)
    fun communityRunner(
        @ProviderName(JobQueueNames.communityJobQueue)
        queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(
        queue,
        100,
        distributedLockFactory,
        errorCapture,
    )

    @Provider(name = "community-ai")
    fun communityAiPackage(): PackageInstallation = PackageInstallation(
        key = "community-ai",
        name = "Community AI",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.1.0",
                installerNames = listOf("quiet-companion")
            )
        )
    )
}
