@file:OptIn(ExperimentalAtomicApi::class)

package bosca.content.transition.jobs

import bosca.content.collection.model.ContentItem
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.TransitioningService
import bosca.content.transition.service.Transitioner
import bosca.db.ConnectionFactory
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.security.model.Principal
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.DelayException
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [ContentTransitionLogic], the shared execution logic that both
 * [MetadataTransitionExecutor] and [CollectionTransitionExecutor] delegate to.
 *
 * Uses a real [ConnectionPool] backed by a mocked JDBC [Connection] so that
 * `connection().commitTransaction()` calls succeed without a database.
 */
class ContentTransitionLogicTest {

    private val principal = Principal(id = UUID.random())
    private val authContext = ImpersonatedAuthenticationContext(principal, emptyList())
    private val transitioner = mockk<Transitioner>()
    private val transitioningService = mockk<TransitioningService<Metadata>>()

    private lateinit var pool: ConnectionPool
    private var currentAutoCommit = true

    @BeforeTest
    fun setUp() {
        val rawConnection = mockk<Connection>(relaxed = true)
        currentAutoCommit = true
        every { rawConnection.autoCommit } answers { currentAutoCommit }
        every { rawConnection.autoCommit = any() } answers { currentAutoCommit = firstArg() }
        every { rawConnection.isValid(any()) } returns true
        every { rawConnection.isClosed } returns false
        every { rawConnection.commit() } just Runs
        every { rawConnection.rollback() } just Runs
        every { rawConnection.clearWarnings() } just Runs
        every { rawConnection.transactionIsolation } returns Connection.TRANSACTION_READ_COMMITTED
        every { rawConnection.transactionIsolation = any() } just Runs
        every { rawConnection.isReadOnly } returns false
        every { rawConnection.isReadOnly = any() } just Runs

        val factory = mockk<ConnectionFactory>(relaxed = true)
        every { factory.key } returns "test"
        every { factory.maxConnections } returns 1
        every { factory.create() } returns rawConnection

        pool = ConnectionPool(factory)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        pool.close()
    }

