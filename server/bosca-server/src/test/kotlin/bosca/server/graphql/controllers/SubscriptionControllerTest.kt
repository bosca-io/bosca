package bosca.server.graphql.controllers

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.model.ChatSessionStatus
import bosca.ai.chat.service.ChatHistoryService
import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.LiveSession
import bosca.content.collection.events.CollectionUpdated
import bosca.content.metadata.events.METADATA_UPLOAD_PROGRESS_CHANNEL
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.events.UploadProgress
import bosca.content.timeevent.events.TIME_EVENT_CHANGED_CHANNEL
import bosca.content.timeevent.events.TimeEventChanged
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.service.PipelineRunService
import bosca.pubsub.Message
import bosca.pubsub.PubSubService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

@OptIn(InternalDI::class, ExperimentalUuidApi::class)
class SubscriptionControllerTest {

    private val pubSubService = mockk<PubSubService>()
    private val groups = mockk<bosca.security.service.GroupEvaluator>(relaxed = true)
    private val liveSessionStream = mockk<LiveSessionsService>()
    private val chatHistoryService = mockk<ChatHistoryService>()
    private val connectionPool = mockk<ConnectionPool>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val authContext = mockk<AuthenticationContext>()

    private val testChannelId = UUID.random()
    private val testSessionId = UUID.random()
    private val testPrincipalId = UUID.random()

    private lateinit var controller: SubscriptionController

