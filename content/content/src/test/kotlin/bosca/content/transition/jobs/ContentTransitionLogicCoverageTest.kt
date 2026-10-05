package bosca.content.transition.jobs

import bosca.content.collection.model.Collection
import bosca.content.collection.model.ContentItem
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
import kotlinx.serialization.json.JsonElement
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Supplementary coverage for [ContentTransitionLogic]. The sibling
 * [ContentTransitionLogicTest] exhaustively covers the [ContentItem]/[bosca.content.metadata.model.Metadata]
 * paths through `execute` and the `is Metadata` arm of the private `chainToPublished` `when`.
 *
 * This file closes the two remaining branches of that `when`:
 * - the `is ICollection` arm (exercised with a real [Collection] item), and
 * - the terminal `else -> error(...)` arm (exercised with a bespoke [ContentItem] that is
 *   neither [bosca.content.metadata.model.Metadata] nor an `ICollection`).
 *
 * Both arms live inside `chainToPublished`, which calls `connection().commitTransaction()`, so a
 * real [ConnectionPool] backed by a mocked JDBC [Connection] is installed in the coroutine context
 * exactly as the sibling test does.
 */
class ContentTransitionLogicCoverageTest {

    private val principal = Principal(id = UUID.random())
    private val authContext = ImpersonatedAuthenticationContext(principal, emptyList())
    private val transitioner = mockk<Transitioner>()

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
    // chainToPublished: the `is ICollection` arm
    // ------------------------------------------------------------------

    @Test
    fun `advertised collection chains to published via transitioner with collection id`() = runBlocking {
        withConnection {
            val collectionService = mockk<TransitioningService<Collection>>()
            val id = UUID.random()
            val item = collection(id = id, workflowStateId = "draft", pendingId = "advertised")
            val advertised = collection(id = id, workflowStateId = "advertised", pendingId = null)
            val publishedItem = collection(id = id, workflowStateId = "published", pendingId = null)

            coEvery { collectionService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns publishedItem

            val result = ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = collectionService,
                getWorkflowStateValid = { it.workflowStateValid },
                languageTag = "es",
            )

            coVerify(exactly = 1) { transitioner.beginTransition(any(), any(), any()) }
            assertEquals(id, requestSlot.captured.collectionId)
            assertEquals("es", requestSlot.captured.languageTag)
            assertEquals("published", requestSlot.captured.stateId)
            assertEquals("Advertisement Complete", requestSlot.captured.status)
            assertNull(requestSlot.captured.metadataId)
            assertSame(publishedItem, result)
        }
    }

    @Test
    fun `advertised collection with null language tag passes null to transitioner`() = runBlocking {
        withConnection {
            val collectionService = mockk<TransitioningService<Collection>>()
            val item = collection(workflowStateId = "draft", pendingId = "advertised")
            val advertised = collection(workflowStateId = "advertised", pendingId = null)

            coEvery { collectionService.setPendingStateComplete(item, any(), any()) } returns advertised
            val requestSlot = slot<BeginTransitionInput>()
            coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

            ContentTransitionLogic.execute(
                item = item,
                principal = principal,
                authenticationContext = authContext,
                transitioner = transitioner,
                transitioningService = collectionService,
                getWorkflowStateValid = { null },
                languageTag = null,
            )

            assertNull(requestSlot.captured.languageTag)
            // No published epoch attribute => no future delay computed.
            assertNull(requestSlot.captured.stateValid)
        }
    }

    // ------------------------------------------------------------------
    // chainToPublished: the terminal `else -> error(...)` arm
    // ------------------------------------------------------------------

    @Test
    fun `advertised item that is neither metadata nor collection errors`(): Unit = runBlocking {
        withConnection {
            val service = mockk<TransitioningService<ContentItem>>()
            val incoming = UnsupportedContentItem(workflowStateId = "draft", pendingId = "advertised")
            val advertised = UnsupportedContentItem(workflowStateId = "advertised", pendingId = null)

            coEvery { service.setPendingStateComplete(incoming, any(), any()) } returns advertised

            val error = assertFailsWith<IllegalStateException> {
                ContentTransitionLogic.execute(
                    item = incoming,
                    principal = principal,
                    authenticationContext = authContext,
                    transitioner = transitioner,
                    transitioningService = service,
                    getWorkflowStateValid = { null },
                    languageTag = null,
                )
            }

            assertEquals(true, error.message?.startsWith("unsupported content item type:"))
            coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun collection(
        id: UUID = UUID.random(),
        workflowStateId: String = "draft",
        pendingId: String? = null,
        attributes: JsonElement? = null,
    ) = Collection(
        id = id,
        name = "test",
        languageTag = "en",
        attributes = attributes,
        workflowStateId = workflowStateId,
        workflowStatePendingId = pendingId,
    )

    /**
     * A [ContentItem] implementation that is deliberately neither
     * [bosca.content.metadata.model.Metadata] nor an `ICollection`, used to drive the terminal
     * `else` arm of the private `chainToPublished` `when`.
     */
    private class UnsupportedContentItem(
        override val workflowStateId: String,
        pendingId: String?,
    ) : ContentItem {
        override val id: UUID = UUID.random()
        override val version: Int? = 1
        override val languageTag: String? = "en"
        override val attributes: JsonElement? = null
        override val itemAttributes: JsonElement? = null
        override val workflowStatePendingId: String? = pendingId
        override val ready: OffsetDateTime? = OffsetDateTime.now()
    }
}
