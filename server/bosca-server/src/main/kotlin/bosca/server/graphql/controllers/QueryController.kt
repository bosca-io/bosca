package bosca.server.graphql.controllers

import bosca.ai.agents.graphql.AgentTools
import bosca.ai.agents.graphql.Agents
import bosca.ai.agents.graphql.McpServers
import bosca.ai.chat.graphql.ChatSessions
import bosca.ai.graphql.Ai
import bosca.ai.models.graphql.Models
import bosca.ai.prompt.graphql.Prompts
import bosca.analytics.graphql.Analytics
import bosca.backup.graphql.Backups
import bosca.chat.graphql.Chat
import bosca.collaboration.graphql.Collaboration
import bosca.community.graphql.Community
import bosca.configuration.graphql.Configurations
import bosca.content.graphql.Content
import bosca.content.state.graphql.WorkflowStates
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import bosca.content.transition.graphql.Transitions
import bosca.events.graphql.Events
import bosca.graphql.GraphQLController
import bosca.graphql.persistedqueries.PersistedQueries
import bosca.graphql.QueryRoot
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.graphql.Packages
import bosca.languages.graphql.Languages
import bosca.profile.organization.graphql.Organizations
import bosca.profile.profile.graphql.Profiles
import bosca.pipelines.graphql.Pipelines
import bosca.feeds.graphql.Feeds
import bosca.recommendations.graphql.RecommendationQuery
import bosca.scheduler.graphql.Scheduler
import bosca.scripting.graphql.Scripts
import bosca.localization.graphql.Localization
import bosca.search.graphql.Search
import bosca.security.graphql.Security
import bosca.calendar.graphql.Calendars
import bosca.devices.graphql.Devices
import bosca.meilisearch.admin.graphql.MeilisearchAdmin
import bosca.nats.admin.graphql.NatsAdmin
import bosca.postgres.admin.graphql.PostgresAdmin
import bosca.experimentation.graphql.ExclusionLayers
import bosca.experimentation.graphql.Experiments
import bosca.experimentation.graphql.FeatureFlags
import bosca.artifacts.admin.graphql.ArtifactsAdmin
import bosca.forms.graphql.FormSchemas
import bosca.git.graphql.Git
import bosca.segmentation.graphql.Segments
import bosca.segmentation.graphql.Campaigns
import bosca.storage.graphql.StorageSystems
import bosca.workops.controller.WorkOps

object Query : QueryRoot

@TypeController
class QueryController(
    private val timeEventService: TimeEventService
) : GraphQLController<Query> {

    @Field
    fun server() = Server

    @Field
    fun content() = Content

    @Field
    fun security() = Security

    @Field
    fun search() = Search

    @Field
    fun profiles() = Profiles

    @Field
    fun organizations() = Organizations

    @Field
    fun analytics() = Analytics

    @Field
    fun configurations() = Configurations

    @Field
    fun community() = Community

    @Field
    fun chat() = Chat

    @Field
    fun collaboration() = Collaboration

    @Field
    fun persistedQueries() = PersistedQueries

    @Field
    fun storageSystems() = StorageSystems

    @Field
    fun languages() = Languages

    @Field
    fun prompts() = Prompts

    @Field
    fun models() = Models

    @Field
    fun states() = WorkflowStates

    @Field
    fun transitions() = Transitions

    @Field
    fun scheduler() = Scheduler

    @Field
    fun packages() = Packages

    @Field
    fun events() = Events

    @Field
    fun ai() = Ai

    @Field
    fun agents() = Agents

    @Field
    fun agentTools() = AgentTools

    @Field
    fun chatSessions() = ChatSessions

    @Field
    fun mcpServers() = McpServers

    @Field
    fun scripts() = Scripts

    @Field
    fun pipelines() = Pipelines

    @Field
    fun feeds() = Feeds

    @Field
    fun recommendation() = RecommendationQuery

    @Field
    fun localization() = Localization

    @Field
    fun backups() = Backups

    @Field
    fun calendars() = Calendars

    @Field
    fun devices() = Devices

    @Field
    fun segments() = Segments

    @Field
    fun campaigns() = Campaigns

    @Field
    fun featureFlags() = FeatureFlags

    @Field
    fun experiments() = Experiments

    @Field
    fun exclusionLayers() = ExclusionLayers

    @Field
    fun meilisearchAdmin() = MeilisearchAdmin

    @Field
    fun natsAdmin() = NatsAdmin

    @Field
    fun postgresAdmin() = PostgresAdmin

    @Field
    fun artifactsAdmin() = ArtifactsAdmin

    @Field
    fun formSchemas() = FormSchemas

    @Field
    fun workOps() = WorkOps

    @Field
    fun gateway() = bosca.gateway.controller.GatewayNamespace

    @Field
    suspend fun timeEventTypes(): List<TimeEventType> = timeEventService.getTypes()

    @Field
    suspend fun timeEventType(id: String): TimeEventType? = timeEventService.getType(id)

    @Field
    fun git(): Git = Git

    @Field
    fun communications(): bosca.communications.graphql.CommunicationsQueries = bosca.communications.graphql.CommunicationsQueries
}
