package bosca.profile.profile.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.service.CollectionService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfilesMutationControllerTest {

    private val profileService = mockk<ProfileService>()
    private val profileRelationshipService = mockk<ProfileRelationshipService>()
    private val profileRelationshipRequestService = mockk<ProfileRelationshipRequestService>()
    private val collectionService = mockk<CollectionService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()

    private val controller = ProfilesMutationController(
        profileService,
        profileRelationshipService,
        profileRelationshipRequestService,
        collectionService,
        groupEvaluator,
        permissionEvaluator
    )

    private fun createAuthContext(principalId: UUID): AuthenticationContext {
        val authentication = mockk<AuthenticationContext>()
        val principal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns principal
        every { principal.id } returns principalId
        return authentication
    }

    @Test
    fun `add creates profile with GENERIC type`() = runTest {
        val principalId = UUID.random()
        val authentication = createAuthContext(principalId)
        val input = ProfileInput(name = "New Profile", visibility = ProfileVisibility.PUBLIC)
        val expected = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "New Profile",
            visibility = ProfileVisibility.PUBLIC
        )

        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.add(input, ProfileType.GENERIC, principalId, allowProtected = false) } returns expected

        val result = controller.add(authentication, input)

        assertEquals(expected, result)
    }

    @Test
    fun `add passes the caller's SA capability through as allowProtected`() = runTest {
        val principalId = UUID.random()
        val authentication = createAuthContext(principalId)
        val input = ProfileInput(name = "Admin Profile", visibility = ProfileVisibility.PUBLIC)
        val expected = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Admin Profile",
            visibility = ProfileVisibility.PUBLIC
        )

        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { profileService.add(input, ProfileType.GENERIC, principalId, allowProtected = true) } returns expected

        val result = controller.add(authentication, input)

        assertEquals(expected, result)
        coVerify { profileService.add(input, ProfileType.GENERIC, principalId, allowProtected = true) }
    }

    @Test
    fun `add with linkToPrincipal false creates profile without principal link`() = runTest {
        val principalId = UUID.random()
        val authentication = createAuthContext(principalId)
        val input = ProfileInput(name = "Unlinked Profile", visibility = ProfileVisibility.PUBLIC)
        val expected = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Unlinked Profile",
            visibility = ProfileVisibility.PUBLIC
        )

        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.add(input, ProfileType.GENERIC, null, allowProtected = false) } returns expected

        val result = controller.add(authentication, input, linkToPrincipal = false)

        assertEquals(expected, result)
    }

    @Test
    fun `add rejects unauthenticated principal`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        every { authentication.principal() } returns null
        val input = ProfileInput(name = "Test", visibility = ProfileVisibility.PUBLIC)

        try {
            controller.add(authentication, input)
            throw AssertionError("Expected SecurityException")
        } catch (_: bosca.security.service.SecurityException) {
            // expected
        }
    }

    @Test
    fun `addChild creates profile with CHILD type`() = runTest {
        val principalId = UUID.random()
        val authentication = createAuthContext(principalId)
        val input = ProfileInput(name = "Child Profile", visibility = ProfileVisibility.PUBLIC)
        val expected = Profile(
            id = UUID.random(),
            type = ProfileType.CHILD,
            principal = principalId,
            name = "Child Profile",
            visibility = ProfileVisibility.PUBLIC
        )

        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.add(input, ProfileType.CHILD, principalId, allowProtected = false) } returns expected

        val result = controller.addChild(authentication, input)

        assertEquals(expected, result)
    }

    @Test
    fun `addCollection creates personal collection and grants profile permissions`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            name = "Profile",
            visibility = ProfileVisibility.PUBLIC,
        )
        val collection = mockk<Collection>()
        val permission = mockk<EntityPermission>()

        every { collection.id } returns collectionId
        every { permission.groupId } returns UUID.random()
        every { permission.action } returns PermissionAction.EDIT
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT) } returns true
        coEvery { collectionService.add(CollectionInput(name = profile.name)) } returns collection
        coEvery { profileService.setCollectionId(profileId, collectionId) } returns profile
        coEvery { profileService.getPermissions(profile) } returns listOf(permission)
        coEvery {
            collectionService.addPermission(collectionId, permission.groupId, permission.action)
        } returns mockk()

        assertEquals(collection, controller.addCollection(authentication, profileId))
        coVerify { profileService.setCollectionId(profileId, collectionId) }
        coVerify { collectionService.addPermission(collectionId, permission.groupId, permission.action) }
    }

    @Test
    fun `addCollection backfills profile permissions on existing personal collection`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            name = "Profile",
            visibility = ProfileVisibility.PUBLIC,
            collectionId = collectionId,
        )
        val collection = mockk<Collection>()
        val permission = mockk<EntityPermission>()

        every { collection.id } returns collectionId
        every { permission.groupId } returns UUID.random()
        every { permission.action } returns PermissionAction.VIEW
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT) } returns true
        coEvery { collectionService.getById(collectionId) } returns collection
        coEvery { profileService.getPermissions(profile) } returns listOf(permission)
        coEvery {
            collectionService.addPermission(collectionId, permission.groupId, permission.action)
        } returns mockk()

        assertEquals(collection, controller.addCollection(authentication, profileId))
        coVerify(exactly = 0) { profileService.setCollectionId(any(), any()) }
        coVerify { collectionService.addPermission(collectionId, permission.groupId, permission.action) }
    }

    @Test
    fun `addRelationship delegates to relationship service for an administrator`() = runTest {
        val sourceProfileId = UUID.random()
        val targetProfileId = UUID.random()
        val authentication = createAuthContext(UUID.random())

        every { groupEvaluator.verifyHasSaGroup(authentication) } just Runs
        coEvery { profileRelationshipService.addRelationship(sourceProfileId, targetProfileId, "friend", null) } just Runs

        val result = controller.addRelationship(authentication, sourceProfileId, targetProfileId, "friend", null)

        assertTrue(result)
        verify(exactly = 1) { groupEvaluator.verifyHasSaGroup(authentication) }
        coVerify { profileRelationshipService.addRelationship(sourceProfileId, targetProfileId, "friend", null) }
    }

    @Test
    fun `requestRelationship creates a pending request from an owned profile`() = runTest {
        val principalId = UUID.random()
        val requesterProfileId = UUID.random()
        val targetProfileId = UUID.random()
        val authentication = createAuthContext(principalId)
        val requester = profile(requesterProfileId, principalId)
        val target = profile(targetProfileId)
        val request = relationshipRequest(requesterProfileId, targetProfileId)

        coEvery { profileService.getById(requesterProfileId) } returns requester
        coEvery { profileService.getById(targetProfileId) } returns target
        coEvery { permissionEvaluator.verifyCanManageRelationships(authentication, requester) } returns Unit
        coEvery { permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.VIEW) } returns Unit
        coEvery {
            profileRelationshipRequestService.request(requesterProfileId, targetProfileId, "friend", null)
        } returns request

        assertEquals(
            request,
            controller.requestRelationship(authentication, requesterProfileId, targetProfileId, "friend", null),
        )
        coVerify(exactly = 1) {
            permissionEvaluator.verifyCanManageRelationships(authentication, requester)
            permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.VIEW)
        }
    }

    @Test
    fun `requestRelationship rejects a target the requester cannot view`() = runTest {
        val principalId = UUID.random()
        val requester = profile(UUID.random(), principalId)
        val target = profile(UUID.random())
        val authentication = createAuthContext(principalId)

        coEvery { profileService.getById(requester.id) } returns requester
        coEvery { profileService.getById(target.id) } returns target
        coEvery { permissionEvaluator.verifyCanManageRelationships(authentication, requester) } returns Unit
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.VIEW)
        } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.requestRelationship(authentication, requester.id, target.id, "friend", null)
        }
        coVerify(exactly = 0) { profileRelationshipRequestService.request(any(), any(), any(), any()) }
    }

    @Test
    fun `requestRelationship rejects a requester the caller does not manage`() = runTest {
        val requester = profile(UUID.random())
        val authentication = createAuthContext(UUID.random())
        coEvery { profileService.getById(requester.id) } returns requester
        coEvery {
            permissionEvaluator.verifyCanManageRelationships(authentication, requester)
        } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.requestRelationship(authentication, requester.id, UUID.random(), "friend", null)
        }
        coVerify(exactly = 0) { profileRelationshipRequestService.request(any(), any(), any(), any()) }
    }

    @Test
    fun `approveRelationshipRequest requires management of the target profile`() = runTest {
        val principalId = UUID.random()
        val requesterProfileId = UUID.random()
        val targetProfileId = UUID.random()
        val authentication = createAuthContext(principalId)
        val target = profile(targetProfileId)
        val pending = relationshipRequest(requesterProfileId, targetProfileId)
        val approved = pending.copy(status = ProfileRelationshipRequestStatus.APPROVED, version = 1)

        coEvery { profileRelationshipRequestService.getById(pending.id) } returns pending
        coEvery { profileService.getById(targetProfileId) } returns target
        coEvery { permissionEvaluator.verifyCanManageRelationships(authentication, target) } returns Unit
        coEvery { profileRelationshipRequestService.approve(pending.id) } returns approved

        assertEquals(approved, controller.approveRelationshipRequest(authentication, pending.id))
    }

    @Test
    fun `approveRelationshipRequest rejects a caller who does not manage the target profile`() = runTest {
        val target = profile(UUID.random())
        val pending = relationshipRequest(UUID.random(), target.id)
        val authentication = createAuthContext(UUID.random())

        coEvery { profileRelationshipRequestService.getById(pending.id) } returns pending
        coEvery { profileService.getById(target.id) } returns target
        coEvery {
            permissionEvaluator.verifyCanManageRelationships(authentication, target)
        } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.approveRelationshipRequest(authentication, pending.id)
        }
        coVerify(exactly = 0) { profileRelationshipRequestService.approve(any()) }
    }

    @Test
    fun `declineRelationshipRequest requires management of the target profile`() = runTest {
        val principalId = UUID.random()
        val requesterProfileId = UUID.random()
        val targetProfileId = UUID.random()
        val authentication = createAuthContext(principalId)
        val target = profile(targetProfileId)
        val pending = relationshipRequest(requesterProfileId, targetProfileId)
        val declined = pending.copy(status = ProfileRelationshipRequestStatus.DECLINED, version = 1)

        coEvery { profileRelationshipRequestService.getById(pending.id) } returns pending
        coEvery { profileService.getById(targetProfileId) } returns target
        coEvery { permissionEvaluator.verifyCanManageRelationships(authentication, target) } returns Unit
        coEvery { profileRelationshipRequestService.decline(pending.id) } returns declined

        assertEquals(declined, controller.declineRelationshipRequest(authentication, pending.id))
    }

    @Test
    fun `cancelRelationshipRequest requires management of the requester profile`() = runTest {
        val principalId = UUID.random()
        val requesterProfileId = UUID.random()
        val targetProfileId = UUID.random()
        val authentication = createAuthContext(principalId)
        val requester = profile(requesterProfileId, principalId)
        val pending = relationshipRequest(requesterProfileId, targetProfileId)
        val cancelled = pending.copy(status = ProfileRelationshipRequestStatus.CANCELLED, version = 1)

        coEvery { profileRelationshipRequestService.getById(pending.id) } returns pending
        coEvery { profileService.getById(requesterProfileId) } returns requester
        coEvery { permissionEvaluator.verifyCanManageRelationships(authentication, requester) } returns Unit
        coEvery { profileRelationshipRequestService.cancel(pending.id) } returns cancelled

        assertEquals(cancelled, controller.cancelRelationshipRequest(authentication, pending.id))
    }

    @Test
    fun `removeRelationship delegates when caller manages either participant`() = runTest {
        val source = profile(UUID.random())
        val target = profile(UUID.random())
        val authentication = createAuthContext(UUID.random())

        coEvery { profileService.getById(source.id) } returns source
        coEvery { profileService.getById(target.id) } returns target
        coEvery {
            permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, source, target)
        } returns Unit
        coEvery { profileRelationshipService.removeRelationship(source.id, target.id, "friend") } just Runs

        val result = controller.removeRelationship(authentication, source.id, target.id, "friend")

        assertTrue(result)
        coVerify { profileRelationshipService.removeRelationship(source.id, target.id, "friend") }
    }

    @Test
    fun `removeRelationship rejects a caller who manages neither participant`() = runTest {
        val source = profile(UUID.random())
        val target = profile(UUID.random())
        val authentication = createAuthContext(UUID.random())

        coEvery { profileService.getById(source.id) } returns source
        coEvery { profileService.getById(target.id) } returns target
        coEvery {
            permissionEvaluator.verifyCanManageEitherRelationshipParticipant(authentication, source, target)
        } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.removeRelationship(authentication, source.id, target.id, "friend")
        }
        coVerify(exactly = 0) { profileRelationshipService.removeRelationship(any(), any(), any()) }
    }

    @Test
    fun `profile returns ProfileMutation singleton`() {
        val result = controller.profile()

        assertNotNull(result)
    }

    private fun relationshipRequest(
        requesterProfileId: UUID,
        targetProfileId: UUID,
    ) = ProfileRelationshipRequest(
        id = UUID.random(),
        requesterProfileId = requesterProfileId,
        targetProfileId = targetProfileId,
        type = "friend",
        status = ProfileRelationshipRequestStatus.PENDING,
        version = 0,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun profile(
        id: UUID,
        principalId: UUID? = null,
    ) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        principal = principalId,
        name = "Profile",
        visibility = ProfileVisibility.PUBLIC,
    )
}
