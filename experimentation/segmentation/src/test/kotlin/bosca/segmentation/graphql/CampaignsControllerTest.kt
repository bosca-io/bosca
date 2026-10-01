package bosca.segmentation.graphql

import bosca.di.annotation.InternalDI
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.segmentation.model.BannerWeight
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.service.CampaignService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class CampaignsControllerTest {

    private val campaignService = mockk<CampaignService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)

    private val controller = CampaignsController(campaignService, profileService, groupEvaluator)

    private fun authenticatedContext(principalId: UUID): AuthenticationContext {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.id } returns principalId
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns principal
        return auth
    }

    private fun anonymousContext(): AuthenticationContext {
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null
        return auth
    }

    // --- activeBanners ---

    @Test
    fun `activeBanners with profileId returns banners for matching principal`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.principal } returns principalId
        coEvery { profileService.getById(profileId) } returns profile

        val campaigns = listOf(
            Campaign(id = UUID.random(), name = "B1", channel = NotificationChannel.BANNER, weight = 50)
        )
        coEvery { campaignService.getActiveBannersForProfile(profileId) } returns campaigns

        val result = controller.activeBanners(auth, profileId, null)

        assertEquals(1, result.size)
        assertEquals("B1", result[0].name)
    }

    @Test
    fun `activeBanners with mismatched principal returns empty`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.principal } returns otherPrincipalId
        coEvery { profileService.getById(profileId) } returns profile

        val result = controller.activeBanners(auth, profileId, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `activeBanners with null profileId and anonymous user returns everyone banners`() = runTest {
        val auth = anonymousContext()
        val campaigns = listOf(
            Campaign(id = UUID.random(), name = "Everyone Banner", channel = NotificationChannel.BANNER, weight = 100)
        )
        coEvery { campaignService.getActiveBanners() } returns campaigns

        val result = controller.activeBanners(auth, null, null)

        assertEquals(1, result.size)
        assertEquals("Everyone Banner", result[0].name)
    }

    // --- activeBannerWeights ---

    @Test
    fun `activeBannerWeights with profileId returns weights for matching principal`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.principal } returns principalId
        coEvery { profileService.getById(profileId) } returns profile

        val weights = listOf(
            BannerWeight(UUID.random(), 80),
            BannerWeight(UUID.random(), 20)
        )
        coEvery {
            campaignService.getActiveBannerWeightsForPlacement(profileId, "top")
        } returns weights

        val result = controller.activeBannerWeights(auth, profileId, "top")

        assertEquals(2, result.size)
        assertEquals(80, result[0].weight)
    }

    @Test
    fun `activeBannerWeights with mismatched principal returns empty`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.principal } returns otherPrincipalId
        coEvery { profileService.getById(profileId) } returns profile

        val result = controller.activeBannerWeights(auth, profileId, "top")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `activeBannerWeights with null profileId and anonymous returns everyone weights`() = runTest {
        val auth = anonymousContext()
        val weights = listOf(BannerWeight(UUID.random(), 100))
        coEvery {
            campaignService.getActiveBannerWeightsForPlacement(null, "hero")
        } returns weights

        val result = controller.activeBannerWeights(auth, null, "hero")

        assertEquals(1, result.size)
        assertEquals(100, result[0].weight)
    }

    // --- activeBanner ---

    @Test
    fun `activeBanner with profileId returns weighted random campaign`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.principal } returns principalId
        coEvery { profileService.getById(profileId) } returns profile

        val campaign = Campaign(
            id = UUID.random(),
            name = "Top Banner",
            channel = NotificationChannel.BANNER,
            placement = "top",
            weight = 100
        )
        coEvery {
            campaignService.getActiveBannerForPlacement(profileId, "top")
        } returns campaign

        val result = controller.activeBanner(auth, profileId, "top")

        assertNotNull(result)
        assertEquals("Top Banner", result.name)
    }

    @Test
    fun `activeBanner with mismatched principal returns everyone banner`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.principal } returns otherPrincipalId
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { campaignService.getActiveBannerForPlacement(null, "top") } returns null

        val result = controller.activeBanner(auth, profileId, "top")

        assertNull(result)
    }

    @Test
    fun `activeBanner with null profileId and anonymous returns everyone banner`() = runTest {
        val auth = anonymousContext()
        val campaign = Campaign(
            id = UUID.random(),
            name = "Hero Banner",
            channel = NotificationChannel.BANNER,
            placement = "hero",
            weight = 100
        )
        coEvery {
            campaignService.getActiveBannerForPlacement(null, "hero")
        } returns campaign

        val result = controller.activeBanner(auth, null, "hero")

        assertNotNull(result)
        assertEquals("Hero Banner", result.name)
    }

    @Test
    fun `activeBanner with null profileId and anonymous returns null when no banners`() = runTest {
        val auth = anonymousContext()
        coEvery {
            campaignService.getActiveBannerForPlacement(null, "sidebar")
        } returns null

        val result = controller.activeBanner(auth, null, "sidebar")

        assertNull(result)
    }

    @Test
    fun `activeBanner with authenticated user but no profileId iterates profiles`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        val profile = mockk<Profile>()
        every { profile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)

        val campaign = Campaign(
            id = UUID.random(),
            name = "Profile Banner",
            channel = NotificationChannel.BANNER,
            placement = "top",
            weight = 100
        )
        coEvery {
            campaignService.getActiveBannerForPlacement(profileId, "top")
        } returns campaign

        val result = controller.activeBanner(auth, null, "top")

        assertNotNull(result)
        assertEquals("Profile Banner", result.name)
    }
}
