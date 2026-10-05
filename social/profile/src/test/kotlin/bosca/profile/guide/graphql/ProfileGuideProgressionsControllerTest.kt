package bosca.profile.guide.graphql

import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileGuideProgressionsControllerTest {

    private val service = mockk<ProfileGuideService>()
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = ProfileGuideProgressionsController(service, groupEvaluator)

    private fun createProfile(principalId: UUID? = UUID.random()): Profile {
        return Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test",
            visibility = ProfileVisibility.PUBLIC
        )
    }

    private fun createAuthContext(principalId: UUID): AuthenticationContext {
        val authentication = mockk<AuthenticationContext>()
        val principal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns principal
        every { principal.id } returns principalId
        return authentication
    }

    @Test
    fun `all returns empty when principal does not match profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)

        val result = controller.all(authentication, progressions, 10, 0L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all returns progress when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val progressions = ProfileGuideProgressions(profile)
        val expected = listOf(
            ProfileGuideProgress(
                profileId = profile.id,
                metadataId = UUID.random(),
                version = 1,
                attributes = JsonObject(emptyMap())
            )
        )

        coEvery { service.getAllProgress(profile.id, 10, 0L) } returns expected

        val result = controller.all(authentication, progressions, 10, 0L)

        assertEquals(1, result.size)
    }

    @Test
    fun `all returns progress when service account queries another user's profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)
        val expected = listOf(
            ProfileGuideProgress(
                profileId = profile.id,
                metadataId = UUID.random(),
                version = 1,
                attributes = JsonObject(emptyMap())
            )
        )
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { service.getAllProgress(profile.id, 10, 0L) } returns expected

        val result = controller.all(authentication, progressions, 10, 0L)

        assertEquals(expected, result)
    }

    @Test
    fun `all filters progress by guide metadata when requested`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val progressions = ProfileGuideProgressions(profile)
        val metadataId = UUID.random()
        val expected = listOf(
            ProfileGuideProgress(
                profileId = profile.id,
                metadataId = metadataId,
                version = 3,
                attributes = JsonObject(emptyMap()),
            ),
        )
        coEvery { service.getAllProgress(profile.id, metadataId, 10, 0L) } returns expected

        val result = controller.all(authentication, progressions, 10, 0L, metadataId)

        assertEquals(expected, result)
    }

    @Test
    fun `count returns 0 when principal does not match profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)

        val result = controller.count(authentication, progressions)

        assertEquals(0L, result)
    }

    @Test
    fun `count returns count when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val progressions = ProfileGuideProgressions(profile)

        coEvery { service.getProgressCount(profile.id) } returns 5L

        val result = controller.count(authentication, progressions)

        assertEquals(5L, result)
    }

    @Test
    fun `count filters progress by guide metadata when requested`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val progressions = ProfileGuideProgressions(profile)
        val metadataId = UUID.random()
        coEvery { service.getProgressCount(profile.id, metadataId) } returns 2L

        val result = controller.count(authentication, progressions, metadataId)

        assertEquals(2L, result)
    }

    @Test
    fun `count returns count when service account queries another user's profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { service.getProgressCount(profile.id) } returns 5L

        val result = controller.count(authentication, progressions)

        assertEquals(5L, result)
    }

    @Test
    fun `progress returns null when principal does not match profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)

        val result = controller.progress(authentication, progressions, UUID.random(), 1)

        assertNull(result)
    }

    @Test
    fun `progress returns progress when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val progressions = ProfileGuideProgressions(profile)
        val metadataId = UUID.random()
        val expected = ProfileGuideProgress(
            profileId = profile.id,
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap())
        )

        coEvery { service.getProgress(profile.id, metadataId, 1) } returns expected

        val result = controller.progress(authentication, progressions, metadataId, 1)

        assertEquals(expected, result)
    }

    @Test
    fun `progress returns progress when service account queries another user's profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)
        val metadataId = UUID.random()
        val expected = ProfileGuideProgress(
            profileId = profile.id,
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap())
        )
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { service.getProgress(profile.id, metadataId, 1) } returns expected

        val result = controller.progress(authentication, progressions, metadataId, 1)

        assertEquals(expected, result)
    }

    @Test
    fun `all returns empty when profile has null principal`() = runTest {
        val profile = createProfile(principalId = null)
        val authentication = createAuthContext(UUID.random())
        val progressions = ProfileGuideProgressions(profile)

        val result = controller.all(authentication, progressions, 10, 0L)

        assertTrue(result.isEmpty())
    }
}
