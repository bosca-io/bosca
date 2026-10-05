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
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecComment
import bosca.workops.model.spec.SpecCommentInput
import bosca.workops.repository.SpecCommentRepository
import bosca.workops.service.SpecCommentService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
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

/**
 * Permission-focused tests for SpecCommentController.
 *
 * Previous behavior: comments on any spec were readable/writable by any
 * authenticated user — no parent-spec permission check. These tests verify
 * the post-fix behavior denies unauthorized callers and routes manager-only
 * read paths only when the caller actually has MANAGE.
 */
class SpecCommentControllerTest {

    private val commentService = mockk<SpecCommentService>(relaxed = true)
    private val commentRepository = mockk<SpecCommentRepository>(relaxed = true)
    private val specService = mockk<SpecService>()
    private val specPermissions = mockk<SpecPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val specId = UUID.random()
    private val spec = Spec(
        id = specId, key = "P-SPEC-1", metadataId = UUID.random(), projectId = UUID.random(),
        statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private fun authenticated(
        primaryProfileId: UUID? = profileId,
        id: UUID = principalId,
    ): AuthenticationContext {
        val principal = Principal(id = id, primaryProfileId = primaryProfileId)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun typeController() = SpecCommentTypeFieldController(
        commentService = commentService,
        commentRepository = commentRepository,
        specService = specService,
        specPermissions = specPermissions,
        profileService = profileService,
        profilePermissions = profilePermissions,
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
        assertEquals(comment.specId, controller.specId(comment))
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
    fun `comment replies select public manager profile and anonymous paths`() = runTest {
        val comment = sampleComment()
        val publicReply = sampleComment(id = 2, parentId = comment.id)
        val managerReply = publicReply.copy(id = 3)
        val profileReply = publicReply.copy(id = 4)
        val missingAuthentication = authenticated()
        val deniedAuthentication = authenticated()
        val managerAuthentication = authenticated()
        val profileAuthentication = authenticated()
        val anonymousAuthentication = AuthenticationContext(null, null)
        coEvery { specService.getById(specId) } returns spec
        coEvery {
            specPermissions.isAllowed(deniedAuthentication, spec, PermissionAction.VIEW)
        } returns false
        coEvery {
            specPermissions.isAllowed(managerAuthentication, spec, PermissionAction.VIEW)
        } returns true
        coEvery {
            specPermissions.isAllowed(managerAuthentication, spec, PermissionAction.MANAGE)
        } returns true
        coEvery {
            specPermissions.isAllowed(profileAuthentication, spec, PermissionAction.VIEW)
        } returns true
        coEvery {
            specPermissions.isAllowed(profileAuthentication, spec, PermissionAction.MANAGE)
        } returns false
        coEvery {
            specPermissions.isAllowed(anonymousAuthentication, spec, PermissionAction.VIEW)
        } returns true
        coEvery {
            specPermissions.isAllowed(anonymousAuthentication, spec, PermissionAction.MANAGE)
        } returns false
        coEvery { commentRepository.listRepliesPublic(specId, comment.id, 0, 10) } returns listOf(publicReply)
        coEvery { commentRepository.listRepliesManager(specId, comment.id, 1, 2) } returns listOf(managerReply)
        coEvery {
            commentRepository.listRepliesForProfile(specId, profileId, comment.id, 3, 4)
        } returns listOf(profileReply)

        val controller = typeController()
        coEvery { specService.getById(specId) } returns null
        assertEquals(listOf(publicReply), controller.replies(comment, missingAuthentication, 0, 10))
        coEvery { specService.getById(specId) } returns spec
        assertEquals(listOf(publicReply), controller.replies(comment, null, 0, 10))
        assertEquals(listOf(publicReply), controller.replies(comment, deniedAuthentication, 0, 10))
        assertEquals(listOf(managerReply), controller.replies(comment, managerAuthentication, 1, 2))
        assertEquals(listOf(profileReply), controller.replies(comment, profileAuthentication, 3, 4))
        assertEquals(listOf(publicReply), controller.replies(comment, anonymousAuthentication, 0, 10))
    }

    @Test
    fun `comment replies resolve fallback profile`() = runTest {
        val comment = sampleComment()
        val authentication = authenticated(primaryProfileId = null)
        val noProfilePrincipalId = UUID.random()
        val noProfileAuthentication = authenticated(primaryProfileId = null, id = noProfilePrincipalId)
        val profile = mockk<Profile>()
        every { profile.id } returns profileId
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns true
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.MANAGE) } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { profileService.getByPrincipal(noProfilePrincipalId) } returns emptyList()
        coEvery {
            commentRepository.listRepliesForProfile(specId, profileId, comment.id, 0, 10)
        } returns emptyList()
        coEvery { commentRepository.listRepliesPublic(specId, comment.id, 1, 2) } returns emptyList()

        assertTrue(typeController().replies(comment, authentication, 0, 10).isEmpty())
        assertTrue(typeController().replies(comment, noProfileAuthentication, 1, 2).isEmpty())
    }

    // --- Query: forSpec ---

    @Test
    fun `forSpec returns empty when spec does not exist`() = runTest {
        val controller = queryController()
        coEvery { specService.getById(specId) } returns null

        val result = controller.forSpec(authenticated(), specId, 0, 50)

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { commentService.listManager(any(), any(), any()) }
        coVerify(exactly = 0) { commentService.listForProfile(any(), any(), any(), any()) }
        coVerify(exactly = 0) { commentService.listPublic(any(), any(), any()) }
    }

    @Test
    fun `forSpec returns empty when caller lacks VIEW on parent spec`() = runTest {
        val controller = queryController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns false

        val result = controller.forSpec(authenticated(), specId, 0, 50)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `forSpec returns manager list when caller has MANAGE on parent spec`() = runTest {
        val controller = queryController()
        val managerComments = listOf(sampleComment())
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns true
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.MANAGE) } returns true
        coEvery { commentService.listManager(specId, 0, 50) } returns managerComments

        val result = controller.forSpec(authenticated(), specId, 0, 50)

        assertEquals(managerComments, result)
        coVerify(exactly = 0) { commentService.listForProfile(any(), any(), any(), any()) }
    }

