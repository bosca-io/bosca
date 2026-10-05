package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataProfile
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataProfileControllerCoverageTest {

    private val service = mockk<ProfileService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()

    private val controller = MetadataProfileController(service, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun metadataProfile(profileId: UUID) = MetadataProfile(
        metadataId = UUID.random(),
        profileId = profileId,
        relationship = "author",
        sort = 0
    )

    private fun profile(id: UUID) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = "Test Profile",
        visibility = ProfileVisibility.PUBLIC
    )

    @Test
    fun `profile returns resolved profile when view is allowed`() = runTest {
        val profileId = UUID.random()
        val metadataProfile = metadataProfile(profileId)
        val resolved = profile(profileId)

        coEvery { service.getById(profileId) } returns resolved
        coEvery { permissionEvaluator.isAllowed(authentication, resolved, PermissionAction.VIEW) } returns true

        assertEquals(resolved, controller.profile(authentication, metadataProfile))
    }

    @Test
    fun `profile returns null when view is denied`() = runTest {
        val profileId = UUID.random()
        val metadataProfile = metadataProfile(profileId)
        val resolved = profile(profileId)

        coEvery { service.getById(profileId) } returns resolved
        coEvery { permissionEvaluator.isAllowed(authentication, resolved, PermissionAction.VIEW) } returns false

        assertNull(controller.profile(authentication, metadataProfile))
    }

    @Test
    fun `relationship returns the relationship of the metadata profile`() {
        val metadataProfile = MetadataProfile(
            metadataId = UUID.random(),
            profileId = UUID.random(),
            relationship = "editor",
            sort = 3
        )

        assertEquals("editor", controller.relationship(metadataProfile))
    }
}
