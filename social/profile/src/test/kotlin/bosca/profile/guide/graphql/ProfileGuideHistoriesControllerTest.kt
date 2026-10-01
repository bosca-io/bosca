package bosca.profile.guide.graphql

import bosca.profile.guide.model.ProfileGuideHistory
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileGuideHistoriesControllerTest {

    private val service = mockk<ProfileGuideService>()
    private val controller = ProfileGuideHistoriesController(service)

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
        val histories = ProfileGuideHistories(profile)

        val result = controller.all(authentication, histories, 0L, 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all returns history when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val histories = ProfileGuideHistories(profile)
        val expected = listOf(
            ProfileGuideHistory(
                id = 1,
                profileId = profile.id,
                metadataId = UUID.random(),
                version = 1,
                attributes = JsonObject(emptyMap())
            )
        )

        coEvery { service.getAllHistory(profile.id, 10, 0L) } returns expected

        val result = controller.all(authentication, histories, 0L, 10)

        assertEquals(1, result.size)
    }

    @Test
    fun `count returns 0 when principal does not match profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val histories = ProfileGuideHistories(profile)

        val result = controller.count(authentication, histories)

        assertEquals(0L, result)
    }

    @Test
    fun `count returns count when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val histories = ProfileGuideHistories(profile)

        coEvery { service.getHistoryCount(profile.id) } returns 3L

        val result = controller.count(authentication, histories)

        assertEquals(3L, result)
    }

    @Test
    fun `history returns empty when principal does not match profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val histories = ProfileGuideHistories(profile)

        val result = controller.history(authentication, histories, UUID.random(), 1, 0L, 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `history returns history when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val histories = ProfileGuideHistories(profile)
        val metadataId = UUID.random()
        val expected = listOf(
            ProfileGuideHistory(
                id = 1,
                profileId = profile.id,
                metadataId = metadataId,
                version = 1,
                attributes = JsonObject(emptyMap())
            )
        )

        coEvery { service.getHistory(profile.id, metadataId, 1, 10, 0L) } returns expected

        val result = controller.history(authentication, histories, metadataId, 1, 0L, 10)

        assertEquals(1, result.size)
    }

    @Test
    fun `historyCount returns 0 when principal does not match profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val histories = ProfileGuideHistories(profile)

        val result = controller.historyCount(authentication, histories, UUID.random(), 1)

        assertEquals(0L, result)
    }

    @Test
    fun `historyCount returns count when principal matches profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val histories = ProfileGuideHistories(profile)
        val metadataId = UUID.random()

        coEvery { service.getHistoryCount(profile.id, metadataId, 1) } returns 2L

        val result = controller.historyCount(authentication, histories, metadataId, 1)

        assertEquals(2L, result)
    }

    @Test
    fun `all returns empty when profile has null principal`() = runTest {
        val profile = createProfile(principalId = null)
        val authentication = createAuthContext(UUID.random())
        val histories = ProfileGuideHistories(profile)

        val result = controller.all(authentication, histories, 0L, 10)

        assertTrue(result.isEmpty())
    }
}
