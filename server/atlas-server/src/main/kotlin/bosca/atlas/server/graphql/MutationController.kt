package bosca.atlas.server.graphql

import bosca.cache.CacheManager
import bosca.cdn.CdnManager
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.graphql.GraphQLService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.sharedqueue.jobs.JobQueue
import bosca.git.graphql.GitHubMutation
import bosca.git.graphql.GitMutation
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.graphql.PipelinesMutation
import bosca.profile.profile.graphql.ProfilesMutation
import bosca.scheduler.graphql.SchedulerMutation
import bosca.security.graphql.SecurityMutation

@TypeController
class MutationController(
    private val cacheManager: CacheManager,
    private val cdnManager: CdnManager,
    private val groupEvaluator: GroupEvaluator,
    private val graphQlService: ObjectProvider<GraphQLService>,
) : GraphQLController<Mutation> {
    @Field fun content() = bosca.content.graphql.ContentMutation

    @Field fun collaboration() = bosca.collaboration.graphql.CollaborationMutation

    @Field fun backups() = bosca.backup.graphql.BackupsMutation

    @Field fun models() = bosca.ai.models.graphql.ModelsMutation

    @Field fun storageSystems() = bosca.storage.graphql.StorageSystemsMutation

    @Field fun configurations() = bosca.configuration.graphql.ConfigurationsMutation

    @Field
    suspend fun clearCache(authenticationContext: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasSaGroup(authenticationContext)
        cacheManager.clearAll()
        graphQlService.get().clearPersistedQueries()
        return true
    }

    @Field
    suspend fun clearCdnCache(authenticationContext: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasSaGroup(authenticationContext)
        return cdnManager.clearCache()
    }

    @OptIn(InternalDI::class)
    @Field
    suspend fun expireAllJobs(authenticationContext: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasSaGroup(authenticationContext)
        ProviderRegistry.findAll(JobQueue::class).forEach { it.get().expireAllJobs() }
        return true
    }

    @OptIn(InternalDI::class)
    @Field
    suspend fun clearJobLocks(authenticationContext: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasSaGroup(authenticationContext)
        ProviderRegistry.findAll(JobQueue::class).forEach { it.get().clearAllJobLocks() }
        return true
    }

    @Field fun postgresAdmin() = bosca.postgres.admin.graphql.PostgresAdminMutation

    @Field fun natsAdmin() = bosca.nats.admin.graphql.NatsAdminMutation

    @Field fun meilisearchAdmin() = bosca.meilisearch.admin.graphql.MeilisearchAdminMutation

    @Field fun packages() = bosca.installer.graphql.PackagesMutation

    @Field fun artifactsAdmin() = bosca.artifacts.admin.graphql.ArtifactsAdminMutation

    @Field fun git() = GitMutation

    @Field fun github() = GitHubMutation

    @Field fun security() = SecurityMutation

    @Field fun profiles() = ProfilesMutation

    @Field fun pipelines() = PipelinesMutation

    @Field fun scheduler() = SchedulerMutation
}
