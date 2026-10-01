package bosca.profile.relationship.graphql

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProfileRelationshipControllerTest {

    private val profileService = mockk<ProfileService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val controller = ProfileRelationshipController(profileService, permissionEvaluator)

    @Test
    fun `profile returns the linked profile after verifying view permission`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val linkedProfileId = UUID.random()
        val linkedProfile = Profile(
            id = linkedProfileId,
            type = ProfileType.GENERIC,
            name = "Linked Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val relationship = ProfileRelationship(UUID.random(), linkedProfileId, "friend")
        coEvery { profileService.getById(linkedProfileId) } returns linkedProfile
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, linkedProfile, PermissionAction.VIEW)
        } returns Unit

        assertSame(linkedProfile, controller.profile(authentication, relationship))
        coVerify(exactly = 1) {
            permissionEvaluator.verifyAllowed(authentication, linkedProfile, PermissionAction.VIEW)
        }
    }

    @Test
    fun `type and attributes expose relationship values`() {
        val attributes = buildJsonObject { put("weight", 3) }
        val relationship = ProfileRelationship(UUID.random(), UUID.random(), "mentor", attributes)

        assertEquals("mentor", controller.type(relationship))
        assertEquals(attributes, controller.attributes(relationship))
    }
}
