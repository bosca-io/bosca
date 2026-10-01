package bosca.server.graphql.controllers

import bosca.ai.agents.graphql.AgentsMutation
import bosca.ai.agents.graphql.AgentToolsMutation
import bosca.ai.agents.graphql.McpServersMutation
import bosca.ai.chat.graphql.ChatSessionsMutation
import bosca.ai.graphql.AiMutation
import bosca.ai.models.graphql.ModelsMutation
import bosca.ai.prompt.graphql.PromptsMutation
import bosca.analytics.graphql.AnalyticsMutation
import bosca.backup.graphql.BackupsMutation
import bosca.cache.CacheManager
import bosca.cdn.CdnManager
import bosca.chat.graphql.ChatMutation
import bosca.collaboration.graphql.CollaborationMutation
import bosca.community.graphql.CommunityMutation
import bosca.configuration.graphql.ConfigurationsMutation
import bosca.content.graphql.ContentMutation
import bosca.content.state.graphql.WorkflowStatesMutation
import bosca.content.timeevent.graphql.TimeEventMutation
import bosca.content.transition.graphql.JobsMutation
import bosca.content.transition.graphql.TransitionsMutation
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.graphql.GraphQLController
import bosca.graphql.GraphQLService
import bosca.graphql.MutationRoot
import bosca.graphql.persistedqueries.PersistedQueriesMutation
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.graphql.PackagesMutation
import bosca.profile.organization.graphql.OrganizationsMutation
import bosca.profile.profile.graphql.ProfilesMutation
import bosca.pipelines.graphql.PipelinesMutation
import bosca.feeds.graphql.FeedsMutation
import bosca.recommendations.graphql.RecommendationMutation
import bosca.scheduler.graphql.SchedulerMutation
import bosca.scripting.graphql.ScriptsMutation
import bosca.languages.graphql.LanguagesMutation
import bosca.localization.graphql.LocalizationMutation
import bosca.security.graphql.SecurityMutation
import bosca.security.service.AuthenticationContext
import bosca.calendar.graphql.CalendarsMutation
import bosca.devices.graphql.DevicesMutation
import bosca.forms.graphql.FormSchemasMutation
import bosca.forms.graphql.FormsMutation
import bosca.meilisearch.admin.graphql.MeilisearchAdminMutation
import bosca.nats.admin.graphql.NatsAdminMutation
import bosca.postgres.admin.graphql.PostgresAdminMutation
import bosca.experimentation.graphql.ExclusionLayersMutation
import bosca.experimentation.graphql.ExperimentsMutation
import bosca.experimentation.graphql.FeatureFlagsMutation
import bosca.artifacts.admin.graphql.ArtifactsAdminMutation
import bosca.git.graphql.GitMutation
import bosca.segmentation.graphql.SegmentsMutation
import bosca.segmentation.graphql.CampaignsMutation
import bosca.security.service.GroupEvaluator
import bosca.sharedqueue.jobs.JobQueue
import bosca.storage.graphql.StorageSystemsMutation

object Mutation : MutationRoot

@TypeController
class MutationController(
    private val cacheManager: CacheManager,
    private val cdnManager: CdnManager,
    private val groupEvaluator: GroupEvaluator,
    private val graphQlService: ObjectProvider<GraphQLService>,
) : GraphQLController<Mutation> {

    @Field
    fun security() = SecurityMutation

    @Field
    fun storageSystems() = StorageSystemsMutation

    @Field
    fun profiles() = ProfilesMutation

    @Field
    fun organizations() = OrganizationsMutation

    @Field
    fun content() = ContentMutation

    @Field
    fun configurations() = ConfigurationsMutation

    @Field
    fun community() = CommunityMutation

    @Field
    fun chat() = ChatMutation

    @Field
    fun collaboration() = CollaborationMutation

    @Field
    fun persistedQueries() = PersistedQueriesMutation

    @Field
    fun chatSessions() = ChatSessionsMutation

    @Field
    fun prompts() = PromptsMutation

    @Field
    fun models() = ModelsMutation

    @Field
    fun scripts() = ScriptsMutation

    @Field
    fun pipelines() = PipelinesMutation

    @Field
    fun feeds() = FeedsMutation

    @Field
    fun recommendation() = RecommendationMutation

    @Field
    fun languages() = LanguagesMutation

    @Field
    fun localization() = LocalizationMutation

    @Field
    fun states() = WorkflowStatesMutation

    @Field
    fun transitions() = TransitionsMutation

    @Field
    fun analytics() = AnalyticsMutation

    @Field
    fun scheduler() = SchedulerMutation

    @Field
    fun jobs() = JobsMutation

    @Field
    fun ai() = AiMutation

    @Field
    fun agents() = AgentsMutation

    @Field
    fun agentTools() = AgentToolsMutation

    @Field
    fun mcpServers() = McpServersMutation

    @Field
    fun packages() = PackagesMutation

    @Field
    fun timeEvents() = TimeEventMutation

    @Field
    fun backups() = BackupsMutation

    @Field
    fun calendars() = CalendarsMutation

    @Field
    fun devices() = DevicesMutation

    @Field
    fun segments() = SegmentsMutation

    @Field
    fun campaigns() = CampaignsMutation

    @Field
    fun featureFlags() = FeatureFlagsMutation

    @Field
    fun experiments() = ExperimentsMutation

    @Field
    fun exclusionLayers() = ExclusionLayersMutation

    @Field
    fun meilisearchAdmin() = MeilisearchAdminMutation

    @Field
    fun natsAdmin() = NatsAdminMutation

    @Field
    fun postgresAdmin() = PostgresAdminMutation

    @Field
    fun artifactsAdmin() = ArtifactsAdminMutation

    @Field
    fun forms() = FormsMutation

    @Field
    fun formSchemasMutation() = FormSchemasMutation

    @Field
    fun workOps() = bosca.workops.controller.WorkOpsMutationsRoot

    @Field
    fun gateway() = bosca.gateway.controller.GatewayMutationsRoot

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
        ProviderRegistry.findAll(JobQueue::class).forEach {
            it.get().expireAllJobs()
        }
        return true
    }

    @OptIn(InternalDI::class)
    @Field
    suspend fun clearJobLocks(authenticationContext: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasSaGroup(authenticationContext)
        ProviderRegistry.findAll(JobQueue::class).forEach {
            it.get().clearAllJobLocks()
        }
        return true
    }

    @Field
    fun git(): GitMutation = GitMutation

    @Field
    fun communications(): bosca.communications.graphql.CommunicationsMutations = bosca.communications.graphql.CommunicationsMutations
}
