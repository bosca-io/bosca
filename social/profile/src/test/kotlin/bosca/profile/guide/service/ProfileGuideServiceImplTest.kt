package bosca.profile.guide.service

import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.service.GuideService
import bosca.profile.guide.model.GuideProgressStatistics
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.repository.ProfileGuideHistoryRepository
import bosca.profile.guide.repository.ProfileGuideProgressRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileGuideServiceImplTest {

    private val progressRepository = mockk<ProfileGuideProgressRepository>()
    private val historyRepository = mockk<ProfileGuideHistoryRepository>()
    private val guideService = mockk<GuideService>()
    private val service = ProfileGuideServiceImpl(progressRepository, historyRepository, guideService, mockk(relaxed = true))

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { 
            bosca.db.transaction<ProfileGuideProgress?>(any()) 
        } coAnswers {
            val block = firstArg<suspend () -> ProfileGuideProgress?>()
            block()
        }
        coEvery { progressRepository.getProgressForUpdate(any(), any(), any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `addProgress adds step progress and moves to history when completed`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val stepId = 100L
        val attributes = JsonObject(emptyMap())

        val steps = listOf(GuideStep(id = 100L, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 1))
        coEvery { guideService.getGuideSteps(metadataId, version) } returns steps
        
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = listOf(100L)
        )
        coEvery { progressRepository.addStepProgress(profileId, metadataId, version, stepId, attributes) } returns progress
        coEvery { historyRepository.add(profileId, metadataId, version, progress.attributes) } returns Unit
        coEvery { progressRepository.delete(profileId, metadataId, version) } returns Unit

        val result = service.addProgress(profileId, metadataId, version, stepId, attributes)

        assertNotNull(result)
        assertEquals(progress, result)

        coVerify { 
            guideService.getGuideSteps(metadataId, version)
            progressRepository.addStepProgress(profileId, metadataId, version, stepId, attributes)
            historyRepository.add(profileId, metadataId, version, progress.attributes)
            progressRepository.delete(profileId, metadataId, version)
        }
    }

    @Test
    fun `addProgress adds generic progress when stepId is not in guide steps`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val stepId = 200L
        val attributes = JsonObject(emptyMap())

        val steps = listOf(GuideStep(id = 100L, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 1))
        coEvery { guideService.getGuideSteps(metadataId, version) } returns steps
        
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = version,
            attributes = attributes,
            completedStepIds = emptyList()
        )
        coEvery { progressRepository.addProgress(profileId, metadataId, version, attributes) } returns progress

        val result = service.addProgress(profileId, metadataId, version, stepId, attributes)

        assertNotNull(result)
        assertEquals(progress, result)

        coVerify { 
            guideService.getGuideSteps(metadataId, version)
            progressRepository.addProgress(profileId, metadataId, version, attributes)
        }
        coVerify(exactly = 0) {
            progressRepository.addStepProgress(any(), any(), any(), any(), any())
            historyRepository.add(any(), any(), any(), any())
            progressRepository.delete(any(), any(), any())
        }
    }

    @Test
    fun `deleteProgress delegates to repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1

        coEvery { progressRepository.delete(profileId, metadataId, version) } returns Unit

        service.deleteProgress(profileId, metadataId, version)

        coVerify { progressRepository.delete(profileId, metadataId, version) }
    }

    @Test
    fun `getStatistics delegates to progress repository`() = runTest {
        val metadataId = UUID.random()
        val expected = GuideProgressStatistics(3, 2, 7, 7, 10, 8)
        coEvery { progressRepository.statistics(metadataId) } returns expected

        assertEquals(expected, service.getStatistics(metadataId))
        coVerify(exactly = 1) { progressRepository.statistics(metadataId) }
    }

    @Test
    fun `getActiveProfileIds delegates pagination to progress repository`() = runTest {
        val metadataId = UUID.random()
        val expected = listOf(UUID.random(), UUID.random())
        coEvery { progressRepository.findProfileIdsByMetadataId(metadataId, 25, 50L) } returns expected

        assertEquals(expected, service.getActiveProfileIds(metadataId, 25, 50L))
        coVerify(exactly = 1) { progressRepository.findProfileIdsByMetadataId(metadataId, 25, 50L) }
    }

    @Test
    fun `getActiveProgress loads a guide for the requested profile page`() = runTest {
        val metadataId = UUID.random()
        val profileIds = listOf(UUID.random(), UUID.random())
        val expected = profileIds.mapIndexed { index, profileId ->
            ProfileGuideProgress(
                profileId = profileId,
                metadataId = metadataId,
                version = index + 1,
                attributes = JsonObject(emptyMap()),
            )
        }
        coEvery { progressRepository.findByMetadataIdAndProfileIds(metadataId, profileIds) } returns expected

        assertEquals(expected, service.getActiveProgress(metadataId, profileIds))
        coVerify(exactly = 1) { progressRepository.findByMetadataIdAndProfileIds(metadataId, profileIds) }
    }

    @Test
    fun `getActiveProgress does not query repository for an empty profile page`() = runTest {
        assertEquals(emptyList(), service.getActiveProgress(UUID.random(), emptyList()))
        coVerify(exactly = 0) { progressRepository.findByMetadataIdAndProfileIds(any(), any()) }
    }

    @Test
    fun `getAllProgress filters by guide metadata when requested`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val expected = listOf(
            ProfileGuideProgress(
                profileId = profileId,
                metadataId = metadataId,
                version = 3,
                attributes = JsonObject(emptyMap()),
            ),
        )
        coEvery {
            progressRepository.findByProfileAndMetadataId(profileId, metadataId, 25, 50L)
        } returns expected

        assertEquals(expected, service.getAllProgress(profileId, metadataId, 25, 50L))
        coVerify(exactly = 1) {
            progressRepository.findByProfileAndMetadataId(profileId, metadataId, 25, 50L)
        }
    }

    @Test
    fun `getProgressCount filters by guide metadata when requested`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        coEvery { progressRepository.countByProfileAndMetadataId(profileId, metadataId) } returns 2L

        assertEquals(2L, service.getProgressCount(profileId, metadataId))
        coVerify(exactly = 1) {
            progressRepository.countByProfileAndMetadataId(profileId, metadataId)
        }
    }

    @Test
    fun `addProgress returns current progress if upsert returns null`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val version = 1
        val stepId = 100L
        val attributes = JsonObject(emptyMap())

        val steps = listOf(GuideStep(id = 100L, metadataId = metadataId, version = version, stepMetadataId = null, stepMetadataVersion = null, sort = 1))
        coEvery { guideService.getGuideSteps(metadataId, version) } returns steps
        
        coEvery { progressRepository.addStepProgress(profileId, metadataId, version, stepId, attributes) } returns null

        val result = service.addProgress(profileId, metadataId, version, stepId, attributes)

        assertNotNull(result)
        assertEquals(listOf(stepId), result.completedStepIds)
    }
}
