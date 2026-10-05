package bosca.content.transition.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.transition.service.Transitioner
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Coverage for the file-private [EventSuppressingMetadataTransitioningService] in
 * [MetadataTransitionExecutor]. That wrapper is unreachable directly (file-private), so it is
 * exercised through [MetadataTransitionExecutor.Companion.execute], which constructs the wrapper
 * and hands it to [ContentTransitionLogic.execute] as the intermediate `transitioningService`.
 *
 * [ContentTransitionLogic] only routes two calls to the intermediate service (both inside the
 * processing→draft dance): `setPendingStateComplete` (pending+processing) and `setPendingState`
 * (processing→null → draft). Those two are the covered wrapper overrides. `setPendingStateFailed`
 * and `setState` are never invoked by the transition logic and are therefore unreachable from the
 * only public entry point (see `untestable`).
 *
 * The wrapper's `withEventManager { eventManager().filter = DisabledEventManagerFilter; delegate.* }`
 * body installs its own [bosca.events.EventManager] in the coroutine context, so no ambient event
 * manager is required. The scenario keeps the item out of the advertised→published chain (final
 * pendingId == null) so no database connection is needed.
 */
class MetadataTransitionExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val transitioner = mockk<Transitioner>()

    private val principal = Principal(id = UUID.random())

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        workflowStateId: String = "draft",
        pendingId: String? = null,
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
        attributes = null,
        ready = OffsetDateTime.now(),
        deleted = false,
    )

    // ------------------------------------------------------------------
    // Happy path: both wrapper overrides are exercised through the
    // processing→draft dance, and event dispatch is suppressed.
    // ------------------------------------------------------------------

    @Test
    fun `companion execute drives both suppressing wrapper overrides`() = runTest {
        val id = UUID.random()
        val incoming = metadata(id = id, workflowStateId = "pending", pendingId = "processing")
        val afterComplete = metadata(id = id, workflowStateId = "processing", pendingId = null)
        val afterDraft = metadata(id = id, workflowStateId = "draft", pendingId = null)

        // Wrapper.setPendingStateComplete -> delegate.setPendingStateComplete(item, status, principal)
        val completeStatus = slot<String>()
        coEvery {
            metadataService.setPendingStateComplete(incoming, capture(completeStatus), any())
        } returns afterComplete

        // Wrapper.setPendingState -> delegate.setPendingState(item, toStateId, status, valid, principal, notifyEvent)
        val toStateId = slot<String>()
        val notifyEvent = slot<Boolean>()
        coEvery {
            metadataService.setPendingState(afterComplete, capture(toStateId), any(), any(), any(), capture(notifyEvent))
        } returns afterDraft

        val auth = ImpersonatedAuthenticationContext(principal, emptyList())

        val result = MetadataTransitionExecutor.execute(metadataService, transitioner, incoming, auth)

        // handleProcessingTransition returned an item with a null pending id, so execute() returns
        // before the completion step or the advertised→published chain.
        assertSame(afterDraft, result)
        assertEquals("draft", (result as Metadata).workflowStateId)
        assertEquals("draft", toStateId.captured)
        assertEquals(false, notifyEvent.captured)
        assertEquals("Transition Complete", completeStatus.captured)

        coVerify(exactly = 1) { metadataService.setPendingStateComplete(incoming, any(), any()) }
        coVerify(exactly = 1) { metadataService.setPendingState(afterComplete, "draft", any(), any(), any(), any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `wrapper setPendingState forwards the principal to the delegate`() = runTest {
        val id = UUID.random()
        val incoming = metadata(id = id, workflowStateId = "pending", pendingId = "processing")
        val afterComplete = metadata(id = id, workflowStateId = "processing", pendingId = null)
        val afterDraft = metadata(id = id, workflowStateId = "draft", pendingId = null)

        coEvery { metadataService.setPendingStateComplete(incoming, any(), any()) } returns afterComplete

        val forwardedPrincipal = slot<Principal>()
        coEvery {
            metadataService.setPendingState(afterComplete, "draft", any(), any(), capture(forwardedPrincipal), any())
        } returns afterDraft

        val auth = ImpersonatedAuthenticationContext(principal, emptyList())

        MetadataTransitionExecutor.execute(metadataService, transitioner, incoming, auth)

        assertSame(principal, forwardedPrincipal.captured)
    }

    // ------------------------------------------------------------------
    // Error path in the companion: no principal available.
    // ------------------------------------------------------------------

    @Test
    fun `companion execute throws when authentication context has no principal`() = runTest {
        val incoming = metadata(workflowStateId = "pending", pendingId = "processing")
        val auth = AuthenticationContext(null, null)

        assertFailsWith<IllegalStateException> {
            MetadataTransitionExecutor.execute(metadataService, transitioner, incoming, auth)
        }

        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
    }
}
