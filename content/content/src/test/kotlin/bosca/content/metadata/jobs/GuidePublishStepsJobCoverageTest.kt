package bosca.content.metadata.jobs

import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit coverage for [GuidePublishStepsExecutor.execute] and [GuidePublishStepsExecutor.getLockId].
 *
 * Exercises every early-return branch (missing metadata, non-guide content type, empty steps),
 * the per-step skip branches (null step metadata id, missing step metadata, already published),
 * and the full publish flow including ready/pending/transition handling.
 */
@OptIn(InternalDI::class, Internal::class)
class GuidePublishStepsJobCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val guideService = mockk<GuideService>()
    private val transitioner = mockk<Transitioner>()
    private val securityService = mockk<SecurityService>()

    private val json = Json { ignoreUnknownKeys = true }

    private val saPrincipal = Principal(id = UUID.random())
    private val saGroups = listOf(
        Group(id = UUID.random(), name = "sa", description = "", type = GroupType.SYSTEM),
    )

    private val executor = GuidePublishStepsExecutor(
        metadataService,
        guideService,
        transitioner,
        securityService,
    )

    @BeforeTest
    fun setup() {
        provides<Json> { json }
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns saPrincipal
        coEvery { securityService.getPrincipalGroups(saPrincipal.id) } returns saGroups
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
        contentType: String = "bosca/v-guide",
        workflowStateId: String = "draft",
        workflowStatePendingId: String? = null,
        ready: OffsetDateTime? = OffsetDateTime.now(),
    ) = Metadata(
        id = id,
        version = version,
        name = "step",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId,
        workflowStatePendingId = workflowStatePendingId,
        ready = ready,
    )

    private fun guideStep(
        metadataId: UUID,
        stepMetadataId: UUID?,
        stepMetadataVersion: Int?,
        sort: Int = 0,
    ) = GuideStep(
        metadataId = metadataId,
        version = 1,
        stepMetadataId = stepMetadataId,
        stepMetadataVersion = stepMetadataVersion,
        sort = sort,
    )

    private suspend fun run(job: GuidePublishStepsJob) {
        val jobQueue = mockk<JobQueue>()
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = GuidePublishStepsExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject)) {
            executor.execute()
        }
    }

    @Test
    fun `getLockId returns the job id`() = runTest {
        val id = UUID.random()
        val job = GuidePublishStepsJob(id = id, version = 4)
        val jobQueue = mockk<JobQueue>()
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = GuidePublishStepsExecutor::class,
        )
        val lockId = withContext(jobQueue.asCoroutineContext(jobObject)) {
            executor.getLockId()
        }
        kotlin.test.assertEquals(id.toString(), lockId)
    }

    @Test
    fun `returns early when metadata not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { guideService.getGuideSteps(any(), any(), any(), any()) }
    }

    @Test
    fun `returns early when content type is not a guide`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns metadata(id = id, contentType = "text/plain")

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { guideService.getGuideSteps(any(), any(), any(), any()) }
    }

    @Test
    fun `returns early when there are no steps`() = runTest {
        val id = UUID.random()
        val guide = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns guide
        coEvery { guideService.getGuideSteps(guide.id, guide.version) } returns emptyList()

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { securityService.getPrincipalByIdentifier(any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `skips step with null step metadata id`() = runTest {
        val id = UUID.random()
        val guide = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns guide
        coEvery { guideService.getGuideSteps(guide.id, guide.version) } returns
            listOf(guideStep(metadataId = id, stepMetadataId = null, stepMetadataVersion = null))

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `skips step when step metadata not found`() = runTest {
        val id = UUID.random()
        val stepId = UUID.random()
        val guide = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns guide
        coEvery { guideService.getGuideSteps(guide.id, guide.version) } returns
            listOf(guideStep(metadataId = id, stepMetadataId = stepId, stepMetadataVersion = null))
        // stepMetadataVersion null -> defaults to 1
        coEvery { metadataService.getById(stepId, 1) } returns null

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `skips step already published`() = runTest {
        val id = UUID.random()
        val stepId = UUID.random()
        val guide = metadata(id = id)
        coEvery { metadataService.getById(id, 1) } returns guide
        coEvery { guideService.getGuideSteps(guide.id, guide.version) } returns
            listOf(guideStep(metadataId = id, stepMetadataId = stepId, stepMetadataVersion = 2))
        coEvery { metadataService.getById(stepId, 2) } returns
            metadata(id = stepId, version = 2, workflowStateId = "published")

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        coVerify(exactly = 0) { transitioner.beginTransition(any(), any(), any()) }
    }

    @Test
    fun `publishes ready non-pending step directly`() = runTest {
        val id = UUID.random()
        val stepId = UUID.random()
        val guide = metadata(id = id)
        // ready set, no pending id, not in pending state: skip setReady + both transition arms
        val stepMetadata = metadata(
            id = stepId,
            version = 3,
            workflowStateId = "draft",
            workflowStatePendingId = null,
            ready = OffsetDateTime.now(),
        )
        coEvery { metadataService.getById(id, 1) } returns guide
        coEvery { guideService.getGuideSteps(guide.id, guide.version) } returns
            listOf(guideStep(metadataId = id, stepMetadataId = stepId, stepMetadataVersion = 3))
        coEvery { metadataService.getById(stepId, 3) } returns stepMetadata
        coEvery { transitioner.beginTransition(any(), any(), any()) } returns stepMetadata

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 0) { metadataService.setReady(any(), any()) }
        val captured = mutableListOf<BeginTransitionInput>()
        coVerify(exactly = 1) {
            transitioner.beginTransition(any(), capture(captured), any())
        }
        kotlin.test.assertEquals(stepId, captured.single().metadataId)
        kotlin.test.assertEquals("published", captured.single().stateId)
    }

    @Test
    fun `publishes step through ready, pending transition, and pending state paths`() = runTest {
        val id = UUID.random()
        val stepId = UUID.random()
        val guide = metadata(id = id)

        // Initial step: ready == null -> setReady, workflowStatePendingId != null -> first transition
        val initialStep = metadata(
            id = stepId,
            version = 5,
            workflowStateId = "draft",
            workflowStatePendingId = null,
            ready = null,
        )
        // After setReady: has ready, and a pending id to trigger the first transition execute
        val readyStep = initialStep.copy(
            ready = OffsetDateTime.now(),
            workflowStatePendingId = "draft",
        )
        // First transition returns a step in the "pending" state -> setPendingState + second transition
        val pendingStep = readyStep.copy(
            workflowStateId = "pending",
            workflowStatePendingId = null,
        )
        // setPendingState result
        val movedStep = pendingStep.copy(workflowStatePendingId = "draft")
        // Second transition result
        val transitionedStep = movedStep.copy(workflowStateId = "draft", workflowStatePendingId = null)

        coEvery { metadataService.getById(id, 1) } returns guide
        coEvery { guideService.getGuideSteps(guide.id, guide.version) } returns
            listOf(guideStep(metadataId = id, stepMetadataId = stepId, stepMetadataVersion = 5))
        coEvery { metadataService.getById(stepId, 5) } returns initialStep
        coEvery { metadataService.setReady(initialStep, saPrincipal) } returns readyStep
        coEvery {
            metadataService.setPendingState(any(), any(), any(), any(), any(), any())
        } returns movedStep
        coEvery { transitioner.beginTransition(any(), any(), any()) } returns transitionedStep

        mockkObject(MetadataTransitionExecutor.Companion)
        coEvery {
            MetadataTransitionExecutor.execute(any(), any(), any(), any())
        } returnsMany listOf(pendingStep, transitionedStep)

        run(GuidePublishStepsJob(id = id, version = 1))

        coVerify(exactly = 1) { metadataService.setReady(initialStep, saPrincipal) }
        coVerify(exactly = 1) {
            metadataService.setPendingState(pendingStep, "draft", any(), any(), any(), any())
        }
        coVerify(exactly = 2) { MetadataTransitionExecutor.execute(any(), any(), any(), any()) }
        coVerify(exactly = 1) { transitioner.beginTransition(any(), any(), any()) }
    }
}
