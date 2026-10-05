package bosca.profile.bookmark.graphql

import bosca.profile.bookmark.model.ProfileBookmark
import bosca.profile.bookmark.service.ProfileBookmarkService
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileBookmarksControllerTest {

    private val service = mockk<ProfileBookmarkService>()
    private val controller = ProfileBookmarksController(service)

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
    fun `bookmarks returns empty list when principal does not own profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val bookmarks = ProfileBookmarks(profile)

        val result = controller.bookmarks(authentication, bookmarks)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `bookmarks returns list when principal owns profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val bookmarks = ProfileBookmarks(profile)
        val expected = listOf(
            ProfileBookmark(id = 1, profileId = profile.id, metadataId = UUID.random(), metadataVersion = 1)
        )

        coEvery { service.getBookmarks(profile.id, 25, 0) } returns expected

        val result = controller.bookmarks(authentication, bookmarks)

        assertEquals(1, result.size)
        assertEquals(expected, result)
    }

    @Test
    fun `bookmarks uses provided offset and limit`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val bookmarks = ProfileBookmarks(profile)

        coEvery { service.getBookmarks(profile.id, 5, 10) } returns emptyList()

        val result = controller.bookmarks(authentication, bookmarks, offset = 10L, limit = 5L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `count returns 0 when principal does not own profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val bookmarks = ProfileBookmarks(profile)

        val result = controller.count(authentication, bookmarks)

        assertEquals(0L, result)
    }

    @Test
    fun `count returns count when principal owns profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val bookmarks = ProfileBookmarks(profile)

        coEvery { service.getBookmarkCount(profile.id) } returns 7L

        val result = controller.count(authentication, bookmarks)

        assertEquals(7L, result)
    }

    @Test
    fun `bookmark returns null when principal does not own profile`() = runTest {
        val profile = createProfile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        val bookmarks = ProfileBookmarks(profile)

        val result = controller.bookmark(authentication, bookmarks, metadataId = UUID.random(), metadataVersion = 1)

        assertNull(result)
    }

    @Test
    fun `bookmark by metadata returns bookmark when found`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val bookmarks = ProfileBookmarks(profile)
        val metadataId = UUID.random()
        val expected = ProfileBookmark(id = 1, profileId = profile.id, metadataId = metadataId, metadataVersion = 1)

        coEvery { service.getBookmark(profile.id, metadataId, 1) } returns expected

        val result = controller.bookmark(authentication, bookmarks, metadataId = metadataId, metadataVersion = 1)

        assertEquals(expected, result)
    }

    @Test
    fun `bookmark by collection returns bookmark when found`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val bookmarks = ProfileBookmarks(profile)
        val collectionId = UUID.random()
        val expected = ProfileBookmark(id = 1, profileId = profile.id, collectionId = collectionId)

        coEvery { service.getBookmark(profile.id, collectionId) } returns expected

        val result = controller.bookmark(authentication, bookmarks, collectionId = collectionId)

        assertEquals(expected, result)
    }

    @Test
    fun `bookmark returns null when no metadata or collection specified`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val bookmarks = ProfileBookmarks(profile)

        val result = controller.bookmark(authentication, bookmarks)

        assertNull(result)
    }

    @Test
    fun `bookmarks returns empty when profile has null principal`() = runTest {
        val profile = createProfile(principalId = null)
        val authentication = createAuthContext(UUID.random())
        val bookmarks = ProfileBookmarks(profile)

        val result = controller.bookmarks(authentication, bookmarks)

        assertTrue(result.isEmpty())
    }
}
