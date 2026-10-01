package bosca.content.guide.graphql

import bosca.profile.guide.model.GuideProgressStatistics
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class GuidesControllerTest {

    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = GuidesController(groupEvaluator)

    @Test
    fun `progress verifies administrator access and returns guide context`() {
        val metadataId = UUID.random()
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit

        val progress = controller.progress(authentication, metadataId)

        assertEquals(metadataId, progress.metadataId)
        verify(exactly = 1) { groupEvaluator.verifyHasSaGroup(authentication) }
    }
}

class GuideProgressControllerTest {

    private val service = mockk<ProfileGuideService>()
    private val profileService = mockk<ProfileService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = GuideProgressController(service, profileService, groupEvaluator)

    @Test
    fun `statistics verifies administrator access and delegates to service`() = runTest {
        val progress = GuideProgress(UUID.random())
        val expected = GuideProgressStatistics(2, 2, 6, 5, 8, 7)
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { service.getStatistics(progress.metadataId) } returns expected

        assertEquals(expected, controller.statistics(authentication, progress))
        verify(exactly = 1) { groupEvaluator.verifyHasSaGroup(authentication) }
    }

    @Test
    fun `activeProfiles resolves IDs in activity order`() = runTest {
        val progress = GuideProgress(UUID.random())
        val profileIds = listOf(UUID.random(), UUID.random())
        val profiles = profileIds.mapIndexed { index, profileId ->
            Profile(
                id = profileId,
                type = ProfileType.GENERIC,
                name = "Profile $index",
                visibility = ProfileVisibility.PUBLIC,
            )
        }
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { service.getActiveProfileIds(progress.metadataId, 100, 0) } returns profileIds
        coEvery { profileService.getAllByIds(profileIds) } returns profiles.reversed()
        val activeProgress = listOf(
            ProfileGuideProgress(
                profileId = profiles[0].id,
                metadataId = progress.metadataId,
                version = 3,
                attributes = JsonObject(emptyMap()),
            ),
            ProfileGuideProgress(
                profileId = profiles[0].id,
                metadataId = progress.metadataId,
                version = 1,
                attributes = JsonObject(emptyMap()),
            ),
            ProfileGuideProgress(
                profileId = profiles[1].id,
                metadataId = progress.metadataId,
                version = 2,
                attributes = JsonObject(emptyMap()),
            ),
        )
        coEvery { service.getActiveProgress(progress.metadataId, profileIds) } returns activeProgress.reversed()

        val result = controller.activeProfiles(authentication, progress, 500, -10)

        assertEquals(profiles, result.map { it.profile })
        assertEquals(listOf(activeProgress[1], activeProgress[0]), result[0].progressions)
        assertEquals(listOf(activeProgress[2]), result[1].progressions)
    }

    @Test
    fun `activeProfiles avoids follow-up lookups for an empty page`() = runTest {
        val progress = GuideProgress(UUID.random())
        every { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { service.getActiveProfileIds(progress.metadataId, 25, 0) } returns emptyList()

        assertEquals(emptyList(), controller.activeProfiles(authentication, progress, 25, 0))
    }
}

class GuideProgressProfileControllerTest {

    private val controller = GuideProgressProfileController()

    @Test
    fun `fields expose the profile and its selected-guide progressions`() {
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val progressions = listOf(
            ProfileGuideProgress(
                profileId = profile.id,
                metadataId = UUID.random(),
                version = 3,
                attributes = JsonObject(emptyMap()),
            ),
        )
        val progress = GuideProgressProfile(profile, progressions)

        assertEquals(profile, controller.profile(progress))
        assertEquals(progressions, controller.progressions(progress))
    }
}

class GuideProgressStatisticsControllerTest {

    private val controller = GuideProgressStatisticsController()

    @Test
    fun `fields expose every aggregate count`() {
        val statistics = GuideProgressStatistics(2, 2, 6, 5, 8, 7)

        assertEquals(2, controller.activeProgressions(statistics))
        assertEquals(2, controller.activeProfiles(statistics))
        assertEquals(6, controller.historicalProgressions(statistics))
        assertEquals(5, controller.completions(statistics))
        assertEquals(8, controller.totalProgressions(statistics))
        assertEquals(7, controller.uniqueProfiles(statistics))
    }
}
