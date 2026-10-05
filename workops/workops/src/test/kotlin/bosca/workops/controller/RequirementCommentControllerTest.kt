package bosca.workops.controller

import bosca.comments.model.CommentStatus
import bosca.profile.model.Profile
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementComment
import bosca.workops.model.requirement.RequirementCommentInput
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.repository.RequirementCommentRepository
import bosca.workops.service.RequirementCommentService
import bosca.workops.service.RequirementPermissionEvaluator
import bosca.workops.service.RequirementService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequirementCommentControllerTest {

    private val commentService = mockk<RequirementCommentService>(relaxed = true)
    private val commentRepository = mockk<RequirementCommentRepository>(relaxed = true)
    private val requirementService = mockk<RequirementService>(relaxed = true)
    private val requirementPermissions = mockk<RequirementPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    private fun typeController() = RequirementCommentTypeFieldController(
        commentService,
        commentRepository,
        requirementService,
        requirementPermissions,
        profileService,
        profilePermissions,
    )

    private fun queryController() = RequirementCommentQueryController(
        commentService,
        requirementService,
        requirementPermissions,
        profileService,
    )

    private fun mutationController() = RequirementCommentMutationController(
        commentService,
        requirementService,
        requirementPermissions,
        profileService,
    )

    @Test
    fun `comment fields expose values and hide inaccessible profiles`() = runTest {
        val comment = sampleComment()
        val authentication = authenticated()
        val profile = mockk<Profile>()
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true

        val controller = typeController()
        assertEquals(comment.id, controller.id(comment))
        assertEquals(comment.parentId, controller.parentId(comment))
        assertEquals(comment.requirementId, controller.requirementId(comment))
        assertEquals(comment.profileId, controller.profileId(comment))
        assertEquals(comment.impersonatorId, controller.impersonatorId(comment))
        assertEquals(comment.visibility, controller.visibility(comment))
        assertEquals(comment.created, controller.created(comment))
        assertEquals(comment.modified, controller.modified(comment))
        assertEquals(comment.status, controller.status(comment))
        assertEquals(comment.content, controller.content(comment))
        assertEquals(comment.likes, controller.likes(comment))
        assertEquals(comment.deleted, controller.deleted(comment))
        assertSame(profile, controller.profile(authentication, comment))

        coEvery { profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false
        assertNull(controller.profile(authentication, comment))
    }

    @Test
    fun `comment replies select public manager and profile visibility paths`() = runTest {
        val requirement = sampleRequirement()
        val comment = sampleComment(requirement.id)
        val publicReply = sampleComment(requirement.id, id = 2, parentId = comment.id)
        val managerReply = publicReply.copy(id = 3)
        val profileReply = publicReply.copy(id = 4)
        val missingAuthentication = authenticated()
        val deniedAuthentication = authenticated()
        val managerAuthentication = authenticated()
        val profileAuthentication = authenticated()
        val publicAuthentication = AuthenticationContext(null, null)
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery {
            requirementPermissions.isAllowed(deniedAuthentication, requirement, PermissionAction.VIEW)
        } returns false
        coEvery {
            requirementPermissions.isAllowed(managerAuthentication, requirement, PermissionAction.VIEW)
        } returns true
        coEvery {
            requirementPermissions.isAllowed(managerAuthentication, requirement, PermissionAction.MANAGE)
        } returns true
        coEvery {
            requirementPermissions.isAllowed(profileAuthentication, requirement, PermissionAction.VIEW)
        } returns true
        coEvery {
            requirementPermissions.isAllowed(profileAuthentication, requirement, PermissionAction.MANAGE)
        } returns false
        coEvery {
            requirementPermissions.isAllowed(publicAuthentication, requirement, PermissionAction.VIEW)
        } returns true
        coEvery {
            requirementPermissions.isAllowed(publicAuthentication, requirement, PermissionAction.MANAGE)
        } returns false
        coEvery { commentRepository.listRepliesPublic(requirement.id, comment.id, 0, 10) } returns listOf(publicReply)
        coEvery { commentRepository.listRepliesManager(requirement.id, comment.id, 1, 2) } returns listOf(managerReply)
        coEvery {
            commentRepository.listRepliesForProfile(requirement.id, profileId, comment.id, 3, 4)
        } returns listOf(profileReply)

        val controller = typeController()
        coEvery { requirementService.getById(requirement.id) } returns null
        assertEquals(listOf(publicReply), controller.replies(comment, missingAuthentication, 0, 10))
        coEvery { requirementService.getById(requirement.id) } returns requirement
        assertEquals(listOf(publicReply), controller.replies(comment, null, 0, 10))
        assertEquals(listOf(publicReply), controller.replies(comment, deniedAuthentication, 0, 10))
        assertEquals(listOf(managerReply), controller.replies(comment, managerAuthentication, 1, 2))
        assertEquals(listOf(profileReply), controller.replies(comment, profileAuthentication, 3, 4))
        assertEquals(listOf(publicReply), controller.replies(comment, publicAuthentication, 0, 10))
    }

    @Test
    fun `comment replies resolve a fallback viewing profile`() = runTest {
        val requirement = sampleRequirement()
        val comment = sampleComment(requirement.id)
        val authentication = authenticated(primaryProfileId = null)
        val profile = mockk<Profile>()
        every { profile.id } returns profileId
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { requirementPermissions.isAllowed(authentication, requirement, PermissionAction.VIEW) } returns true
        coEvery { requirementPermissions.isAllowed(authentication, requirement, PermissionAction.MANAGE) } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery {
            commentRepository.listRepliesForProfile(requirement.id, profileId, comment.id, 0, 10)
        } returns emptyList()

        assertTrue(typeController().replies(comment, authentication, 0, 10).isEmpty())
    }

    @Test
    fun `comment queries select manager profile fallback and public paths`() = runTest {
        val requirement = sampleRequirement()
        val comment = sampleComment(requirement.id)
        val managerAuthentication = authenticated()
        val profileAuthentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        val publicAuthentication = AuthenticationContext(null, null)
        val fallbackProfile = mockk<Profile>()
        every { fallbackProfile.id } returns profileId
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { requirementPermissions.isAllowed(any<AuthenticationContext>(), requirement, PermissionAction.VIEW) } returns true
        coEvery {
            requirementPermissions.isAllowed(managerAuthentication, requirement, PermissionAction.MANAGE)
        } returns true
        coEvery {
            requirementPermissions.isAllowed(profileAuthentication, requirement, PermissionAction.MANAGE)
        } returns false
        coEvery {
            requirementPermissions.isAllowed(fallbackAuthentication, requirement, PermissionAction.MANAGE)
        } returns false
        coEvery {
            requirementPermissions.isAllowed(publicAuthentication, requirement, PermissionAction.MANAGE)
        } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { commentService.listManager(requirement.id, 0, 10) } returns listOf(comment)
        coEvery { commentService.listForProfile(requirement.id, profileId, 1, 2) } returns listOf(comment)
        coEvery { commentService.listPublic(requirement.id, 3, 4) } returns listOf(comment)
        coEvery { commentService.countManager(requirement.id) } returns 5
        coEvery { commentService.getManager(requirement.id, comment.id) } returns comment
        coEvery { commentService.getForProfile(requirement.id, comment.id, profileId) } returns comment
        coEvery { commentService.getPublic(requirement.id, comment.id) } returns comment

        val controller = queryController()
        assertEquals(listOf(comment), controller.forRequirement(managerAuthentication, requirement.id, 0, 10))
        assertEquals(listOf(comment), controller.forRequirement(profileAuthentication, requirement.id, 1, 2))
        assertEquals(listOf(comment), controller.forRequirement(fallbackAuthentication, requirement.id, 1, 2))
        assertEquals(listOf(comment), controller.forRequirement(publicAuthentication, requirement.id, 3, 4))
        assertEquals(5, controller.countForRequirement(managerAuthentication, requirement.id))
        assertSame(comment, controller.comment(managerAuthentication, requirement.id, comment.id))
        assertSame(comment, controller.comment(profileAuthentication, requirement.id, comment.id))
        assertSame(comment, controller.comment(publicAuthentication, requirement.id, comment.id))
    }

    @Test
    fun `comment queries fail closed for missing or invisible requirements`() = runTest {
        val requirement = sampleRequirement()
        val missingId = UUID.random()
        val authentication = authenticated()
        coEvery { requirementService.getById(missingId) } returns null
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { requirementPermissions.isAllowed(authentication, requirement, PermissionAction.VIEW) } returns false

        val controller = queryController()
        assertTrue(controller.forRequirement(authentication, missingId, 0, 10).isEmpty())
        assertEquals(0, controller.countForRequirement(authentication, missingId))
        assertNull(controller.comment(authentication, missingId, 1))
        assertTrue(controller.forRequirement(authentication, requirement.id, 0, 10).isEmpty())
        assertEquals(0, controller.countForRequirement(authentication, requirement.id))
        assertNull(controller.comment(authentication, requirement.id, 1))
    }

    @Test
    fun `comment mutations resolve actors and expose updated like counts`() = runTest {
        val requirement = sampleRequirement()
        val authentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        val fallbackProfile = mockk<Profile>()
        val input = RequirementCommentInput(content = "Comment")
        val comment = sampleComment(requirement.id)
        every { fallbackProfile.id } returns profileId
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { commentService.add(requirement.id, input, principalId, profileId) } returns comment
        coEvery { commentService.getManager(requirement.id, 1) } returns comment.copy(likes = 2)
        coEvery { commentService.getManager(requirement.id, 2) } returns null

        val controller = mutationController()
        assertSame(comment, controller.add(authentication, requirement.id, input))
        assertEquals(2, controller.like(authentication, requirement.id, 1))
        assertEquals(-1, controller.unlike(fallbackAuthentication, requirement.id, 2))
        assertTrue(controller.setStatus(authentication, requirement.id, 1, CommentStatus.APPROVED))
        assertTrue(controller.delete(authentication, requirement.id, 1))

        coVerify { commentService.like(requirement.id, 1, profileId) }
        coVerify { commentService.unlike(requirement.id, 2, profileId) }
        coVerify { commentService.setStatus(requirement.id, 1, CommentStatus.APPROVED, principalId, profileId) }
        coVerify { commentService.delete(requirement.id, 1, principalId, profileId) }
    }

    @Test
    fun `comment mutations require an existing requirement authenticated principal and profile`() = runTest {
        val requirement = sampleRequirement()
        val missingId = UUID.random()
        val authenticated = authenticated()
        val noProfile = authenticated(primaryProfileId = null)
        val unauthenticated = AuthenticationContext(null, null)
        val input = RequirementCommentInput(content = "Comment")
        coEvery { requirementService.getById(missingId) } returns null
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()

        val controller = mutationController()
        assertMutationFailures(controller, authenticated, missingId, input)
        assertMutationFailures(controller, unauthenticated, requirement.id, input)
        assertMutationFailures(controller, noProfile, requirement.id, input)
    }

    private suspend fun assertMutationFailures(
        controller: RequirementCommentMutationController,
        authentication: AuthenticationContext,
        requirementId: UUID,
        input: RequirementCommentInput,
    ) {
        assertFailsWith<IllegalStateException> { controller.add(authentication, requirementId, input) }
        assertFailsWith<IllegalStateException> { controller.like(authentication, requirementId, 1) }
        assertFailsWith<IllegalStateException> { controller.unlike(authentication, requirementId, 1) }
        assertFailsWith<IllegalStateException> {
            controller.setStatus(authentication, requirementId, 1, CommentStatus.APPROVED)
        }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, requirementId, 1) }
    }

    private fun sampleRequirement() = Requirement(
        id = UUID.random(),
        key = "GIT-REQ-1",
        metadataId = UUID.random(),
        parentType = RequirementParent.SPEC,
        parentId = UUID.random(),
        statusId = UUID.random(),
        workflowId = UUID.random(),
        priorityId = UUID.random(),
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun sampleComment(
        requirementId: UUID = UUID.random(),
        id: Long = 1,
        parentId: Long? = null,
    ) = RequirementComment(
        id = id,
        parentId = parentId,
        requirementId = requirementId,
        profileId = profileId,
        visibility = ProfileVisibility.USER,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        status = CommentStatus.APPROVED,
        content = "Comment",
    )
}
