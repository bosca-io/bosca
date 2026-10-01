package bosca.server.graphql.controllers

import bosca.ai.agents.graphql.AgentTools
import bosca.ai.agents.graphql.Agents
import bosca.ai.agents.graphql.McpServers
import bosca.ai.chat.graphql.ChatSessions
import bosca.ai.models.graphql.Models
import bosca.ai.prompt.graphql.Prompts
import bosca.analytics.graphql.Analytics
import bosca.backup.graphql.Backups
import bosca.community.graphql.Community
import bosca.configuration.graphql.Configurations
import bosca.content.graphql.Content
import bosca.content.state.graphql.WorkflowStates
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import bosca.content.transition.graphql.Transitions
import bosca.graphql.persistedqueries.PersistedQueries
import bosca.installer.graphql.Packages
import bosca.languages.graphql.Languages
import bosca.profile.organization.graphql.Organizations
import bosca.profile.profile.graphql.Profiles
import bosca.scheduler.graphql.Scheduler
import bosca.scripting.graphql.Scripts
import bosca.search.graphql.Search
import bosca.security.graphql.Security
import bosca.storage.graphql.StorageSystems
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryControllerTest {

    private val timeEventService = mockk<TimeEventService>()
    private val controller = QueryController(timeEventService)

    @Test
    fun `timeEventTypes delegates to service`() = runTest {
        val types = listOf(mockk<TimeEventType>(), mockk<TimeEventType>())
        coEvery { timeEventService.getTypes() } returns types

        val result = controller.timeEventTypes()

        assertEquals(types, result)
    }

    @Test
    fun `timeEventType with valid id returns type`() = runTest {
        val type = mockk<TimeEventType>()
        coEvery { timeEventService.getType("test-id") } returns type

        val result = controller.timeEventType("test-id")

        assertEquals(type, result)
    }

    @Test
    fun `timeEventType with invalid id returns null`() = runTest {
        coEvery { timeEventService.getType("missing") } returns null

        val result = controller.timeEventType("missing")

        assertNull(result)
    }

    @Test
    fun `field methods return correct singleton objects`() {
        assertEquals(Server, controller.server())
        assertEquals(Content, controller.content())
        assertEquals(Security, controller.security())
        assertEquals(Search, controller.search())
        assertEquals(Profiles, controller.profiles())
        assertEquals(Organizations, controller.organizations())
        assertEquals(Analytics, controller.analytics())
        assertEquals(Configurations, controller.configurations())
        assertEquals(Community, controller.community())
        assertEquals(PersistedQueries, controller.persistedQueries())
        assertEquals(StorageSystems, controller.storageSystems())
        assertEquals(Languages, controller.languages())
        assertEquals(Prompts, controller.prompts())
        assertEquals(Models, controller.models())
        assertEquals(WorkflowStates, controller.states())
        assertEquals(Transitions, controller.transitions())
        assertEquals(Scheduler, controller.scheduler())
        assertEquals(Packages, controller.packages())
        assertEquals(Agents, controller.agents())
        assertEquals(AgentTools, controller.agentTools())
        assertEquals(ChatSessions, controller.chatSessions())
        assertEquals(McpServers, controller.mcpServers())
        assertEquals(Scripts, controller.scripts())
        assertEquals(Backups, controller.backups())
    }
}