    @BeforeTest
    fun setup() {
        every { connectionPool.connection() } returns connectionManager

        ProviderRegistry.register(
            ConnectionPool::class,
            object : ObjectProvider<ConnectionPool> {
                override val type = ConnectionPool::class
                override suspend fun get() = connectionPool
            }
        )
        ProviderRegistry.register(
            ChatHistoryService::class,
            object : ObjectProvider<ChatHistoryService> {
                override val type = ChatHistoryService::class
                override suspend fun get() = chatHistoryService
            }
        )

        controller = SubscriptionController(pubSubService, groups, liveSessionStream)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun testSession(principalId: UUID = testPrincipalId) = ChatSession(
        id = testSessionId,
        principalId = principalId,
        agentKey = "hello"
    )

    private fun testPrincipal(id: UUID = testPrincipalId) = AuthenticatedPrincipal(
        Principal(id = id),
        emptyList()
    )

    // --- aiChatHistory tests ---

    @Test
    fun `aiChatHistory with missing session throws`() = runTest {
        coEvery { chatHistoryService.getSession(testSessionId) } returns null

        val flow = controller.aiChatHistory(authContext, testSessionId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    @Test
    fun `aiChatHistory with wrong principal throws`() = runTest {
        val session = testSession(principalId = testPrincipalId)
        val otherPrincipal = testPrincipal(UUID.random())
        coEvery { chatHistoryService.getSession(testSessionId) } returns session
        every { authContext.principal() } returns otherPrincipal

        val flow = controller.aiChatHistory(authContext, testSessionId)
        assertFailsWith<SecurityException> {
            flow.toList()
        }
    }

    @Test
    fun `aiChatHistory with valid session emits messages`() = runTest {
        val session = testSession()
        val principal = testPrincipal()
        val historyMessage = mockk<ChatHistoryMessage>()

        coEvery { chatHistoryService.getSession(testSessionId) } returns session
        every { authContext.principal() } returns principal
        every { chatHistoryService.subscribeToMessages(testSessionId) } returns flowOf(historyMessage)

        val flow = controller.aiChatHistory(authContext, testSessionId)
        val results = flow.toList()

        assertEquals(1, results.size)
        assertEquals(historyMessage, results[0])
    }

    // --- aiChatStatus tests ---

    @Test
    fun `aiChatStatus with missing session throws`() = runTest {
        coEvery { chatHistoryService.getSession(testSessionId) } returns null

        val flow = controller.aiChatStatus(authContext, testSessionId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    @Test
    fun `aiChatStatus with valid session emits statuses`() = runTest {
        val session = testSession()
        val principal = testPrincipal()

        coEvery { chatHistoryService.getSession(testSessionId) } returns session
        every { authContext.principal() } returns principal
        every { chatHistoryService.subscribeToStatuses(testSessionId) } returns flowOf(ChatSessionStatus.STREAMING)

        val flow = controller.aiChatStatus(authContext, testSessionId)
        val results = flow.toList()

        assertEquals(1, results.size)
        assertEquals(ChatSessionStatus.STREAMING, results[0])
    }

    // --- aiChatProcessing tests ---

    @Test
    fun `aiChatProcessing with missing session throws`() = runTest {
        coEvery { chatHistoryService.getSession(testSessionId) } returns null

        val flow = controller.aiChatProcessing(authContext, testSessionId)
        assertFailsWith<IllegalArgumentException> {
            flow.toList()
        }
    }

    // --- metadata and collection subscription tests ---

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `metadata merges channels and maps to MetadataEvent`() = runTest {
        val update = MetadataUpdated(id = testChannelId, version = 1)
        val msg = Message("metadata.state", update)

        every { pubSubService.subscribe(any(), any<DeserializationStrategy<MetadataUpdated>>()) } returns flowOf(msg)

        val flow = controller.metadata(authContext)
        val results = flow.toList()

        assertEquals(2, results.size)
        assertEquals(testChannelId, results[0].id)
        assertEquals(1, results[0].version)
        verify { groups.verifyHasScope(authContext, ApiTokenScopes.CONTENT_VIEW.name) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `collection merges channels and maps to CollectionEvent`() = runTest {
        val update = CollectionUpdated(id = testChannelId, languageTag = "en")
        val msg = Message("collection.state", update)

        every { pubSubService.subscribe(any(), any<DeserializationStrategy<CollectionUpdated>>()) } returns flowOf(msg)

        val flow = controller.collection(authContext)
        val results = flow.toList()

        assertEquals(2, results.size)
        assertEquals(testChannelId, results[0].id)
        assertEquals("en", results[0].languageTag)
        verify { groups.verifyHasScope(authContext, ApiTokenScopes.COLLECTIONS_VIEW.name) }
    }

    @Test
    fun `metadata rejects a token without content view before subscribing`() = runTest {
        every {
            groups.verifyHasScope(authContext, ApiTokenScopes.CONTENT_VIEW.name)
        } throws bosca.security.service.SecurityException("missing content view")

        assertFailsWith<bosca.security.service.SecurityException> {
            controller.metadata(authContext).toList()
        }
        verify(exactly = 0) {
            pubSubService.subscribe(any(), any<DeserializationStrategy<MetadataUpdated>>())
        }
    }

    // --- uploadProgress tests ---

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `uploadProgress requires content view and emits matching progress`() = runTest {
        val metadataId = UUID.random()
        val progress = UploadProgress(metadataId = metadataId, bytesUploaded = 5L, totalBytes = 10L)
        every {
            pubSubService.subscribe(
                eq(METADATA_UPLOAD_PROGRESS_CHANNEL),
                any<DeserializationStrategy<UploadProgress>>(),
            )
        } returns flowOf(Message(METADATA_UPLOAD_PROGRESS_CHANNEL, progress))

        val results = controller.uploadProgress(authContext, metadataId).toList()

        assertEquals(1, results.size)
        assertEquals(5L, results.single().bytesUploaded)
        assertEquals(10L, results.single().totalBytes)
        verify { groups.verifyHasScope(authContext, ApiTokenScopes.CONTENT_VIEW.name) }
    }

    // --- timeEventChanged tests ---

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `timeEventChanged filters by metadataId and maps to event`() = runTest {
        val metadataId = UUID.random()
        val changed = TimeEventChanged(metadataId = metadataId, metadataVersion = 1, eventCount = 5)
        val msg = Message(TIME_EVENT_CHANGED_CHANNEL, changed)

        every {
            pubSubService.subscribe(
                eq(TIME_EVENT_CHANGED_CHANNEL),
                any<DeserializationStrategy<TimeEventChanged>>()
            )
        } returns flowOf(msg)

        val flow = controller.timeEventChanged(authContext, metadataId)
        val results = flow.toList()

        assertEquals(1, results.size)
        assertEquals(metadataId, results[0].metadataId)
        assertEquals(1, results[0].metadataVersion)
        assertEquals(5, results[0].eventCount)
        verify { groups.verifyHasScope(authContext, ApiTokenScopes.CONTENT_VIEW.name) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `timeEventChanged filters out events for other metadata`() = runTest {
        val targetId = UUID.random()
        val otherId = UUID.random()
        val changed = TimeEventChanged(metadataId = otherId, metadataVersion = 1, eventCount = 3)
        val msg = Message(TIME_EVENT_CHANGED_CHANNEL, changed)

        every {
            pubSubService.subscribe(
                eq(TIME_EVENT_CHANGED_CHANNEL),
                any<DeserializationStrategy<TimeEventChanged>>()
            )
        } returns flowOf(msg)

        val flow = controller.timeEventChanged(authContext, targetId)
        val results = flow.toList()

        assertEquals(0, results.size)
    }

    // --- pipelineRun tests ---

    @Test
    fun `pipelineRun verifies the admin group and emits the run's updates`() = runTest {
        val runId = UUID.random()
        val update = PipelineRunUpdate(runId = runId, runStatus = PipelineRunStatus.OK)
        every {
            pubSubService.subscribe(
                eq(PipelineRunService.runEventChannel(runId)),
                any<DeserializationStrategy<PipelineRunUpdate>>(),
            )
        } returns flowOf(Message(PipelineRunService.runEventChannel(runId), update))

        val results = controller.pipelineRun(authContext, runId).toList()

        assertEquals(1, results.size)
        assertEquals(runId, results[0].runId)
        assertEquals(PipelineRunStatus.OK, results[0].runStatus)
        verify { groups.verifyHasAdminGroup(authContext) }
    }

    @Test
    fun `pipelineRun rejects a non-admin caller before subscribing`() = runTest {
        every { groups.verifyHasAdminGroup(authContext) } throws bosca.security.service.SecurityException("not an admin")

        assertFailsWith<bosca.security.service.SecurityException> { controller.pipelineRun(authContext, UUID.random()).toList() }
        verify(exactly = 0) { pubSubService.subscribe(any(), any<DeserializationStrategy<PipelineRunUpdate>>()) }
    }

    // --- liveSessions tests ---

    @Test
    fun `liveSessions streams the primitive's sessions for an authenticated caller`() = runTest {
        every { authContext.principal() } returns testPrincipal()
        val session = LiveSession(sessionId = "s1", latitude = 40.7, longitude = -74.0, appVersion = "1.0.0")
        every { liveSessionStream.subscribe("app-1", "1.0.0") } returns flowOf(session)

        val results = controller.liveSessions(authContext, "app-1", "1.0.0").toList()

        assertEquals(1, results.size)
        assertEquals("s1", results[0].sessionId)
        assertEquals("1.0.0", results[0].appVersion)
    }

    @Test
    fun `liveSessions with no appId streams every application`() = runTest {
        every { authContext.principal() } returns testPrincipal()
        val session = LiveSession(sessionId = "s1", latitude = 40.7, longitude = -74.0, appId = "app-1", appVersion = "1.0.0")
        every { liveSessionStream.subscribe(null, null) } returns flowOf(session)

        val results = controller.liveSessions(authContext, null, null).toList()

        assertEquals(1, results.size)
        assertEquals("app-1", results[0].appId)
    }

    @Test
    fun `liveSessions rejects an unauthenticated caller before subscribing`() = runTest {
        every { authContext.principal() } returns null

        assertFailsWith<SecurityException> { controller.liveSessions(authContext, "app-1", null).toList() }
        verify(exactly = 0) { liveSessionStream.subscribe(any(), any()) }
    }
}
