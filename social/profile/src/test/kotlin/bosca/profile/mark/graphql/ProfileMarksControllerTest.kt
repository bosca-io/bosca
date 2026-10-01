package bosca.profile.mark.graphql

import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.service.ProfileMarkService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileMarksControllerTest {

    private val service = mockk<ProfileMarkService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val controller = ProfileMarksController(service, permissionEvaluator)

    private fun createProfile(principalId: UUID? = UUID.random()): Profile {
        return Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test",
            visibility = ProfileVisibility.PUBLIC
        )
    }

    @Test
    fun `all returns empty list when not allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false

        val result = controller.all(authentication, marks)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all returns marks when allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)
        val expected = listOf(ProfileMark(id = 1, profileId = profile.id))

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarks(profile.id, 25, 0) } returns expected

        val result = controller.all(authentication, marks)

        assertEquals(1, result.size)
    }

    @Test
    fun `all uses provided offset and limit`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarks(profile.id, 5, 10) } returns emptyList()

        val result = controller.all(authentication, marks, offset = 10L, limit = 5)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `count returns 0 when not allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false

        val result = controller.count(authentication, marks)

        assertEquals(0L, result)
    }

    @Test
    fun `count returns count when allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarkCount(profile.id) } returns 12L

        val result = controller.count(authentication, marks)

        assertEquals(12L, result)
    }

    @Test
    fun `mark returns empty when not allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false

        val result = controller.mark(authentication, marks, metadataId = UUID.random(), metadataVersion = 1, offset = 0L, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `mark by metadata returns marks when allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)
        val metadataId = UUID.random()
        val expected = listOf(ProfileMark(id = 1, profileId = profile.id, metadataId = metadataId, metadataVersion = 1))

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarks(profile.id, metadataId, 1, 10, 0L) } returns expected

        val result = controller.mark(authentication, marks, metadataId = metadataId, metadataVersion = 1, offset = 0L, limit = 10)

        assertEquals(1, result.size)
    }

    @Test
    fun `mark by collection returns marks when allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)
        val collectionId = UUID.random()
        val expected = listOf(ProfileMark(id = 1, profileId = profile.id, collectionId = collectionId))

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarks(profile.id, collectionId, 10, 0L) } returns expected

        val result = controller.mark(authentication, marks, collectionId = collectionId, offset = 0L, limit = 10)

        assertEquals(1, result.size)
    }

    @Test
    fun `mark returns empty when no metadata or collection specified`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true

        val result = controller.mark(authentication, marks, offset = 0L, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `markCount returns 0 when not allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false

        val result = controller.markCount(authentication, marks, metadataId = UUID.random(), metadataVersion = 1)

        assertEquals(0L, result)
    }

    @Test
    fun `markCount by metadata returns count when allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)
        val metadataId = UUID.random()

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarkCount(profile.id, metadataId, 1) } returns 5L

        val result = controller.markCount(authentication, marks, metadataId = metadataId, metadataVersion = 1)

        assertEquals(5L, result)
    }

    @Test
    fun `markCount by collection returns count when allowed`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)
        val collectionId = UUID.random()

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { service.getMarkCount(profile.id, collectionId) } returns 3L

        val result = controller.markCount(authentication, marks, collectionId = collectionId)

        assertEquals(3L, result)
    }

    @Test
    fun `markCount returns 0 when neither metadata nor collection specified`() = runTest {
        val profile = createProfile()
        val authentication = mockk<AuthenticationContext>()
        val marks = ProfileMarks(profile)

        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true

        val result = controller.markCount(authentication, marks)

        assertEquals(0L, result)
    }
}
