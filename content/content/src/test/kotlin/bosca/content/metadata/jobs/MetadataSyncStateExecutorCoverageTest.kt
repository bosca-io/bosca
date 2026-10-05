package bosca.content.metadata.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.timeevent.service.TimeEventService
import bosca.content.transition.service.Transitioner
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit coverage for [MetadataSyncStateExecutor] driven through the public [execute] entry point.
 * The sibling end-to-end tests exercise the full publish-and-sync flow against a real database and
 * job runner; this test drives every branch of the executor's relationship-sync logic with mocked
 * collaborators only (no containers, no real transitioner state machine beyond the small shared
 * [bosca.content.transition.jobs.ContentTransitionLogic] path that fires for pending metadata).
 */
@OptIn(InternalDI::class)
class MetadataSyncStateExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val timeEventService = mockk<TimeEventService>(relaxed = true)
    private val transitioner = mockk<Transitioner>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }

    private val saPrincipal = Principal(id = UUID.random())
    private val saGroups = listOf(Group(id = UUID.random(), name = "sa", description = "", type = GroupType.SYSTEM))

    private val executor = MetadataSyncStateExecutor(metadataService, timeEventService, transitioner, securityService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
        // impersonate("sa") uses these two calls to build the ImpersonatedAuthenticationContext.
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns saPrincipal
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns saGroups
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        contentType: String = "text/plain",
        workflowStateId: String = "draft",
        workflowStatePendingId: String? = null,
        public: Boolean = false,
        publicContent: Boolean = false,
        publicSupplementary: Boolean = false,
        ready: java.time.OffsetDateTime? = java.time.OffsetDateTime.now(),
        syncVariantRelationships: Boolean = true,
    ): Metadata = Metadata(
        id = id,
        version = version,
        name = "meta-$id",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        workflowStatePendingId = workflowStatePendingId,
        public = public,
        publicContent = publicContent,
        publicSupplementary = publicSupplementary,
        ready = ready,
        syncVariantRelationships = syncVariantRelationships,
    )

    private fun relationship(id1: UUID, id2: UUID): MetadataRelationship =
        MetadataRelationship(metadataId1 = id1, metadataId2 = id2, relationship = "variant")

    /**
     * Drives [MetadataSyncStateExecutor.execute] with a job targeting [target].
     */
    @OptIn(Internal::class)
    private suspend fun execute(target: Metadata) {
        val config = MetadataSyncStateJob(id = target.id, version = target.version)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataSyncStateExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job) + connectionManager.asCoroutineContext()) {
            executor.execute()
        }
    }

    @Test
    fun `returns early when metadata not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null

        execute(metadata(id = id, version = 1))

        val theId = id
        coVerify(exactly = 1) { metadataService.removeFromCache(theId, 1) }
        coVerify(exactly = 0) { metadataService.getRelationships(any()) }
        coVerify(exactly = 0) { timeEventService.getRelatedMetadataIds(any(), any()) }
    }

    @Test
    fun `skips sync when syncVariantRelationships is disabled`() = runTest {
        val parent = metadata(workflowStateId = "published", syncVariantRelationships = false)
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent

        execute(parent)

        coVerify(exactly = 0) { metadataService.getRelationships(any()) }
        coVerify(exactly = 0) { timeEventService.getRelatedMetadataIds(any(), any()) }
    }

    @Test
    fun `skips sync when metadata is not published`() = runTest {
        val parent = metadata(workflowStateId = "draft", syncVariantRelationships = true)
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent

        execute(parent)

        coVerify(exactly = 0) { metadataService.getRelationships(any()) }
        coVerify(exactly = 0) { timeEventService.getRelatedMetadataIds(any(), any()) }
    }

    @Test
    fun `related metadata not found returns early within syncRelationship`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val relatedId = UUID.random()
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns null

        execute(parent)

        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `propagates public flags to already-published related metadata without transitioning`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val relatedId = UUID.random()
        // Related is already published, with all public flags off, non-image so only the direct
        // supplementary branch fires (not the image else-if).
        val related = metadata(
            id = relatedId,
            workflowStateId = "published",
            public = false,
            publicContent = false,
            publicSupplementary = false,
            contentType = "text/plain",
        )
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns related

        execute(parent)

        coVerify(exactly = 1) { metadataService.setPublic(related, true) }
        coVerify(exactly = 1) { metadataService.setPublicContent(related, true) }
        coVerify(exactly = 1) { metadataService.setPublicSupplementary(related, true) }
        // Already published, so the transition block is skipped entirely.
        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `skips public flag setters when related already has the flags`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val relatedId = UUID.random()
        // Related already published with all public flags on: every if-condition is false and the
        // image else-if is unreachable because publicSupplementary is already true.
        val related = metadata(
            id = relatedId,
            workflowStateId = "published",
            public = true,
            publicContent = true,
            publicSupplementary = true,
        )
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns related

        execute(parent)

        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `image related metadata gets supplementary made public via else-if branch`() = runTest {
        // Parent does NOT set public flags, so all first-if conditions are false; the image else-if
        // branch is the only one that fires for the image-typed related metadata.
        val parent = metadata(workflowStateId = "published", public = false, publicContent = false, publicSupplementary = false)
        val relatedId = UUID.random()
        val related = metadata(
            id = relatedId,
            workflowStateId = "published",
            public = false,
            publicContent = false,
            publicSupplementary = false,
            contentType = "image/png",
        )
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns related

        execute(parent)

        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        // else-if fires for image content type.
        coVerify(exactly = 1) { metadataService.setPublicSupplementary(related, true) }
    }

    @Test
    fun `non-image related metadata with no parent flags leaves supplementary untouched`() = runTest {
        val parent = metadata(workflowStateId = "published", public = false, publicContent = false, publicSupplementary = false)
        val relatedId = UUID.random()
        val related = metadata(
            id = relatedId,
            workflowStateId = "published",
            public = false,
            publicContent = false,
            publicSupplementary = false,
            contentType = "application/pdf",
        )
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns related

        execute(parent)

        // Neither the direct if nor the image else-if fires.
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `unpublished draft related metadata with pending transition is transitioned to published`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val relatedId = UUID.random()
        // ready != null so setReady is NOT called; workflowStatePendingId != null so the transition
        // companion runs; state "draft" so the pending->draft branch is skipped.
        val related = metadata(
            id = relatedId,
            contentType = "image/jpeg",
            workflowStateId = "draft",
            workflowStatePendingId = "processing",
            public = false,
            publicContent = false,
            publicSupplementary = false,
            ready = java.time.OffsetDateTime.now(),
        )
        // After ContentTransitionLogic completes the pending state, still "draft" but no pending id.
        val afterTransition = related.copy(workflowStatePendingId = null)

        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery { metadataService.setPendingStateComplete(any(), any(), any()) } returns afterTransition

        execute(parent)

        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        // The transition companion completed the pending state once.
        coVerify(exactly = 1) { metadataService.setPendingStateComplete(any(), any(), any()) }
        // Final publish transition begins (item arg defaulted to null at the call site).
        coVerify(exactly = 1) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `unpublished pending related metadata is readied then moved through pending to draft`() = runTest {
        val parent = metadata(workflowStateId = "published", public = false, publicContent = false, publicSupplementary = false)
        val relatedId = UUID.random()
        // ready == null so setReady IS called; workflowStatePendingId == null so the first transition
        // companion is skipped; state "pending" so the pending->draft branch fires.
        val related = metadata(
            id = relatedId,
            workflowStateId = "pending",
            workflowStatePendingId = null,
            ready = null,
        )
        val readied = related.copy() // still pending, still no pending id
        val movedToDraft = related.copy(workflowStateId = "pending", workflowStatePendingId = "processing")
        val afterTransition = related.copy(workflowStateId = "draft", workflowStatePendingId = null)

        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, relatedId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery { metadataService.setReady(related, saPrincipal) } returns readied
        coEvery {
            metadataService.setPendingState(readied, "draft", any(), any(), any(), any())
        } returns movedToDraft
        // The transition companion (invoked once, for the pending->draft move) completes the pending state.
        coEvery { metadataService.setPendingStateComplete(any(), any(), any()) } returns afterTransition

        execute(parent)

        coVerify(exactly = 1) { metadataService.setReady(related, saPrincipal) }
        coVerify(exactly = 1) { metadataService.setPendingState(readied, "draft", any(), any(), any(), any()) }
        coVerify(exactly = 1) { metadataService.setPendingStateComplete(any(), any(), any()) }
        coVerify(exactly = 1) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `time event related metadata is synced alongside standard relationships`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val timeEventRelatedId = UUID.random()
        val related = metadata(
            id = timeEventRelatedId,
            workflowStateId = "published",
            public = false,
            publicContent = false,
            publicSupplementary = false,
            contentType = "image/png",
        )
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns emptyList()
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns listOf(timeEventRelatedId)
        coEvery { metadataService.getById(timeEventRelatedId) } returns related

        execute(parent)

        coVerify(exactly = 1) { metadataService.setPublic(related, true) }
        coVerify(exactly = 1) { metadataService.getById(timeEventRelatedId) }
    }

    @Test
    fun `standard relationship failure is collected and rethrown after processing`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val failingId = UUID.random()
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns listOf(relationship(parent.id, failingId))
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns emptyList()
        // getById inside syncRelationship throws -> caught -> failures collected -> rethrown at the end.
        coEvery { metadataService.getById(failingId) } throws RuntimeException("boom")

        assertFailsWith<RuntimeException> {
            execute(parent)
        }
    }

    @Test
    fun `time event relationship failure is collected and rethrown after processing`() = runTest {
        val parent = metadata(workflowStateId = "published", public = true, publicContent = true, publicSupplementary = true)
        val timeEventFailingId = UUID.random()
        coEvery { metadataService.getById(parent.id, parent.version) } returns parent
        coEvery { metadataService.getRelationships(parent.id) } returns emptyList()
        coEvery { timeEventService.getRelatedMetadataIds(parent.id, parent.version) } returns listOf(timeEventFailingId)
        coEvery { metadataService.getById(timeEventFailingId) } throws RuntimeException("time-event boom")

        assertFailsWith<RuntimeException> {
            execute(parent)
        }
    }
}
