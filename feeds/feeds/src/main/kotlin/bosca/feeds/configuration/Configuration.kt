package bosca.feeds.configuration

import bosca.db.migrations.Migration
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import bosca.feeds.installer.FeedsGroupsInstaller
import bosca.feeds.migration.FeedsMigration
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner

/**
 * DI providers for the feeds implementation module.
 *
 * The interface-returning providers (`Migration`, `PackageInstaller`, `JobQueue`, `JobRunner`) are
 * **named**: an unnamed `@Provider` returning an interface would clobber that type's single registry
 * slot. Naming registers them as distinct keyed providers the platform aggregates via `findAll` /
 * resolves by `@ProviderName`.
 */
@Providers
class Configuration {

    @Provider(name = "feeds-migrations")
    fun migration(): Migration = FeedsMigration()

    @Provider(name = "feeds-groups")
    fun feedsGroupsInstaller(securityService: SecurityService): PackageInstaller =
        FeedsGroupsInstaller(securityService)

    /** Installs the feeds admin group at startup. */
    @Provider(name = "feeds")
    fun feedsPackage(): PackageInstallation = PackageInstallation(
        key = "feeds",
        name = "Feeds",
        versions = listOf(
            PackageInstallationVersion(version = "1.0.0", installerNames = listOf("feeds-groups")),
        ),
    )

    /** The feeds job queue (`@JobDefinition(queue = FeedsJobQueueNames.feedsJobQueue)` resolves to this). */
    @Provider(singleton = true, name = FeedsJobQueueNames.feedsJobQueue)
    fun feedsJobQueue(factory: JobQueueFactory): JobQueue = factory.create(FeedsJobQueueNames.feedsQueue)

    /** The runner that drains the feeds queue (enabled via `runners.enabled` on bosca-runner). */
    @Provider(singleton = true, name = FeedsJobQueueNames.feedsRunner)
    fun feedsJobQueueRunner(
        @ProviderName(FeedsJobQueueNames.feedsJobQueue) queue: JobQueue,
        distributedLockFactory: DistributedLockFactory,
        errorCapture: ObjectProvider<ErrorCapture>,
    ): JobRunner = JobRunner(queue, 100, distributedLockFactory, errorCapture)
}
