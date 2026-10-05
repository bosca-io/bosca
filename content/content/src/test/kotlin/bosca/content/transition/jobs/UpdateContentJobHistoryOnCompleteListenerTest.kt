package bosca.content.transition.jobs

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Coverage for [UpdateContentJobHistoryOnCompleteListener] — the single listener
 * responsible for closing out a `metadata_job_history` / `collection_job_history`
 * row (setting `complete = now()`). Before these tests the class had **no**
 * coverage, so every branch that returns early WITHOUT closing the row — and
 * therefore leaves the row visibly "pending" forever — was unverified.
 *
 * The job arguments are built with [InternalJobConstructor]; a freshly built
 * [Job] has `id == UUID.NIL`, which makes it `isLocked`, so `setStatus` /
 * `addChild` are usable directly without a distributed lock.
 */
@OptIn(Internal::class)
class UpdateContentJobHistoryOnCompleteListenerTest {

    private val metadataJobHistoryService = mockk<MetadataJobHistoryService>(relaxed = true)
    private val collectionJobHistoryService = mockk<CollectionJobHistoryService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val transitioner = mockk<Transitioner>(relaxed = true)

    private val listener = UpdateContentJobHistoryOnCompleteListener(
        metadataJobHistoryService,
        collectionJobHistoryService,
        metadataService,
        collectionService,
        securityService,
        transitioner,
    )

    private val id = UUID.random()

    /**
     * The advertised→published chain calls `connection().commitTransaction()`, which
     * only needs a [ConnectionManager] present in the coroutine context — no JDBC
     * connection is ever acquired because every service is mocked.
     */
    private suspend fun <T> withConnectionContext(block: suspend () -> T): T {
        val cm = ConnectionManager(mockk<ConnectionPool>(relaxed = true))
        return withContext(cm.asCoroutineContext()) {
            block()
        }
    }

    private class FakeExecutor : JobExecutor {
        override suspend fun execute() {}
    }

    private fun buildJob(
        definition: JsonElement,
        status: JobStatus,
        childStatuses: List<JobStatus> = emptyList(),
    ): Job {
        val job = InternalJobConstructor(definition = definition, executor = FakeExecutor::class)
        for (childStatus in childStatuses) {
            val child = InternalJobConstructor(definition = JsonObject(emptyMap()), executor = FakeExecutor::class)
            child.setStatus(childStatus)
            job.addChild(child)
        }
        job.setStatus(status)
        return job
    }

    private fun metadataDefinition(version: Int? = 1, type: String? = null): JsonObject = buildJsonObject {
        put("id", id.toString())
        if (version != null) put("version", version)
        if (type != null) put("type", type)
    }

    private fun collectionDefinition(languageTag: String? = null, type: String? = null): JsonObject = buildJsonObject {
        put("id", id.toString())
        if (languageTag != null) put("languageTag", languageTag)
        if (type != null) put("type", type)
    }