    @Test
    fun `forSpec returns profile-scoped list when caller has VIEW but not MANAGE`() = runTest {
        val controller = queryController()
        val visibleComments = listOf(sampleComment())
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns true
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.MANAGE) } returns false
        coEvery { commentService.listForProfile(specId, profileId, 0, 50) } returns visibleComments

        val result = controller.forSpec(authenticated(), specId, 0, 50)

        assertEquals(visibleComments, result)
        coVerify(exactly = 0) { commentService.listManager(any(), any(), any()) }
    }

    // --- Query: countForSpec ---

    @Test
    fun `countForSpec returns 0 when caller lacks VIEW`() = runTest {
        val controller = queryController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns false

        assertEquals(0L, controller.countForSpec(authenticated(), specId))
        coVerify(exactly = 0) { commentService.countManager(any()) }
    }

    @Test
    fun `countForSpec returns count when caller has VIEW`() = runTest {
        val controller = queryController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns true
        coEvery { commentService.countManager(specId) } returns 42L

        assertEquals(42L, controller.countForSpec(authenticated(), specId))
    }

    // --- Query: comment (by id) ---

    @Test
    fun `comment returns null when caller lacks VIEW`() = runTest {
        val controller = queryController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns false

        assertNull(controller.comment(authenticated(), specId, commentId = 1))
    }

    @Test
    fun `comment queries select manager profile fallback and public paths`() = runTest {
        val comment = sampleComment()
        val managerAuthentication = authenticated()
        val profileAuthentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        val noProfilePrincipalId = UUID.random()
        val noProfileAuthentication = authenticated(primaryProfileId = null, id = noProfilePrincipalId)
        val publicAuthentication = AuthenticationContext(null, null)
        val fallbackProfile = mockk<Profile>()
        every { fallbackProfile.id } returns profileId
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.VIEW) } returns true
        coEvery {
            specPermissions.isAllowed(managerAuthentication, spec, PermissionAction.MANAGE)
        } returns true
        coEvery {
            specPermissions.isAllowed(profileAuthentication, spec, PermissionAction.MANAGE)
        } returns false
        coEvery {
            specPermissions.isAllowed(fallbackAuthentication, spec, PermissionAction.MANAGE)
        } returns false
        coEvery {
            specPermissions.isAllowed(publicAuthentication, spec, PermissionAction.MANAGE)
        } returns false
        coEvery {
            specPermissions.isAllowed(noProfileAuthentication, spec, PermissionAction.MANAGE)
        } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { profileService.getByPrincipal(noProfilePrincipalId) } returns emptyList()
        coEvery { commentService.listManager(specId, 0, 10) } returns listOf(comment)
        coEvery { commentService.listForProfile(specId, profileId, 1, 2) } returns listOf(comment)
        coEvery { commentService.listPublic(specId, 3, 4) } returns listOf(comment)
        coEvery { commentService.listPublic(specId, 5, 6) } returns listOf(comment)
        coEvery { commentService.countManager(specId) } returns 5
        coEvery { commentService.getManager(specId, comment.id) } returns comment
        coEvery { commentService.getForProfile(specId, comment.id, profileId) } returns comment
        coEvery { commentService.getPublic(specId, comment.id) } returns comment

        val controller = queryController()
        assertEquals(listOf(comment), controller.forSpec(managerAuthentication, specId, 0, 10))
        assertEquals(listOf(comment), controller.forSpec(profileAuthentication, specId, 1, 2))
        assertEquals(listOf(comment), controller.forSpec(fallbackAuthentication, specId, 1, 2))
        assertEquals(listOf(comment), controller.forSpec(publicAuthentication, specId, 3, 4))
        assertEquals(listOf(comment), controller.forSpec(noProfileAuthentication, specId, 5, 6))
        assertEquals(5, controller.countForSpec(managerAuthentication, specId))
        assertSame(comment, controller.comment(managerAuthentication, specId, comment.id))
        assertSame(comment, controller.comment(profileAuthentication, specId, comment.id))
        assertSame(comment, controller.comment(publicAuthentication, specId, comment.id))
    }

    @Test
    fun `comment queries fail closed for missing or invisible specs`() = runTest {
        val missingId = UUID.random()
        val authentication = authenticated()
        coEvery { specService.getById(missingId) } returns null
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.isAllowed(authentication, spec, PermissionAction.VIEW) } returns false

        val controller = queryController()
        assertTrue(controller.forSpec(authentication, missingId, 0, 10).isEmpty())
        assertEquals(0, controller.countForSpec(authentication, missingId))
        assertNull(controller.comment(authentication, missingId, 1))
        assertTrue(controller.forSpec(authentication, specId, 0, 10).isEmpty())
        assertEquals(0, controller.countForSpec(authentication, specId))
        assertNull(controller.comment(authentication, specId, 1))
    }

    // --- Mutations: add (requires EDIT) ---

    @Test
    fun `add throws when caller lacks EDIT on parent spec`() = runTest {
        val controller = mutationController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.verifyAllowed(any(), spec, PermissionAction.EDIT) } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.add(authenticated(), specId, SpecCommentInput(content = "hi"))
        }
        coVerify(exactly = 0) { commentService.add(any(), any(), any(), any()) }
    }

    @Test
    fun `add errors when spec not found`() = runTest {
        val controller = mutationController()
        coEvery { specService.getById(specId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.add(authenticated(), specId, SpecCommentInput(content = "hi"))
        }
    }

    // --- Mutations: setStatus (requires MANAGE) ---

    @Test
    fun `setStatus throws when caller lacks MANAGE`() = runTest {
        val controller = mutationController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.verifyAllowed(any(), spec, PermissionAction.MANAGE) } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.setStatus(authenticated(), specId, commentId = 1, status = CommentStatus.APPROVED)
        }
        coVerify(exactly = 0) { commentService.setStatus(any(), any(), any(), any(), any()) }
    }

    // --- Mutations: delete (requires MANAGE) ---

    @Test
    fun `delete throws when caller lacks MANAGE`() = runTest {
        val controller = mutationController()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specPermissions.verifyAllowed(any(), spec, PermissionAction.MANAGE) } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.delete(authenticated(), specId, commentId = 1)
        }
        coVerify(exactly = 0) { commentService.delete(any(), any(), any(), any()) }
    }

    @Test
    fun `comment mutations resolve actors and expose updated like counts`() = runTest {
        val authentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        val fallbackProfile = mockk<Profile>()
        val input = SpecCommentInput(content = "Comment")
        val comment = sampleComment()
        every { fallbackProfile.id } returns profileId
        coEvery { specService.getById(specId) } returns spec
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { commentService.add(specId, input, principalId, profileId) } returns comment
        coEvery { commentService.getManager(specId, 1) } returns comment.copy(likes = 2)
        coEvery { commentService.getManager(specId, 2) } returns null
        coEvery { commentService.getManager(specId, 3) } returns null
        coEvery { commentService.getManager(specId, 4) } returns comment.copy(likes = 3)

        val controller = mutationController()
        assertSame(comment, controller.add(authentication, specId, input))
        assertEquals(2, controller.like(authentication, specId, 1))
        assertEquals(-1, controller.unlike(fallbackAuthentication, specId, 2))
        assertEquals(-1, controller.like(authentication, specId, 3))
        assertEquals(3, controller.unlike(authentication, specId, 4))
        assertTrue(controller.setStatus(authentication, specId, 1, CommentStatus.APPROVED))
        assertTrue(controller.delete(authentication, specId, 1))

        coVerify { commentService.like(specId, 1, profileId) }
        coVerify { commentService.unlike(specId, 2, profileId) }
        coVerify { commentService.setStatus(specId, 1, CommentStatus.APPROVED, principalId, profileId) }
        coVerify { commentService.delete(specId, 1, principalId, profileId) }
    }

    @Test
    fun `comment mutations require an existing spec authenticated principal and profile`() = runTest {
        val missingId = UUID.random()
        val authenticated = authenticated()
        val noProfile = authenticated(primaryProfileId = null)
        val unauthenticated = AuthenticationContext(null, null)
        val input = SpecCommentInput(content = "Comment")
        coEvery { specService.getById(missingId) } returns null
        coEvery { specService.getById(specId) } returns spec
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()

        val controller = mutationController()
        assertMutationFailures(controller, authenticated, missingId, input)
        assertMutationFailures(controller, unauthenticated, specId, input)
        assertMutationFailures(controller, noProfile, specId, input)
    }

    // --- helpers ---

    private fun queryController() = SpecCommentQueryController(
        commentService = commentService,
        specService = specService,
        specPermissions = specPermissions,
        profileService = profileService,
    )

    private fun mutationController() = SpecCommentMutationController(
        commentService = commentService,
        specService = specService,
        specPermissions = specPermissions,
        profileService = profileService,
    )

    private suspend fun assertMutationFailures(
        controller: SpecCommentMutationController,
        authentication: AuthenticationContext,
        targetSpecId: UUID,
        input: SpecCommentInput,
    ) {
        assertFailsWith<IllegalStateException> { controller.add(authentication, targetSpecId, input) }
        assertFailsWith<IllegalStateException> { controller.like(authentication, targetSpecId, 1) }
        assertFailsWith<IllegalStateException> { controller.unlike(authentication, targetSpecId, 1) }
        assertFailsWith<IllegalStateException> {
            controller.setStatus(authentication, targetSpecId, 1, CommentStatus.APPROVED)
        }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, targetSpecId, 1) }
    }

    private fun sampleComment(
        id: Long = 1,
        parentId: Long? = null,
    ): SpecComment {
        val now = OffsetDateTime.now()
        return SpecComment(
            id = id,
            parentId = parentId,
            specId = specId,
            profileId = profileId,
            visibility = ProfileVisibility.USER,
            created = now,
            modified = now,
            status = CommentStatus.APPROVED,
            content = "comment",
        )
    }
}
