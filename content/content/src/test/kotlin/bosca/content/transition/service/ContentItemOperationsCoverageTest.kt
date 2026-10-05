package bosca.content.transition.service

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.ContentItem
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transition.model.BeginTransitionInput
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class ContentItemOperationsCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val collectionService = mockk<CollectionService>()
    private val metadataJobHistory = mockk<MetadataJobHistoryService>()
    private val collectionJobHistory = mockk<CollectionJobHistoryService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()

    private val authentication = mockk<AuthenticationContext>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
        ProviderRegistry.clear()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        workflowStateId: String = "draft",
    ) = Metadata(
        id = id,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        version = version,
    )

    private fun collection(
        id: UUID = UUID.random(),
        workflowStateId: String = "draft",
    ) = Collection(
        id = id,
        name = "test",
        languageTag = "en",
        workflowStateId = workflowStateId,
    )

    private fun languageVariant(
        id: UUID = UUID.random(),
        workflowStateId: String = "draft",
    ) = CollectionLanguageVariant(
        id = id,
        languageTag = "fr",
        name = "test",
        workflowStateId = workflowStateId,
    )

    private fun principalAuth(): Principal {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principal
        every { authenticatedPrincipal.id } returns principal.id
        return principal
    }

    private fun authenticatedPrincipal(): AuthenticatedPrincipal {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authenticatedPrincipal.asPrincipal() } returns principal
        every { authenticatedPrincipal.id } returns principal.id
        return authenticatedPrincipal
    }

    private fun request(
        metadataId: UUID? = null,
        collectionId: UUID? = null,
        version: Int? = null,
        languageTag: String? = null,
        stateId: String = "published",
        status: String = "s",
        stateValid: OffsetDateTime? = null,
    ) = BeginTransitionInput(
        metadataId = metadataId,
        collectionId = collectionId,
        version = version,
        languageTag = languageTag,
        stateId = stateId,
        status = status,
        stateValid = stateValid,
    )

    private suspend fun resolve(
        req: BeginTransitionInput,
        item: ContentItem?,
    ): ContentItemOperations =
        ContentItemOperations.resolve(
            req,
            item,
            metadataService,
            collectionService,
            metadataJobHistory,
            collectionJobHistory,
            metadataPermissionEvaluator,
            collectionPermissionEvaluator,
        )

    /** A [ContentItem] that is neither Metadata nor a collection — exercises the unsupported-type arm. */
    private class UnsupportedItem : ContentItem {
        override val id: UUID = UUID.random()
        override val version: Int? = null
        override val languageTag: String? = null
        override val attributes: JsonElement? = null
        override var itemAttributes: JsonElement? = null
        override val workflowStateId: String = "draft"
        override val workflowStatePendingId: String? = null
        override val ready: OffsetDateTime? = null
    }

    // ── resolve: item provided ────────────────────────────────────────────────

    @Test
    fun `resolve with provided Metadata returns metadata operations`() = runTest {
        val md = metadata()
        val ops = resolve(request(), md)
        assertSame(md, ops.item)
        assertEquals("transition-metadata", ops.fallbackJobName)
    }

    @Test
    fun `resolve with provided Collection returns collection operations`() = runTest {
        val col = collection()
        val ops = resolve(request(), col)
        assertSame(col, ops.item)
        assertEquals("transition-collection", ops.fallbackJobName)
    }

    @Test
    fun `resolve with provided language variant returns collection operations`() = runTest {
        val variant = languageVariant()
        val ops = resolve(request(languageTag = "fr"), variant)
        assertSame(variant, ops.item)
        assertEquals("transition-collection", ops.fallbackJobName)
    }

    @Test
    fun `resolve with provided unsupported item type errors`() = runTest {
        val error = assertFailsWith<IllegalStateException> { resolve(request(), UnsupportedItem()) }
        assertTrue(error.message?.contains("unsupported content item type") == true)
    }

    // ── resolve: metadata by id ─────────────────────────────────────────────────

    @Test
    fun `resolve loads metadata by id and version`() = runTest {
        val md = metadata(version = 3)
        coEvery { metadataService.getById(md.id, 3) } returns md
        val ops = resolve(request(metadataId = md.id, version = 3), null)
        assertSame(md, ops.item)
    }

    @Test
    fun `resolve errors when metadata version missing`() = runTest {
        val id = UUID.random()
        val error = assertFailsWith<IllegalStateException> {
            resolve(request(metadataId = id, version = null), null)
        }
        assertTrue(error.message?.contains("metadata version is required") == true)
    }

    @Test
    fun `resolve errors when metadata not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 2) } returns null
        val error = assertFailsWith<IllegalStateException> {
            resolve(request(metadataId = id, version = 2), null)
        }
        assertTrue(error.message?.contains("metadata not found") == true)
    }

    // ── resolve: collection by id ───────────────────────────────────────────────

    @Test
    fun `resolve loads collection by id when no language tag`() = runTest {
        val col = collection()
        coEvery { collectionService.getById(col.id) } returns col
        val ops = resolve(request(collectionId = col.id), null)
        assertSame(col, ops.item)
    }

    @Test
    fun `resolve errors when collection not found`() = runTest {
        val id = UUID.random()
        coEvery { collectionService.getById(id) } returns null
        val error = assertFailsWith<IllegalStateException> {
            resolve(request(collectionId = id), null)
        }
        assertTrue(error.message?.contains("collection not found") == true)
    }

    @Test
    fun `resolve loads language variant when language tag present`() = runTest {
        val id = UUID.random()
        val variant = languageVariant(id = id)
        coEvery { collectionService.getLanguageVariant(id, "fr") } returns variant
        val ops = resolve(request(collectionId = id, languageTag = "fr"), null)
        assertSame(variant, ops.item)
    }

    @Test
    fun `resolve errors when language variant not found`() = runTest {
        val id = UUID.random()
        coEvery { collectionService.getLanguageVariant(id, "fr") } returns null
        val error = assertFailsWith<IllegalStateException> {
            resolve(request(collectionId = id, languageTag = "fr"), null)
        }
        assertTrue(error.message?.contains("variant not found") == true)
    }

    @Test
    fun `resolve errors when neither collection nor metadata provided`() = runTest {
        val error = assertFailsWith<IllegalStateException> { resolve(request(), null) }
        assertTrue(error.message?.contains("collection_id or a metadata_id") == true)
    }

    // ── MetadataOperations ──────────────────────────────────────────────────────

    @Test
    fun `metadata verifyPermission delegates to evaluator`() = runTest {
        val md = metadata()
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EXECUTE) } just Runs
        val ops = resolve(request(), md)
        ops.verifyPermission(authentication)
        coVerify(exactly = 1) { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EXECUTE) }
    }

    @Test
    fun `metadata setPendingState delegates with request fields`() = runTest {
        val md = metadata()
        val principal = authenticatedPrincipal()
        val valid = OffsetDateTime.now()
        val req = request(stateId = "published", status = "pending-status", stateValid = valid)
        coEvery {
            metadataService.setPendingState(md, toStateId = "published", principal = principal.asPrincipal(), status = "pending-status", valid = valid)
        } returns md
        val ops = resolve(req, md)
        assertSame(md, ops.setPendingState(principal, req))
        coVerify(exactly = 1) {
            metadataService.setPendingState(md, toStateId = "published", principal = principal.asPrincipal(), status = "pending-status", valid = valid)
        }
    }

    @Test
    fun `metadata setPendingStateComplete delegates`() = runTest {
        val md = metadata()
        val current = metadata(id = md.id, version = 2)
        val principal = authenticatedPrincipal()
        val req = request(status = "complete-status")
        coEvery { metadataService.setPendingStateComplete(current, "complete-status", principal = principal.asPrincipal()) } returns current
        val ops = resolve(request(), md)
        assertSame(current, ops.setPendingStateComplete(current, principal, req))
    }

    @Test
    fun `metadata setPendingStateFailed delegates`() = runTest {
        val md = metadata()
        val current = metadata(id = md.id)
        val principal = authenticatedPrincipal()
        coEvery { metadataService.setPendingStateFailed(current, "failed-status", principal = principal.asPrincipal()) } returns current
        val ops = resolve(request(), md)
        assertSame(current, ops.setPendingStateFailed(current, principal, "failed-status"))
    }

    @Test
    fun `metadata addHistory records a metadata job history entry`() = runTest {
        val md = metadata(version = 7)
        val principal = authenticatedPrincipal()
        val jobId = UUID.random()
        val delayed = OffsetDateTime.now()
        val history = mockk<MetadataJobHistory>()
        coEvery { metadataJobHistory.addHistory(any()) } returns history
        val ops = resolve(request(), md)
        val result = ops.addHistory(principal, "job-name", jobId, "en", delayed)
        assertSame(history, result)
        coVerify(exactly = 1) { metadataJobHistory.addHistory(any()) }
    }

    @Test
    fun `metadata cancelActiveJobs errors when no principal`() = runTest {
        val md = metadata()
        every { authentication.principal() } returns null
        val ops = resolve(request(), md)
        val error = assertFailsWith<IllegalStateException> { ops.cancelActiveJobs(authentication) }
        assertTrue(error.message?.contains("no principal") == true)
    }

    @Test
    fun `metadata cancelActiveJobs sets not ready when pending and cancels active jobs`() = runTest {
        val md = metadata(version = 4, workflowStateId = "pending")
        val principal = principalAuth()
        val jobId = UUID.random()
        val job = MetadataJobHistory(
            id = md.id,
            version = md.version,
            jobName = "transition-metadata",
            jobId = jobId,
            status = "running",
            principal = principal.id,
        )
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        val queue = mockk<JobQueue>()
        provides<JobConfigurationEnqueuer>(name = "transition-metadata", singleton = true) { enqueuer }
        coEvery { enqueuer.queue() } returns queue
        coEvery { queue.markCancelled(jobId) } just Runs

        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EXECUTE) } just Runs
        coEvery { metadataService.setNotReady(md) } just Runs
        coEvery { metadataJobHistory.getActiveJobs(md.id, md.version) } returns listOf(job)
        coEvery { metadataJobHistory.setComplete(md.id, md.version, jobId, "Cancelled", false) } just Runs
        coEvery { metadataService.setPendingStateFailed(md, "Cancelled Transition", principal = principal) } returns md

        val ops = resolve(request(), md)
        ops.cancelActiveJobs(authentication)

        coVerify(exactly = 1) { metadataService.setNotReady(md) }
        coVerify(exactly = 1) { queue.markCancelled(jobId) }
        coVerify(exactly = 1) { metadataJobHistory.setComplete(md.id, md.version, jobId, "Cancelled", false) }
        coVerify(exactly = 1) { metadataService.setPendingStateFailed(md, "Cancelled Transition", principal = principal) }
    }

    @Test
    fun `metadata cancelActiveJobs skips setNotReady when not pending and swallows queue failure`() = runTest {
        val md = metadata(version = 2, workflowStateId = "draft")
        val principal = principalAuth()
        val jobId = UUID.random()
        val job = MetadataJobHistory(
            id = md.id,
            version = md.version,
            jobName = "missing-enqueuer",
            jobId = jobId,
            status = "running",
            principal = principal.id,
        )
        // No provider registered for "missing-enqueuer" — provide<>() resolution throws, caught by the operation.
        coEvery { metadataPermissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EXECUTE) } just Runs
        coEvery { metadataJobHistory.getActiveJobs(md.id, md.version) } returns listOf(job)
        coEvery { metadataJobHistory.setComplete(md.id, md.version, jobId, "Cancelled", false) } just Runs
        coEvery { metadataService.setPendingStateFailed(md, "Cancelled Transition", principal = principal) } returns md

        val ops = resolve(request(), md)
        ops.cancelActiveJobs(authentication)

        coVerify(exactly = 0) { metadataService.setNotReady(any()) }
        coVerify(exactly = 1) { metadataJobHistory.setComplete(md.id, md.version, jobId, "Cancelled", false) }
        coVerify(exactly = 1) { metadataService.setPendingStateFailed(md, "Cancelled Transition", principal = principal) }
    }

    // ── CollectionOperations ────────────────────────────────────────────────────

    @Test
    fun `collection verifyPermission delegates to evaluator`() = runTest {
        val col = collection()
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EXECUTE) } just Runs
        val ops = resolve(request(), col)
        ops.verifyPermission(authentication)
        coVerify(exactly = 1) { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EXECUTE) }
    }

    @Test
    fun `collection setPendingState delegates with request fields`() = runTest {
        val col = collection()
        val principal = authenticatedPrincipal()
        val req = request(stateId = "published", status = "pending-status")
        coEvery {
            collectionService.setPendingState(col, toStateId = "published", principal = principal.asPrincipal(), status = "pending-status", valid = null)
        } returns col
        val ops = resolve(request(), col)
        assertSame(col, ops.setPendingState(principal, req))
        coVerify(exactly = 1) {
            collectionService.setPendingState(col, toStateId = "published", principal = principal.asPrincipal(), status = "pending-status", valid = null)
        }
    }

    @Test
    fun `collection setPendingStateComplete delegates`() = runTest {
        val col = collection()
        val current = collection(id = col.id)
        val principal = authenticatedPrincipal()
        val req = request(status = "complete-status")
        coEvery { collectionService.setPendingStateComplete(current, "complete-status", principal = principal.asPrincipal()) } returns current
        val ops = resolve(request(), col)
        assertSame(current, ops.setPendingStateComplete(current, principal, req))
    }

    @Test
    fun `collection setPendingStateFailed delegates`() = runTest {
        val col = collection()
        val current = collection(id = col.id)
        val principal = authenticatedPrincipal()
        coEvery { collectionService.setPendingStateFailed(current, "failed-status", principal = principal.asPrincipal()) } returns current
        val ops = resolve(request(), col)
        assertSame(current, ops.setPendingStateFailed(current, principal, "failed-status"))
    }

    @Test
    fun `collection addHistory records a collection job history entry with language tag`() = runTest {
        val col = collection()
        val principal = authenticatedPrincipal()
        val jobId = UUID.random()
        val delayed = OffsetDateTime.now()
        val history = mockk<CollectionJobHistory>()
        coEvery { collectionJobHistory.addHistory(any()) } returns history
        val ops = resolve(request(), col)
        val result = ops.addHistory(principal, "job-name", jobId, "fr", delayed)
        assertSame(history, result)
        coVerify(exactly = 1) { collectionJobHistory.addHistory(any()) }
    }

    @Test
    fun `collection cancelActiveJobs errors when no principal`() = runTest {
        val col = collection()
        every { authentication.principal() } returns null
        val ops = resolve(request(), col)
        val error = assertFailsWith<IllegalStateException> { ops.cancelActiveJobs(authentication) }
        assertTrue(error.message?.contains("no principal") == true)
    }

    @Test
    fun `collection cancelActiveJobs sets not ready when pending and cancels active jobs`() = runTest {
        val col = collection(workflowStateId = "pending")
        val principal = principalAuth()
        val jobId = UUID.random()
        val job = CollectionJobHistory(
            id = col.id,
            jobName = "transition-collection",
            jobId = jobId,
            status = "running",
            principal = principal.id,
        )
        val enqueuer = mockk<JobConfigurationEnqueuer>()
        val queue = mockk<JobQueue>()
        provides<JobConfigurationEnqueuer>(name = "transition-collection", singleton = true) { enqueuer }
        coEvery { enqueuer.queue() } returns queue
        coEvery { queue.markCancelled(jobId) } just Runs

        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EXECUTE) } just Runs
        coEvery { collectionService.setNotReady(col) } just Runs
        coEvery { collectionJobHistory.getActiveJobs(col.id) } returns listOf(job)
        coEvery { collectionJobHistory.setComplete(col.id, jobId, "Cancelled", false) } just Runs
        coEvery { collectionService.setPendingStateFailed(col, "Cancelled Transition", principal = principal) } returns col

        val ops = resolve(request(), col)
        ops.cancelActiveJobs(authentication)

        coVerify(exactly = 1) { collectionService.setNotReady(col) }
        coVerify(exactly = 1) { queue.markCancelled(jobId) }
        coVerify(exactly = 1) { collectionJobHistory.setComplete(col.id, jobId, "Cancelled", false) }
        coVerify(exactly = 1) { collectionService.setPendingStateFailed(col, "Cancelled Transition", principal = principal) }
    }

    @Test
    fun `collection cancelActiveJobs skips setNotReady when not pending and swallows queue failure`() = runTest {
        val col = collection(workflowStateId = "draft")
        val principal = principalAuth()
        val jobId = UUID.random()
        val job = CollectionJobHistory(
            id = col.id,
            jobName = "missing-enqueuer",
            jobId = jobId,
            status = "running",
            principal = principal.id,
        )
        // No provider registered — provide<>() resolution throws, caught by the operation.
        coEvery { collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EXECUTE) } just Runs
        coEvery { collectionJobHistory.getActiveJobs(col.id) } returns listOf(job)
        coEvery { collectionJobHistory.setComplete(col.id, jobId, "Cancelled", false) } just Runs
        coEvery { collectionService.setPendingStateFailed(col, "Cancelled Transition", principal = principal) } returns col

        val ops = resolve(request(), col)
        ops.cancelActiveJobs(authentication)

        coVerify(exactly = 0) { collectionService.setNotReady(any()) }
        coVerify(exactly = 1) { collectionJobHistory.setComplete(col.id, jobId, "Cancelled", false) }
        coVerify(exactly = 1) { collectionService.setPendingStateFailed(col, "Cancelled Transition", principal = principal) }
    }
}