    private suspend fun <T> withConnection(block: suspend () -> T): T {
        val cm = ConnectionManager(pool)
        val em = EventManager()
        return withContext(cm.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Deleted items are short-circuited
    // ------------------------------------------------------------------

    @Test
    fun `deleted metadata is returned immediately without state changes`() = runBlocking {
        withConnection {
            val deleted = metadata(deleted = true, workflowStateId = "draft", pendingId = "published")

            val result = executeSimple(deleted)

            assertSame(deleted, result)
            coVerify(exactly = 0) { transitioningService.setPendingStateComplete(any(), any(), any()) }
        }
    }

    // ------------------------------------------------------------------
    // processing → draft auto-transition
    // ------------------------------------------------------------------

    @Test
    fun `pending with processing pending completes then transitions to draft`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "pending", pendingId = "processing")
            val afterProcessingComplete = metadata(workflowStateId = "processing", pendingId = null)
            val afterDraftPending = metadata(workflowStateId = "processing", pendingId = "draft")
            val afterDraftComplete = metadata(workflowStateId = "draft", pendingId = null)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns afterProcessingComplete
            coEvery { transitioningService.setPendingState(afterProcessingComplete, "draft", any(), any(), any(), any()) } returns afterDraftPending
            coEvery { transitioningService.setPendingStateComplete(afterDraftPending, any(), any()) } returns afterDraftComplete

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertEquals("draft", result.workflowStateId)
            coVerify(exactly = 2) { transitioningService.setPendingStateComplete(any(), any(), any()) }
        }
    }

    @Test
    fun `pending with no pending id returns item without modifications`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "pending", pendingId = null)

            val result = executeSimple(item)

            assertSame(item, result)
        }
    }

    // ------------------------------------------------------------------
    // No pending state → idempotent return
    // ------------------------------------------------------------------

    @Test
    fun `non-pending state with no pending id returns item as-is`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "draft", pendingId = null)

            val result = executeSimple(item)

            assertSame(item, result)
        }
    }

    @Test
    fun `approval state with no pending id returns item as-is`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "approval", pendingId = null)

            val result = executeSimple(item)

            assertSame(item, result)
        }
    }

    // ------------------------------------------------------------------
    // Redelivery self-healing: advertised with nothing pending re-chains
    // ------------------------------------------------------------------

    @Test
    fun `redelivered advertised item with no pending re-runs the publish chain`() = runBlocking {
        withConnection {
            // Simulates a redelivery after the advertised completion committed but the
            // chain failed: the item is settled in "advertised" with nothing pending,
            // so the publish must be re-scheduled instead of silently succeeding.
            val publishedEpoch = System.currentTimeMillis() + 60 * 60 * 1000
            val attrs = JsonObject(mapOf("published" to JsonPrimitive(publishedEpoch)))
            val item = metadata(workflowStateId = "advertised", pendingId = null, attributes = attrs)

            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns item

            executeSimple(item)

            val request = requestSlot.captured
            assertEquals("published", request.stateId)
            assertEquals(item.id, request.metadataId)
            assertTrue(
                request.stateValid != null && request.stateValid!! > OffsetDateTime.now(),
                "publish must be re-scheduled for the future published epoch"
            )
            coVerify(exactly = 0) { transitioningService.setPendingStateComplete(any(), any(), any()) }
        }
    }

    // ------------------------------------------------------------------
    // stateValid delay enforcement
    // ------------------------------------------------------------------

    @Test
    fun `stateValid far in the future throws DelayException`(): Unit = runBlocking {
        withConnection {
            val futureValid = OffsetDateTime.now().plusHours(1)
            val item = metadata(workflowStateId = "draft", pendingId = "published")

            assertFailsWith<DelayException> {
                executeSimple(item, stateValid = futureValid)
            }
            Unit
        }
    }

    @Test
    fun `stateValid in the past does not throw`() = runBlocking {
        withConnection {
            val pastValid = OffsetDateTime.now().minusHours(1)
            val completed = metadata(workflowStateId = "published", pendingId = null)
            val item = metadata(workflowStateId = "draft", pendingId = "published")

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns completed

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { pastValid },
                languageTag = null,
            )

            assertEquals("published", result.workflowStateId)
        }
    }

    @Test
    fun `stateValid within 1 second jitter threshold does not throw`() = runBlocking {
        withConnection {
            val barelyFuture = OffsetDateTime.now().plusNanos(500_000_000)
            val completed = metadata(workflowStateId = "published", pendingId = null)
            val item = metadata(workflowStateId = "draft", pendingId = "published")

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns completed

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { barelyFuture },
                languageTag = null,
            )

            assertEquals("published", result.workflowStateId)
        }
    }

    @Test
    fun `null stateValid does not throw`() = runBlocking {
        withConnection {
            val completed = metadata(workflowStateId = "published", pendingId = null)
            val item = metadata(workflowStateId = "draft", pendingId = "published")

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns completed

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertEquals("published", result.workflowStateId)
        }
    }

    // ------------------------------------------------------------------
    // Normal transition completes pending state
    // ------------------------------------------------------------------

    @Test
    fun `transition passes correct status and principal to setPendingStateComplete`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "draft", pendingId = "published")
            val completed = metadata(workflowStateId = "published", pendingId = null)

            val statusSlot = slot<String>()
            val principalSlot = slot<Principal>()
            coEvery { transitioningService.setPendingStateComplete(item, capture(statusSlot), capture(principalSlot)) } returns completed

            ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertEquals("Transition Complete", statusSlot.captured)
            assertSame(principal, principalSlot.captured)
        }
    }

    // ------------------------------------------------------------------
    // Advertised → published chaining
    // ------------------------------------------------------------------

    @Test
    fun `advertised state chains to published transition via transitioner`() = runBlocking {
        withConnection {
            val publishedEpoch = System.currentTimeMillis() + 120_000
            val attrs = JsonObject(mapOf("published" to JsonPrimitive(publishedEpoch)))
            val item = metadata(workflowStateId = "draft", pendingId = "advertised", attributes = attrs)
            val advertised = metadata(workflowStateId = "advertised", pendingId = null, attributes = attrs)
            val publishedItem = metadata(workflowStateId = "published", pendingId = null)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns publishedItem

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            coVerify(exactly = 1) { transitioner.beginTransition(any(), any(), any()) }
            assertEquals("published", requestSlot.captured.stateId)
            assertEquals("Advertisement Complete", requestSlot.captured.status)
            assertSame(publishedItem, result)
        }
    }

    @Test
    fun `advertised chain passes metadata id and version to transitioner`() = runBlocking {
        withConnection {
            val id = UUID.random()
            val attrs = JsonObject(mapOf("published" to JsonPrimitive(System.currentTimeMillis() + 60_000)))
            val item = metadata(id = id, version = 7, workflowStateId = "draft", pendingId = "advertised", attributes = attrs)
            val advertised = metadata(id = id, version = 7, workflowStateId = "advertised", pendingId = null, attributes = attrs)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

            ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertEquals(id, requestSlot.captured.metadataId)
            assertEquals(7, requestSlot.captured.version)
        }
    }

    @Test
    fun `advertised chain with no published epoch sets null stateValid`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "draft", pendingId = "advertised", attributes = JsonObject(emptyMap()))
            val advertised = metadata(workflowStateId = "advertised", pendingId = null)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

            ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertSame(null, requestSlot.captured.stateValid)
        }
    }

    @Test
    fun `advertised chain with past published epoch sets null stateValid`() = runBlocking {
        withConnection {
            val pastPublished = System.currentTimeMillis() - 60_000
            val attrs = JsonObject(mapOf("published" to JsonPrimitive(pastPublished)))
            val item = metadata(workflowStateId = "draft", pendingId = "advertised", attributes = attrs)
            val advertised = metadata(workflowStateId = "advertised", pendingId = null, attributes = attrs)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

            ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertSame(null, requestSlot.captured.stateValid)
        }
    }

    @Test
    fun `advertised chain with future published epoch sets stateValid`() = runBlocking {
        withConnection {
            val futurePublished = System.currentTimeMillis() + 300_000
            val attrs = JsonObject(mapOf("published" to JsonPrimitive(futurePublished)))
            val item = metadata(workflowStateId = "draft", pendingId = "advertised", attributes = attrs)
            val advertised = metadata(workflowStateId = "advertised", pendingId = null, attributes = attrs)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

            ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertTrue(requestSlot.captured.stateValid != null)
        }
    }

    @Test
    fun `non-advertised completed state does not chain to published`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "draft", pendingId = "published")
            val published = metadata(workflowStateId = "published", pendingId = null)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns published

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
            assertEquals("published", result.workflowStateId)
        }
    }

    @Test
    fun `draft to draft transition does not chain`() = runBlocking {
        withConnection {
            val item = metadata(workflowStateId = "approval", pendingId = "draft")
            val draft = metadata(workflowStateId = "draft", pendingId = null)

            coEvery { transitioningService.setPendingStateComplete(item, any(), any()) } returns draft

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = transitioningService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
            assertEquals("draft", result.workflowStateId)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        workflowStateId: String = "draft",
        pendingId: String? = null,
        attributes: kotlinx.serialization.json.JsonElement? = null,
        deleted: Boolean = false,
    ) = Metadata(
        id = id,
        version = version,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        workflowStatePendingId = pendingId,
        attributes = attributes,
        ready = OffsetDateTime.now(),
        deleted = deleted,
    )

    private suspend fun executeSimple(
        item: Metadata,
        stateValid: OffsetDateTime? = null,
    ): ContentItem {
        return ContentTransitionLogic.execute(
            item = item,
            principal = principal,
            authenticationContext = authContext,
            transitioner = transitioner,
            transitioningService = transitioningService,
            getWorkflowStateValid = { stateValid },
            languageTag = null,
        )
    }
}