    private fun assertNoRowClosed() {
        coVerify(exactly = 0) { metadataJobHistoryService.setComplete(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { collectionJobHistoryService.setComplete(any(), any(), any(), any()) }
    }

    // --- terminal-status / completeness gating ----------------------------------

    @Test
    fun `non terminal status closes nothing`() = runTest {
        val job = buildJob(metadataDefinition(), JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.RUNNING, null)

        assertNoRowClosed()
    }

    @Test
    fun `complete job that is not fully complete closes nothing`() = runTest {
        // Parent reports COMPLETE but a child is still RUNNING -> isFullyComplete() == false.
        val job = buildJob(metadataDefinition(), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.RUNNING))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        assertNoRowClosed()
    }

    @Test
    fun `definition that is not a json object closes nothing`() = runTest {
        val job = buildJob(JsonNull, JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        assertNoRowClosed()
    }

    @Test
    fun `missing id closes nothing`() = runTest {
        val job = buildJob(buildJsonObject { put("version", 1) }, JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        assertNoRowClosed()
    }

    @Test
    fun `unparseable id closes nothing`() = runTest {
        val job = buildJob(buildJsonObject { put("id", "not-a-uuid"); put("version", 1) }, JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        assertNoRowClosed()
    }

    @Test
    fun `metadata job missing version closes nothing`() = runTest {
        // type forces the metadata branch; version is absent -> early return WITHOUT
        // closing the row. This is one of the ways a metadata_job_history row gets
        // stranded as permanently pending.
        val job = buildJob(metadataDefinition(version = null, type = "metadata"), JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        assertNoRowClosed()
    }

    @Test
    fun `unknown content type closes nothing`() = runTest {
        val job = buildJob(metadataDefinition(version = 1, type = "bogus"), JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        assertNoRowClosed()
    }

    // --- metadata happy paths ----------------------------------------------------

    @Test
    fun `metadata single job marks history complete and does not finalize pending state`() = runTest {
        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) {
            metadataJobHistoryService.setComplete(id, 1, job.getId(), "Complete", true)
        }
        // Single (non-multi) job: the executor cleared its own pending state, so the
        // listener must NOT re-finalize it.
        coVerify(exactly = 0) { metadataService.getById(any<UUID>(), any<Int>()) }
        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
    }

    @Test
    fun `metadata type resolved from explicit type field`() = runTest {
        val job = buildJob(metadataDefinition(version = 1, type = "metadata"), JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) {
            metadataJobHistoryService.setComplete(id, 1, job.getId(), "Complete", true)
        }
    }

    @Test
    fun `metadata multi job finalizes pending state when present`() = runTest {
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.workflowStatePendingId } returns "pending"
        coEvery { metadataService.getById(id, 1) } returns metadata
        val completed = mockk<Metadata>(relaxed = true)
        every { completed.workflowStateId } returns "pending"
        every { completed.workflowStatePendingId } returns null
        coEvery { metadataService.setPendingStateComplete(any(), any(), any()) } returns completed
        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns id
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()

        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { metadataJobHistoryService.setComplete(id, 1, job.getId(), "Complete", true) }
        coVerify(exactly = 1) { metadataService.removeFromCache(id, 1) }
        coVerify(exactly = 1) { metadataService.setPendingStateComplete(eq(metadata), eq("Transition Complete"), any()) }
    }

    @Test
    fun `metadata multi job with no pending state skips finalize`() = runTest {
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.workflowStatePendingId } returns null
        coEvery { metadataService.getById(id, 1) } returns metadata

        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { metadataJobHistoryService.setComplete(id, 1, job.getId(), "Complete", true) }
        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
    }

    @Test
    fun `metadata multi job skips finalize when metadata is gone`() = runTest {
        coEvery { metadataService.getById(id, 1) } returns null

        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { metadataJobHistoryService.setComplete(id, 1, job.getId(), "Complete", true) }
        coVerify(exactly = 1) { metadataService.removeFromCache(id, 1) }
        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
    }

    // --- advertised → published chaining -----------------------------------------

    @Test
    fun `metadata multi job completing into advertised chains to published`() = runTest {
        val publishedEpoch = System.currentTimeMillis() + 60 * 60 * 1000
        val attrs = buildJsonObject { put("published", publishedEpoch) }
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.workflowStatePendingId } returns "advertised"
        every { metadata.workflowStateValid } returns null
        coEvery { metadataService.getById(id, 1) } returns metadata

        val advertised = mockk<Metadata>(relaxed = true)
        every { advertised.id } returns id
        every { advertised.version } returns 1
        every { advertised.workflowStateId } returns "advertised"
        every { advertised.workflowStatePendingId } returns null
        every { advertised.attributes } returns attrs
        coEvery { metadataService.setPendingStateComplete(eq(metadata), any(), any()) } returns advertised

        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns id
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()

        val requestSlot = slot<BeginTransitionInput>()
        coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        withConnectionContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 1) { metadataService.setPendingStateComplete(eq(metadata), eq("Transition Complete"), any()) }
        val request = requestSlot.captured
        assertEquals("published", request.stateId)
        assertEquals(id, request.metadataId)
        assertEquals(1, request.version)
        assertNotNull(request.stateValid, "publish must be scheduled for the future published epoch")
    }

    @Test
    fun `metadata multi job leaves a future scheduled transition for its delayed job`() = runTest {
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.workflowStatePendingId } returns "published"
        every { metadata.workflowStateValid } returns OffsetDateTime.now().plusHours(1)
        coEvery { metadataService.getById(id, 1) } returns metadata

        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { metadataJobHistoryService.setComplete(id, 1, job.getId(), "Complete", true) }
        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `metadata multi job already settled in advertised re-runs the publish chain`() = runTest {
        val attrs = buildJsonObject { put("published", System.currentTimeMillis() + 60 * 60 * 1000) }
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.id } returns id
        every { metadata.version } returns 1
        every { metadata.workflowStateId } returns "advertised"
        every { metadata.workflowStatePendingId } returns null
        every { metadata.workflowStateValid } returns null
        every { metadata.attributes } returns attrs
        coEvery { metadataService.getById(id, 1) } returns metadata

        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns id
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()

        val requestSlot = slot<BeginTransitionInput>()
        coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns metadata

        val job = buildJob(metadataDefinition(version = 1), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        withConnectionContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
        assertEquals("published", requestSlot.captured.stateId)
    }

    @Test
    fun `collection multi job completing into advertised chains to published`() = runTest {
        val attrs = buildJsonObject { put("published", System.currentTimeMillis() + 60 * 60 * 1000) }
        val collection = mockk<Collection>(relaxed = true)
        every { collection.workflowStatePendingId } returns "advertised"
        every { collection.workflowStateValid } returns null
        coEvery { collectionService.getById(id) } returns collection

        val advertised = mockk<Collection>(relaxed = true)
        every { advertised.id } returns id
        every { advertised.workflowStateId } returns "advertised"
        every { advertised.workflowStatePendingId } returns null
        every { advertised.attributes } returns attrs
        coEvery { collectionService.setPendingStateComplete(eq(collection), any(), any()) } returns advertised

        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns id
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()

        val requestSlot = slot<BeginTransitionInput>()
        coEvery { transitioner.beginTransition(any(), capture(requestSlot), any()) } returns advertised

        val job = buildJob(collectionDefinition(), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        withConnectionContext {
            listener.onStatusChanged(job, JobStatus.COMPLETE, null)
        }

        coVerify(exactly = 1) { collectionService.setPendingStateComplete(eq(collection), eq("Transition Complete"), any()) }
        val request = requestSlot.captured
        assertEquals("published", request.stateId)
        assertEquals(id, request.collectionId)
        assertNotNull(request.stateValid, "publish must be scheduled for the future published epoch")
    }

    // --- collection paths --------------------------------------------------------

    @Test
    fun `collection single job marks history complete`() = runTest {
        // No "version" key -> inferred as collection.
        val job = buildJob(collectionDefinition(), JobStatus.COMPLETE)

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) {
            collectionJobHistoryService.setComplete(id, job.getId(), "Complete", true)
        }
    }

    @Test
    fun `collection multi job with language tag uses language variant`() = runTest {
        coEvery { collectionService.getLanguageVariant(id, "en") } returns null

        val job = buildJob(collectionDefinition(languageTag = "en"), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { collectionJobHistoryService.setComplete(id, job.getId(), "Complete", true) }
        coVerify(exactly = 1) { collectionService.removeFromCache(id) }
        coVerify(exactly = 1) { collectionService.getLanguageVariant(id, "en") }
        coVerify(exactly = 0) { collectionService.setPendingStateComplete(any(), any(), any()) }
    }

    @Test
    fun `collection multi job without language tag finalizes pending state`() = runTest {
        val collection = mockk<Collection>(relaxed = true)
        every { collection.workflowStatePendingId } returns "pending"
        coEvery { collectionService.getById(id) } returns collection
        val completed = mockk<Collection>(relaxed = true)
        every { completed.workflowStateId } returns "pending"
        every { completed.workflowStatePendingId } returns null
        coEvery { collectionService.setPendingStateComplete(any(), any(), any()) } returns completed
        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns id
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns principal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()

        val job = buildJob(collectionDefinition(), JobStatus.COMPLETE, childStatuses = listOf(JobStatus.COMPLETE))

        listener.onStatusChanged(job, JobStatus.COMPLETE, null)

        coVerify(exactly = 1) { collectionJobHistoryService.setComplete(id, job.getId(), "Complete", true) }
        coVerify(exactly = 1) { collectionService.setPendingStateComplete(eq(collection), eq("Transition Complete"), any()) }
    }

    // --- FAILED_AND_COMPLETE terminal state --------------------------------------

    @Test
    fun `terminally-failed job closes the metadata history row as failed`() = runTest {
        // A terminal failure (FAILED_AND_COMPLETE) must close its row (success = false) rather than
        // being held open by the isFullyComplete() gate, which is only ever true for COMPLETE.
        val job = buildJob(metadataDefinition(version = 1), JobStatus.FAILED_AND_COMPLETE)

        listener.onStatusChanged(job, JobStatus.FAILED_AND_COMPLETE, "boom")

        coVerify(exactly = 1) {
            metadataJobHistoryService.setComplete(id, 1, job.getId(), "boom", false)
        }
        // A failed job must not finalize the pending workflow state.
        coVerify(exactly = 0) { metadataService.setPendingStateComplete(any(), any(), any()) }
    }

    @Test
    fun `terminally-failed job with no error message uses default failed status`() = runTest {
        val job = buildJob(metadataDefinition(version = 1), JobStatus.FAILED_AND_COMPLETE)

        listener.onStatusChanged(job, JobStatus.FAILED_AND_COMPLETE, null)

        coVerify(exactly = 1) {
            metadataJobHistoryService.setComplete(id, 1, job.getId(), "Failed", false)
        }
    }

    @Test
    fun `terminally-failed collection job closes the collection history row as failed`() = runTest {
        val job = buildJob(collectionDefinition(), JobStatus.FAILED_AND_COMPLETE)

        listener.onStatusChanged(job, JobStatus.FAILED_AND_COMPLETE, "boom")

        coVerify(exactly = 1) {
            collectionJobHistoryService.setComplete(id, job.getId(), "boom", false)
        }
    }

    @Test
    fun `a retryable FAILED does not close the row`() = runTest {
        // FAILED is non-terminal now (the job will retry); the row stays open until COMPLETE or the
        // terminal FAILED_AND_COMPLETE.
        val job = buildJob(metadataDefinition(version = 1), JobStatus.FAILED)

        listener.onStatusChanged(job, JobStatus.FAILED, "transient")

        coVerify(exactly = 0) { metadataJobHistoryService.setComplete(any(), any(), any(), any(), any()) }
    }
}
