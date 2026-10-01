package bosca.profile.relationship.graphql

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProfileRelationshipRequestControllerTest {

    private val profileService = mockk<ProfileService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val controller = ProfileRelationshipRequestController(profileService, permissionEvaluator)

    @Test
    fun `requester and target resolve their profiles`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val request = relationshipRequest()
        val requester = profile(request.requesterProfileId, "Requester")
        val target = profile(request.targetProfileId, "Target")
        coEvery { profileService.getById(request.requesterProfileId) } returns requester
        coEvery { profileService.getById(request.targetProfileId) } returns target
        coEvery {
            permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, requester, target)
        } returns Unit

        assertSame(requester, controller.requester(authentication, request))
        assertSame(target, controller.target(authentication, request))
        coVerify(exactly = 2) {
            permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, requester, target)
        }
    }

    @Test
    fun `profile fields reject callers who manage neither participant`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val request = relationshipRequest()
        val requester = profile(request.requesterProfileId, "Requester")
        val target = profile(request.targetProfileId, "Target")
        coEvery { profileService.getById(request.requesterProfileId) } returns requester
        coEvery { profileService.getById(request.targetProfileId) } returns target
        coEvery {
            permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, requester, target)
        } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> { controller.target(authentication, request) }
    }

    @Test
    fun `scalar fields expose request lifecycle values`() {
        val attributes = buildJsonObject { put("message", "hello") }
        val request = relationshipRequest().copy(attributes = attributes)

        assertEquals(request.id, controller.id(request))
        assertEquals(request.type, controller.type(request))
        assertEquals(attributes, controller.attributes(request))
        assertEquals(ProfileRelationshipRequestStatus.PENDING, controller.status(request))
        assertEquals(request.created, controller.created(request))
        assertEquals(request.modified, controller.modified(request))
    }

    private fun relationshipRequest() = ProfileRelationshipRequest(
        id = UUID.random(),
        requesterProfileId = UUID.random(),
        targetProfileId = UUID.random(),
        type = "friend",
        status = ProfileRelationshipRequestStatus.PENDING,
        version = 0,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun profile(id: UUID, name: String) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
    )
}
