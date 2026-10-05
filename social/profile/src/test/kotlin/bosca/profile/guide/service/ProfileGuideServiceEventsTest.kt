package bosca.profile.guide.service

import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.service.GuideService
import bosca.analytics.server.ServerAnalyticsClient
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.profile.guide.events.ProfileGuideCompleted
import bosca.profile.guide.events.ProfileGuideProgressAdded
import bosca.profile.guide.events.ProfileGuideProgressDeleted
import bosca.profile.guide.events.dispatch
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.repository.ProfileGuideHistoryRepository
import bosca.profile.guide.repository.ProfileGuideProgressRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileGuideServiceEventsTest {

    private val progressRepository = mockk<ProfileGuideProgressRepository>()
    private val historyRepository = mockk<ProfileGuideHistoryRepository>()
    private val guideService = mockk<GuideService>()
    private val analytics = mockk<ServerAnalyticsClient>()
    private val analyticsEvents = mutableListOf<Events>()
    private val service = ProfileGuideServiceImpl(progressRepository, historyRepository, guideService, analytics)

    private val addedEvents = mutableListOf<ProfileGuideProgressAdded>()
    private val completedEvents = mutableListOf<ProfileGuideCompleted>()
    private val deletedEvents = mutableListOf<ProfileGuideProgressDeleted>()

    @BeforeTest
    fun setup() {
        coEvery { analytics.capture(any<Events>()) } coAnswers { analyticsEvents += firstArg<Events>() }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<ProfileGuideProgress?>(any())
        } coAnswers {
            val block = firstArg<suspend () -> ProfileGuideProgress?>()
            block()
        }
        coEvery { progressRepository.getProgressForUpdate(any(), any(), any()) } returns null

        mockkStatic("bosca.profile.guide.events.ProfileGuideProgressAddedExtKt")
        mockkStatic("bosca.profile.guide.events.ProfileGuideCompletedExtKt")
        mockkStatic("bosca.profile.guide.events.ProfileGuideProgressDeletedExtKt")
        coEvery { any<ProfileGuideProgressAdded>().dispatch() } coAnswers { addedEvents += firstArg<ProfileGuideProgressAdded>() }
        coEvery { any<ProfileGuideCompleted>().dispatch() } coAnswers { completedEvents += firstArg<ProfileGuideCompleted>() }
        coEvery { any<ProfileGuideProgressDeleted>().dispatch() } coAnswers { deletedEvents += firstArg<ProfileGuideProgressDeleted>() }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        unmockkStatic("bosca.profile.guide.events.ProfileGuideProgressAddedExtKt")
        unmockkStatic("bosca.profile.guide.events.ProfileGuideCompletedExtKt")
        unmockkStatic("bosca.profile.guide.events.ProfileGuideProgressDeletedExtKt")
    }

    private fun step(id: Long, metadataId: UUID, version: Int) =
        GuideStep(id = id, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 1)

    @Test
    fun `completing the final step fires ProgressAdded and Completed`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())

        coEvery { guideService.getGuideSteps(metadataId, version) } returns listOf(step(100L, metadataId, version))
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = listOf(100L),
        )
        coEvery { progressRepository.addStepProgress(profileId, metadataId, version, 100L, attributes) } returns progress
        coEvery { historyRepository.add(profileId, metadataId, version, progress.attributes) } returns Unit
        coEvery { progressRepository.delete(profileId, metadataId, version) } returns Unit

        service.addProgress(profileId, metadataId, version, 100L, attributes)

        assertEquals(1, addedEvents.size)
        assertEquals(100L, addedEvents.single().stepId)
        assertEquals(true, addedEvents.single().initialProgress)
        assertEquals(100.0, addedEvents.single().percentage)
        assertEquals(100, addedEvents.single().percentageMilestone)
        assertEquals(true, addedEvents.single().firstAtPercentageMilestone)
        assertEquals(1, completedEvents.size)
        assertEquals(profileId, completedEvents.single().profileId)
        assertEquals(metadataId, completedEvents.single().metadataId)
        val batch = analyticsEvents.single()
        assertEquals(profileId.toString(), batch.context?.userId)
        assertEquals(listOf("guide_step", "guide"), batch.events.map { it.element?.type })
        assertEquals(listOf(EventType.Completion, EventType.Completion), batch.events.map { it.type })
        assertEquals(progress.modified.toInstant().toEpochMilli(), batch.events.first().created)
        assertEquals(listOf(metadataId.toString()), batch.events.last().element?.content?.map { it.id })
    }

    @Test
    fun `registering progress fires an initial ProgressAdded event`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())

        coEvery { guideService.getGuideSteps(metadataId, version) } returns listOf(
            step(100L, metadataId, version),
        )
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = emptyList(),
        )
        coEvery { progressRepository.addProgress(profileId, metadataId, version, attributes) } returns progress

        service.addProgress(profileId, metadataId, version, 0L, attributes)

        assertEquals(1, addedEvents.size)
        assertEquals(true, addedEvents.single().initialProgress)
        assertEquals(0.0, addedEvents.single().percentage)
        assertEquals(0, addedEvents.single().percentageMilestone)
        assertEquals(false, addedEvents.single().firstAtPercentageMilestone)
        assertEquals(0, analyticsEvents.size)
    }

    @Test
    fun `completing the first step after registration is not initial progress`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())
        val registeredProgress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = emptyList(),
        )

        coEvery { guideService.getGuideSteps(metadataId, version) } returns listOf(
            step(100L, metadataId, version),
            step(101L, metadataId, version),
        )
        coEvery { progressRepository.getProgressForUpdate(profileId, metadataId, version) } returns registeredProgress
        coEvery {
            progressRepository.addStepProgress(profileId, metadataId, version, 101L, attributes)
        } returns registeredProgress.copy(completedStepIds = listOf(101L))

        service.addProgress(profileId, metadataId, version, 101L, attributes)

        assertEquals(1, addedEvents.size)
        assertEquals(false, addedEvents.single().initialProgress)
        assertEquals(0, completedEvents.size)
        assertEquals(listOf("guide_step"), analyticsEvents.single().events.map { it.element?.type })
    }

    @Test
    fun `four of seven steps reports the 50 percent milestone and actual percentage`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())
        val steps = (100L..106L).map { step(it, metadataId, version) }
        val existing = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = listOf(100L, 101L, 102L),
        )
        val updated = existing.copy(completedStepIds = existing.completedStepIds + 103L)

        coEvery { guideService.getGuideSteps(metadataId, version) } returns steps
        coEvery { progressRepository.getProgressForUpdate(profileId, metadataId, version) } returns existing
        coEvery {
            progressRepository.addStepProgress(profileId, metadataId, version, 103L, attributes)
        } returns updated

        service.addProgress(profileId, metadataId, version, 103L, attributes)

        val event = addedEvents.single()
        assertEquals(400.0 / 7.0, event.percentage)
        assertEquals(50, event.percentageMilestone)
        assertEquals(true, event.firstAtPercentageMilestone)
        assertEquals(false, event.initialProgress)
        assertEquals(0, completedEvents.size)
    }

    @Test
    fun `later progress in the same percentage milestone does not report it again`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())
        val steps = (100L..124L).map { step(it, metadataId, version) }
        val existing = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = (100L..112L).toList(),
        )
        val updated = existing.copy(completedStepIds = existing.completedStepIds + 113L)

        coEvery { guideService.getGuideSteps(metadataId, version) } returns steps
        coEvery { progressRepository.getProgressForUpdate(profileId, metadataId, version) } returns existing
        coEvery {
            progressRepository.addStepProgress(profileId, metadataId, version, 113L, attributes)
        } returns updated

        service.addProgress(profileId, metadataId, version, 113L, attributes)

        val event = addedEvents.single()
        assertEquals(56.0, event.percentage)
        assertEquals(50, event.percentageMilestone)
        assertEquals(false, event.firstAtPercentageMilestone)
    }

    @Test
    fun `partial progress fires ProgressAdded but not Completed`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())

        coEvery { guideService.getGuideSteps(metadataId, version) } returns listOf(
            step(100L, metadataId, version),
            step(101L, metadataId, version),
        )
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = listOf(100L),
        )
        coEvery { progressRepository.addStepProgress(profileId, metadataId, version, 100L, attributes) } returns progress

        service.addProgress(profileId, metadataId, version, 100L, attributes)

        assertEquals(1, addedEvents.size)
        assertEquals(0, completedEvents.size)
    }

    @Test
    fun `already-recorded step fires no events`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val attributes = JsonObject(emptyMap())

        coEvery { guideService.getGuideSteps(metadataId, version) } returns listOf(step(100L, metadataId, version))
        // Null from the conditional upsert means the step was already recorded — a no-op.
        coEvery { progressRepository.addStepProgress(profileId, metadataId, version, 100L, attributes) } returns null

        service.addProgress(profileId, metadataId, version, 100L, attributes)

        assertEquals(0, addedEvents.size)
        assertEquals(0, completedEvents.size)
        assertEquals(0, analyticsEvents.size)
    }

    @Test
    fun `deleteProgress fires ProgressDeleted`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        coEvery { progressRepository.delete(profileId, metadataId, 1) } returns Unit

        service.deleteProgress(profileId, metadataId, 1)

        assertEquals(1, deletedEvents.size)
        assertEquals(profileId, deletedEvents.single().profileId)
    }
}
