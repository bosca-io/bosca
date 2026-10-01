package bosca.profile.profile.graphql

import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.security.service.GroupEvaluator
import bosca.slug.service.SlugService
import bosca.content.collection.service.CollectionService
import bosca.profile.organization.service.OrganizationService
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.profile.model.Profile
import bosca.profile.attribute.model.ProfileAttribute
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse

class ProfileControllerTest {

    private val profileService = mockk<ProfileService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val organizationService = mockk<OrganizationService>(relaxed = true)
    private val organizationPermissionEvaluator = mockk<OrganizationPermissionEvaluator>(relaxed = true)
    private val profileRelationshipService = mockk<ProfileRelationshipService>()
    private val profileRelationshipRequestService = mockk<ProfileRelationshipRequestService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(relaxed = true)

    private val controller = ProfileController(
        profileService,
        securityService,
        collectionPermissionEvaluator,
        groupEvaluator,
        slugService,
        collectionService,
        organizationService,
        organizationPermissionEvaluator,
        profileRelationshipService,
        profileRelationshipRequestService,
        profilePermissionEvaluator,
    )

    @Test
    fun `searchable returns the saved profile preference`() {
        val profile = Profile(
            type = ProfileType.GENERIC,
            name = "Hidden from search",
            visibility = ProfileVisibility.PUBLIC,
            searchable = false,
        )

        assertFalse(controller.searchable(profile))
    }

    @Test
    fun `chat is exposed only for the authenticated principal's primary profile`() = runTest {
        val principal = Principal(id = UUID.random())
        val authentication = mockk<AuthenticationContext>()
        every { authentication.principal() } returns AuthenticatedPrincipal(principal, emptyList())
        val primary = Profile(
            id = UUID.random(),
            principal = principal.id,
            type = ProfileType.GENERIC,
            name = "Primary",
            visibility = ProfileVisibility.USER,
        )
        val secondary = primary.copy(id = UUID.random(), name = "Secondary")
        coEvery { profileService.getPrimaryProfile(principal) } returns primary

        assertNotNull(controller.chat(authentication, primary))
        assertNull(controller.chat(authentication, secondary))
        assertNull(controller.chat(null, primary))
    }

    @Test
    fun `relationships return outgoing relationships with the requested page`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Requested Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val outgoingProfileId = UUID.random()
        val outgoing = ProfileRelationship(profile.id, outgoingProfileId, "friend")
        coEvery {
            profileRelationshipService.getRelationships(profile.id, "friend", offset = 10, limit = 20)
        } returns listOf(outgoing)

        val result = controller.relationships(
            authentication = authentication,
            profile = profile,
            type = "friend",
            limit = 20,
            offset = 10,
        )

