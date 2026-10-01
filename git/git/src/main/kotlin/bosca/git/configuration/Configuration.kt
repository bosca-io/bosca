package bosca.git.configuration

import bosca.db.migrations.Migration
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.DfsRefRepository
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryAccessEvaluator
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.git.service.BranchProtectionService
import bosca.git.service.PullRequestService
import bosca.git.service.RefUpdateNotifier
import bosca.git.service.RefUpdateNotifierImpl
import bosca.git.service.RepositoryContentValidatorRegistry
import bosca.git.service.RepositoryService
import bosca.git.service.WebhookService
import bosca.git.transport.GitPostReceiveHook
import bosca.git.transport.GitPreReceiveHook
import bosca.profile.profile.service.ProfileService
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.storage.service.ObjectStorageService

object JobQueueNames {
    const val gitJobQueue = "git"
}

@Providers
class Configuration {

    @Provider(name = "git-migrations")
    fun migration(): Migration = GitMigration()

    @Provider(singleton = true, name = JobQueueNames.gitJobQueue)
    fun gitJobQueue(factory: JobQueueFactory): JobQueue = factory.create(JobQueueNames.gitJobQueue)

    @Provider(singleton = true)
    fun dfsRepoManager(
        objectStorage: ObjectStorageService,
        packRepository: DfsPackRepository,
        refRepository: DfsRefRepository
    ): BoscaDfsRepositoryManager = BoscaDfsRepositoryManager(objectStorage, packRepository, refRepository)

    @Provider(singleton = true)
    fun repositoryPermissionEvaluator(
        service: RepositoryService,
        securityService: SecurityService,
        groupEvaluator: GroupEvaluator
    ) = RepositoryPermissionEvaluator(service, securityService, groupEvaluator)

    @Provider(singleton = true)
    fun repositoryAccessEvaluator(evaluator: RepositoryPermissionEvaluator): RepositoryAccessEvaluator = evaluator

    @Provider(singleton = true)
    fun contentValidatorRegistry() = RepositoryContentValidatorRegistry()

    @Provider
    fun preReceiveHook(
        branchProtectionService: BranchProtectionService,
        repositoryService: RepositoryService,
        validatorRegistry: RepositoryContentValidatorRegistry,
    ) = GitPreReceiveHook(branchProtectionService, repositoryService, validatorRegistry)

    @Provider(singleton = true)
    fun refUpdateNotifier(
        repositoryRepository: GitRepositoryRepository,
        packRepository: DfsPackRepository,
        webhookService: WebhookService,
        taskCommitRefRepository: TaskCommitReferenceRepository,
        pullRequestService: PullRequestService,
        profileService: ProfileService,
        securityService: SecurityService,
    ): RefUpdateNotifier = RefUpdateNotifierImpl(
        repositoryRepository,
        packRepository,
        webhookService,
        taskCommitRefRepository,
        pullRequestService,
        profileService,
        securityService,
    )

    @Provider
    fun postReceiveHook(refUpdateNotifier: RefUpdateNotifier) = GitPostReceiveHook(refUpdateNotifier)
}
