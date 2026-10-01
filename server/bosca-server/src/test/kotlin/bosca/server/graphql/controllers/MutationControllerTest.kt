package bosca.server.graphql.controllers

import bosca.ai.agents.graphql.AgentToolsMutation
import bosca.ai.agents.graphql.AgentsMutation
import bosca.ai.agents.graphql.McpServersMutation
import bosca.ai.chat.graphql.ChatSessionsMutation
import bosca.ai.models.graphql.ModelsMutation
import bosca.ai.prompt.graphql.PromptsMutation
import bosca.analytics.graphql.AnalyticsMutation
import bosca.backup.graphql.BackupsMutation
import bosca.cache.CacheManager
import bosca.cdn.CdnManager
import bosca.community.graphql.CommunityMutation
import bosca.configuration.graphql.ConfigurationsMutation
import bosca.content.graphql.ContentMutation
import bosca.content.state.graphql.WorkflowStatesMutation
import bosca.content.timeevent.graphql.TimeEventMutation
import bosca.content.transition.graphql.TransitionsMutation
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.graphql.GraphQLService
import bosca.graphql.MutationRoot
import bosca.graphql.persistedqueries.PersistedQueriesMutation
import bosca.installer.graphql.PackagesMutation
import bosca.profile.organization.graphql.OrganizationsMutation
import bosca.profile.profile.graphql.ProfilesMutation
import bosca.scheduler.graphql.SchedulerMutation
import bosca.scripting.graphql.ScriptsMutation
import bosca.security.graphql.SecurityMutation
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.sharedqueue.jobs.JobQueue
import bosca.storage.graphql.StorageSystemsMutation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class MutationControllerTest {

    private val cacheManager = mockk<CacheManager>()
    private val cdnManager = mockk<CdnManager>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val graphQLService = mockk<GraphQLService>()
    private val graphQLServiceProvider = mockk<ObjectProvider<GraphQLService>>()
    private val authContext = mockk<AuthenticationContext>()

    private lateinit var controller: MutationController

    @BeforeTest
    fun setup() {
        coEvery { graphQLServiceProvider.get() } returns graphQLService
        controller = MutationController(
            cacheManager,
            cdnManager,
            groupEvaluator,
            graphQLServiceProvider,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `clearCache verifies SA group then clears cache and persisted queries`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } returns Unit
        coEvery { cacheManager.clearAll() } returns Unit
        every { graphQLService.clearPersistedQueries() } returns Unit

        val result = controller.clearCache(authContext)

        assertTrue(result)
        coVerify { cacheManager.clearAll() }
        coVerify { graphQLServiceProvider.get() }
        coVerify { graphQLService.clearPersistedQueries() }
    }

    @Test
    fun `clearCache throws when not SA group`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } throws SecurityException("Access denied")

        try {
            controller.clearCache(authContext)
            error("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Access denied", e.message)
        }
    }

    @Test
    fun `clearCdnCache verifies SA group then clears CDN`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } returns Unit
        coEvery { cdnManager.clearCache() } returns true

        val result = controller.clearCdnCache(authContext)

        assertTrue(result)
        coVerify { cdnManager.clearCache() }
    }

    @Test
    fun `clearCdnCache returns false when CDN clear fails`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } returns Unit
        coEvery { cdnManager.clearCache() } returns false

        val result = controller.clearCdnCache(authContext)

        assertEquals(false, result)
    }

    @Test
    fun `expireAllJobs verifies SA group then expires all job queues`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } returns Unit
        val jobQueue1 = mockk<JobQueue>()
        val jobQueue2 = mockk<JobQueue>()
        coEvery { jobQueue1.expireAllJobs() } returns Unit
        coEvery { jobQueue2.expireAllJobs() } returns Unit

        val provider1 = mockk<ObjectProvider<JobQueue>>()
        val provider2 = mockk<ObjectProvider<JobQueue>>()
        every { provider1.type } returns JobQueue::class
        every { provider2.type } returns JobQueue::class
        coEvery { provider1.get() } returns jobQueue1
        coEvery { provider2.get() } returns jobQueue2

        ProviderRegistry.register(JobQueue::class, provider1, "queue1")
        ProviderRegistry.register(JobQueue::class, provider2, "queue2")

        val result = controller.expireAllJobs(authContext)

        assertTrue(result)
        coVerify { jobQueue1.expireAllJobs() }
        coVerify { jobQueue2.expireAllJobs() }
    }

    @Test
    fun `expireAllJobs with no job queues succeeds`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } returns Unit

        val result = controller.expireAllJobs(authContext)

        assertTrue(result)
    }

    @Test
    fun `expireAllJobs throws when not SA group`() = runTest {
        every { groupEvaluator.verifyHasSaGroup(authContext) } throws SecurityException("Access denied")

        try {
            controller.expireAllJobs(authContext)
            error("Expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("Access denied", e.message)
        }
    }

    @Test
    fun `field methods return correct singleton objects`() {
        assertEquals(SecurityMutation, controller.security())
        assertEquals(StorageSystemsMutation, controller.storageSystems())
        assertEquals(ProfilesMutation, controller.profiles())
        assertEquals(OrganizationsMutation, controller.organizations())
        assertEquals(ContentMutation, controller.content())
        assertEquals(ConfigurationsMutation, controller.configurations())
        assertEquals(CommunityMutation, controller.community())
        assertEquals(PersistedQueriesMutation, controller.persistedQueries())
        assertEquals(ChatSessionsMutation, controller.chatSessions())
        assertEquals(PromptsMutation, controller.prompts())
        assertEquals(ModelsMutation, controller.models())
        assertEquals(ScriptsMutation, controller.scripts())
        assertEquals(WorkflowStatesMutation, controller.states())
        assertEquals(TransitionsMutation, controller.transitions())
        assertEquals(AnalyticsMutation, controller.analytics())
        assertEquals(SchedulerMutation, controller.scheduler())
        assertEquals(AgentsMutation, controller.agents())
        assertEquals(AgentToolsMutation, controller.agentTools())
        assertEquals(McpServersMutation, controller.mcpServers())
        assertEquals(PackagesMutation, controller.packages())
        assertEquals(TimeEventMutation, controller.timeEvents())
        assertEquals(BackupsMutation, controller.backups())
    }
}