        assertEquals(listOf(outgoing), result)
        coVerify(exactly = 1) {
            profilePermissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        }
    }

    @Test
    fun `relationships keep pagination optional`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Requested Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        coEvery {
            profileRelationshipService.getRelationships(profile.id, null, offset = 0, limit = Int.MAX_VALUE)
        } returns emptyList()

        assertEquals(emptyList(), controller.relationships(authentication, profile))
    }

    @Test
    fun `incoming relationship requests require edit permission and use the requested page`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Target Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val request = ProfileRelationshipRequest(
            id = UUID.random(),
            requesterProfileId = UUID.random(),
            targetProfileId = profile.id,
            type = "friend",
            status = ProfileRelationshipRequestStatus.PENDING,
            version = 0,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
        )
        coEvery {
            profileRelationshipRequestService.getIncoming(profile.id, "friend", offset = 5, limit = 10)
        } returns listOf(request)

        assertEquals(
            listOf(request),
            controller.incomingRelationshipRequests(authentication, profile, "friend", limit = 10, offset = 5),
        )
        coVerify(exactly = 1) {
            profilePermissionEvaluator.verifyCanManageRelationships(authentication, profile)
        }
    }

    @Test
    fun `outgoing relationship requests keep pagination optional`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Requester Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        coEvery {
            profileRelationshipRequestService.getOutgoing(profile.id, null, offset = 0, limit = Int.MAX_VALUE)
        } returns emptyList()

        assertEquals(emptyList(), controller.outgoingRelationshipRequests(authentication, profile))
        coVerify(exactly = 1) {
            profilePermissionEvaluator.verifyCanManageRelationships(authentication, profile)
        }
    }

    @Test
    fun `lastLogin returns date for same principal`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val lastLogin = OffsetDateTime.now()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        coEvery { securityService.getPrincipalLastLogin(principalId) } returns lastLogin

        val result = controller.lastLogin(authentication, profile)

        assertEquals(lastLogin, result)
    }

    @Test
    fun `lastLogin returns date for sa`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val lastLogin = OffsetDateTime.now()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns otherPrincipalId
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { securityService.getPrincipalLastLogin(principalId) } returns lastLogin

        val result = controller.lastLogin(authentication, profile)

        assertEquals(lastLogin, result)
    }

    @Test
    fun `lastLogin returns null for different principal without sa`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns otherPrincipalId
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        val result = controller.lastLogin(authentication, profile)

        assertNull(result)
    }

    @Test
    fun `lastLogin returns null for unauthenticated`() = runTest {
        val principalId = UUID.random()
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )

        val result = controller.lastLogin(null, profile)

        assertNull(result)
    }

    @Test
    fun `lastLogin returns null if profile has no principal`() = runTest {
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.ORGANIZATION,
            principal = null,
            name = "Org Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = mockk<AuthenticationContext>()

        val result = controller.lastLogin(authentication, profile)

        assertNull(result)
    }

    // ── attributes() visibility gating ───────────────────────────────────

    private fun attribute(typeId: String, visibility: ProfileVisibility, profileId: UUID) =
        ProfileAttribute(
            id = UUID.random(),
            profile = profileId,
            typeId = typeId,
            visibility = visibility,
            confidence = 100,
            priority = 0,
            source = "test"
        )

    private fun mixedAttributes(profileId: UUID) = listOf(
        attribute("bosca.profiles.email", ProfileVisibility.USER, profileId),
        attribute("bosca.profiles.location", ProfileVisibility.FRIENDS, profileId),
        attribute("bosca.profiles.school", ProfileVisibility.FRIENDS_OF_FRIENDS, profileId),
        attribute("bosca.profiles.bio", ProfileVisibility.PUBLIC, profileId),
        attribute("bosca.profiles.comment.disabled", ProfileVisibility.SYSTEM, profileId),
    )

    @Test
    fun `attributes gives the owner everything except SYSTEM`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getAttributes(profileId) } returns mixedAttributes(profileId)

        val result = controller.attributes(authentication, profile)

        assertEquals(
            listOf(
                "bosca.profiles.email",
                "bosca.profiles.location",
                "bosca.profiles.school",
                "bosca.profiles.bio",
            ),
            result.map { it.typeId }
        )
    }

    @Test
    fun `attributes returns SYSTEM attributes to a super admin`() = runTest {
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = UUID.random(),
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = mockk<AuthenticationContext>()

        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { profileService.getAttributes(profileId) } returns mixedAttributes(profileId)

        val result = controller.attributes(authentication, profile)

        assertEquals(5, result.size)
        assertEquals(
            listOf(
                ProfileVisibility.USER,
                ProfileVisibility.FRIENDS,
                ProfileVisibility.FRIENDS_OF_FRIENDS,
                ProfileVisibility.PUBLIC,
                ProfileVisibility.SYSTEM
            ),
            result.map { it.visibility }
        )
    }

    @Test
    fun `attributes gives a friend FRIENDS and PUBLIC attributes`() = runTest {
        val viewerProfileId = UUID.random()
        val viewerPrincipal = Principal(id = UUID.random(), primaryProfileId = viewerProfileId)
        val viewerProfile = Profile(
            id = viewerProfileId,
            type = ProfileType.GENERIC,
            principal = viewerPrincipal.id,
            name = "Viewer Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = UUID.random(),
            name = "Friend Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = AuthenticatedPrincipal(viewerPrincipal, emptyList())

        every { authentication.principal() } returns authenticatedPrincipal
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getAttributes(profileId) } returns mixedAttributes(profileId)
        coEvery { profileService.getPrimaryProfile(viewerPrincipal) } returns viewerProfile
        coEvery {
            profileRelationshipService.getRelationship(viewerProfile.id, profile.id, "friend")
        } returns ProfileRelationship(viewerProfile.id, profile.id, "friend")

        val result = controller.attributes(authentication, profile)

        assertEquals(
            listOf("bosca.profiles.location", "bosca.profiles.bio"),
            result.map { it.typeId },
        )
        coVerify(exactly = 1) {
            profileRelationshipService.getRelationship(viewerProfile.id, profile.id, "friend")
        }
    }

    @Test
    fun `attributes gives an authenticated non-friend only PUBLIC attributes`() = runTest {
        val viewerProfileId = UUID.random()
        val viewerPrincipal = Principal(id = UUID.random(), primaryProfileId = viewerProfileId)
        val viewerProfile = Profile(
            id = viewerProfileId,
            type = ProfileType.GENERIC,
            principal = viewerPrincipal.id,
            name = "Viewer Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = UUID.random(),
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = AuthenticatedPrincipal(viewerPrincipal, emptyList())

        every { authentication.principal() } returns authenticatedPrincipal
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getAttributes(profileId) } returns mixedAttributes(profileId)
        coEvery { profileService.getPrimaryProfile(viewerPrincipal) } returns viewerProfile
        coEvery {
            profileRelationshipService.getRelationship(viewerProfile.id, profile.id, "friend")
        } returns null

        val result = controller.attributes(authentication, profile)

        assertEquals(
            listOf("bosca.profiles.bio"),
            result.map { it.typeId }
        )
    }

    @Test
    fun `attributes returns only PUBLIC attributes to unauthenticated callers`() = runTest {
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = UUID.random(),
            name = "Test Profile",
            visibility = ProfileVisibility.PUBLIC
        )

        coEvery { profileService.getAttributes(profileId) } returns mixedAttributes(profileId)

        val result = controller.attributes(null, profile)

        assertEquals(
            listOf("bosca.profiles.bio"),
            result.map { it.typeId }
        )
    }
}
