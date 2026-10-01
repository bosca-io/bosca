package bosca.git.security

import bosca.git.model.Repository
import bosca.git.model.RepositoryPermission
import bosca.git.model.Visibility
import bosca.git.service.RepositoryService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RepositoryPermissionEvaluatorTest {

    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private lateinit var evaluator: RepositoryPermissionEvaluator

    private val ownerId = UUID.random()
    private val viewGroupId = UUID.random()
    private val editGroupId = UUID.random()
    private val manageGroupId = UUID.random()

    @BeforeTest
    fun setup() {
        evaluator = RepositoryPermissionEvaluator(repositoryService, securityService, groupEvaluator)
        every { groupEvaluator.hasScope(any(), any()) } answers {
            GroupEvaluator(securityService).hasScope(args[0] as? AuthenticationContext, args[1] as String)
        }
        // Default mocks inspect the actual principal's groups, so tests that wire a
        // principal carrying e.g. the admin group don't also have to remember to
        // override the mock. Individual tests can still `every { … } returns …`
        // to force a specific outcome (last-write-wins in mockk).
        every { groupEvaluator.hasSaGroup(any()) } answers {
            val auth = args[0] as? AuthenticationContext
            (auth?.principal() as? ScopedAuthenticatedPrincipal)?.hasGroup("sa") == true
        }
        every { groupEvaluator.hasAdminGroup(any()) } answers {
            val auth = args[0] as? AuthenticationContext
            (auth?.principal() as? ScopedAuthenticatedPrincipal)?.hasGroup("administrators") == true
        }
        every { groupEvaluator.hasEditorGroup(any()) } answers {
            val auth = args[0] as? AuthenticationContext
            (auth?.principal() as? ScopedAuthenticatedPrincipal)?.hasGroup("editors") == true
        }
    }

    private fun repo(
        id: UUID = UUID.random(),
        visibility: Visibility = Visibility.PRIVATE,
        deleted: Boolean = false
    ) = Repository(
        id = id,
        slug = "test-repo",
        name = "Test",
        ownerId = ownerId,
        visibility = visibility,
        deleted = deleted
    )

    private fun authContext(vararg groups: UUID): ImpersonatedAuthenticationContext {
        val principal = Principal(id = UUID.random())
        val groupList = groups.map { Group(id = it, name = "group", description = "group", type = GroupType.PRINCIPAL) }
        return ImpersonatedAuthenticationContext(principal, groupList)
    }

    @Test
    fun `public repo allows anonymous VIEW`() = runTest {
        val repository = repo(visibility = Visibility.PUBLIC)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        assertTrue(evaluator.isAllowed(null, repository, PermissionAction.VIEW))
    }

    @Test
    fun `private repo denies anonymous VIEW`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        assertFalse(evaluator.isAllowed(null, repository, PermissionAction.VIEW))
    }

    @Test
    fun `private repo denies anonymous EDIT`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        assertFalse(evaluator.isAllowed(null, repository, PermissionAction.EDIT))
    }

    @Test
    fun `group with VIEW grant allows fetch on private repo`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        val permissions: List<EntityPermission> = listOf(
            RepositoryPermission(repository.id, viewGroupId, PermissionAction.VIEW)
        )
        coEvery { repositoryService.getPermissions(repository) } returns permissions
        val auth = authContext(viewGroupId)
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.VIEW))
    }

    @Test
    fun `group with VIEW grant denied push on private repo`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        val permissions: List<EntityPermission> = listOf(
            RepositoryPermission(repository.id, viewGroupId, PermissionAction.VIEW)
        )
        coEvery { repositoryService.getPermissions(repository) } returns permissions
        val auth = authContext(viewGroupId)
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `group with EDIT grant allows push`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        val permissions: List<EntityPermission> = listOf(
            RepositoryPermission(repository.id, editGroupId, PermissionAction.EDIT)
        )
        coEvery { repositoryService.getPermissions(repository) } returns permissions
        val auth = authContext(editGroupId)
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `MANAGE grant implies EDIT`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        val permissions: List<EntityPermission> = listOf(
            RepositoryPermission(repository.id, manageGroupId, PermissionAction.MANAGE)
        )
        coEvery { repositoryService.getPermissions(repository) } returns permissions
        val auth = authContext(manageGroupId)
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `MANAGE required for configuration changes`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        val permissions: List<EntityPermission> = listOf(
            RepositoryPermission(repository.id, editGroupId, PermissionAction.EDIT)
        )
        coEvery { repositoryService.getPermissions(repository) } returns permissions
        val auth = authContext(editGroupId)
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.MANAGE))
    }

    @Test
    fun `deleted repo denies all non-SA access`() = runTest {
        val repository = repo(deleted = true)
        val permissions: List<EntityPermission> = listOf(
            RepositoryPermission(repository.id, editGroupId, PermissionAction.EDIT)
        )
        coEvery { repositoryService.getPermissions(repository) } returns permissions
        val auth = authContext(editGroupId)
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.VIEW))
    }

    private val adminGroupId = UUID.random()
    private val adminGroup = Group(id = adminGroupId, name = "administrators", description = "admins", type = GroupType.PRINCIPAL)
    private val editorGroupId = UUID.random()
    private val editorGroup = Group(id = editorGroupId, name = "editors", description = "editors", type = GroupType.PRINCIPAL)

    private fun scopedAuthContext(groups: List<Group>, scopes: List<String>?): AuthenticationContext {
        val principal = Principal(id = UUID.random())
        val scoped = ScopedAuthenticatedPrincipal(principal, groups, scopes, null, 1L)
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns scoped
        return auth
    }

    @Test
    fun `repository grants cannot expand a read only token`() = runTest {
        val repository = repo()
        val grantedGroup = Group(id = editGroupId, name = "contributors", description = "contributors", type = GroupType.PRINCIPAL)
        coEvery { repositoryService.getPermissions(repository) } returns listOf(
            RepositoryPermission(repository.id, editGroupId, PermissionAction.VIEW),
            RepositoryPermission(repository.id, editGroupId, PermissionAction.EDIT),
            RepositoryPermission(repository.id, editGroupId, PermissionAction.MANAGE)
        )
        val realEvaluator = RepositoryPermissionEvaluator(repositoryService, securityService, GroupEvaluator(securityService))
        val readOnly = scopedAuthContext(listOf(grantedGroup), listOf("git:read"))
        assertTrue(realEvaluator.isAllowed(readOnly, repository, PermissionAction.VIEW))
        assertFalse(realEvaluator.isAllowed(readOnly, repository, PermissionAction.EDIT))
        assertFalse(realEvaluator.isAllowed(readOnly, repository, PermissionAction.MANAGE))
        val writer = scopedAuthContext(listOf(grantedGroup), listOf("git:write"))
        assertTrue(realEvaluator.isAllowed(writer, repository, PermissionAction.EDIT))
    }

    @Test
    fun `admin token with git write scope can push`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(listOf(adminGroup), listOf("git:write"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `admin token with git read scope can view`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(listOf(adminGroup), listOf("git:read"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.VIEW))
    }

    @Test
    fun `admin token with only git read scope cannot push`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(listOf(adminGroup), listOf("git:read"))
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `editor token with git write scope can push`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(listOf(editorGroup), listOf("git:write"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `editor token with git manage scope cannot manage`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(listOf(editorGroup), listOf("git:manage"))
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.MANAGE))
    }

    @Test
    fun `admin token with git manage scope can manage`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(listOf(adminGroup), listOf("git:manage"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.MANAGE))
    }

    @Test
    fun `token with no group and git write scope cannot push`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("git:write"))
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `unrestricted token for admin can push without git scope`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        every { groupEvaluator.hasAdminGroup(any()) } returns true
        val auth = scopedAuthContext(listOf(adminGroup), null)
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `unrestricted editors and managers can execute a repository but not system level`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val managerGroup = Group(id = UUID.random(), name = "managers", description = "managers", type = GroupType.PRINCIPAL)
        for (group in listOf(editorGroup, managerGroup)) {
            val auth = scopedAuthContext(listOf(group), null)
            assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EXECUTE))
            assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.MANAGE))
            assertFailsWith<SecurityException> { evaluator.verifyAllowed(auth, PermissionAction.EXECUTE) }
        }
        val other = Group(id = UUID.random(), name = "contributors", description = "contributors", type = GroupType.PRINCIPAL)
        assertFalse(evaluator.isAllowed(scopedAuthContext(listOf(other), null), repository, PermissionAction.EXECUTE))
    }

    @Test
    fun `scoped editor token still needs ci execute to run pipelines`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        assertFalse(evaluator.isAllowed(scopedAuthContext(listOf(editorGroup), listOf("git:write")), repository, PermissionAction.EXECUTE))
    }

    @Test
    fun `ci manage scope grants manage without any group`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:manage"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.MANAGE))
    }

    @Test
    fun `ci execute scope grants execute without any group`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:execute"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EXECUTE))
    }

    @Test
    fun `ci edit scope grants edit without any group`() = runTest {
        // `ci:edit` is the explicit "CI is allowed to write to the repository"
        // scope — for things like updating source refs back to a repo after a
        // pipeline run. It bypasses the group requirement that git:write has.
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:edit"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `ci execute scope alone does not grant repository edit`() = runTest {
        // EDIT means "write to the repository" (git push, source-ref mutation).
        // ci:execute is for pipeline execution — it does NOT imply repo write.
        // A CI token that needs to push must carry git:write explicitly.
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:execute"))
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `ci read scope grants view without any group`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:read"))
        assertTrue(evaluator.isAllowed(auth, repository, PermissionAction.VIEW))
    }

    @Test
    fun `ci manage scope can use verifyAllowed without repo or group`() {
        val auth = scopedAuthContext(emptyList(), listOf("ci:manage"))
        evaluator.verifyAllowed(auth, PermissionAction.MANAGE)
    }

    private fun loginAuthContext(groups: List<Group>): AuthenticationContext {
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = UUID.random()), groups)
        return auth
    }

    @Test
    fun `logged in admin and sa sessions pass system level manage for agent registration`() {
        val realEvaluator = RepositoryPermissionEvaluator(repositoryService, securityService, GroupEvaluator(securityService))
        val saGroup = Group(id = UUID.random(), name = "sa", description = "service accounts", type = GroupType.PRINCIPAL)
        realEvaluator.verifyAllowed(loginAuthContext(listOf(adminGroup)), PermissionAction.MANAGE)
        realEvaluator.verifyAllowed(loginAuthContext(listOf(saGroup)), PermissionAction.MANAGE)
        realEvaluator.verifyAllowed(scopedAuthContext(listOf(saGroup), listOf("security:manage")), PermissionAction.MANAGE)
        realEvaluator.verifyAllowed(scopedAuthContext(listOf(saGroup), null), PermissionAction.MANAGE)
    }

    @Test
    fun `admin token with only git manage scope cannot pass system level manage`() {
        val realEvaluator = RepositoryPermissionEvaluator(repositoryService, securityService, GroupEvaluator(securityService))
        val auth = scopedAuthContext(listOf(adminGroup), listOf("git:manage"))
        assertFailsWith<SecurityException> { realEvaluator.verifyAllowed(auth, PermissionAction.MANAGE) }
    }

    @Test
    fun `admin token with git manage and security manage passes system level manage`() {
        val realEvaluator = RepositoryPermissionEvaluator(repositoryService, securityService, GroupEvaluator(securityService))
        val auth = scopedAuthContext(listOf(adminGroup), listOf("git:manage", "security:manage"))
        realEvaluator.verifyAllowed(auth, PermissionAction.MANAGE)
    }

    @Test
    fun `admin editor token with git write keeps system level edit through the editors group`() {
        val realEvaluator = RepositoryPermissionEvaluator(repositoryService, securityService, GroupEvaluator(securityService))
        val auth = scopedAuthContext(listOf(adminGroup, editorGroup), listOf("git:write"))
        realEvaluator.verifyAllowed(auth, PermissionAction.EDIT)
    }

    @Test
    fun `token with only ci read scope cannot edit`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:read"))
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.EDIT))
    }

    @Test
    fun `token with only ci execute scope cannot manage`() = runTest {
        val repository = repo(visibility = Visibility.PRIVATE)
        coEvery { repositoryService.getPermissions(repository) } returns emptyList()
        val auth = scopedAuthContext(emptyList(), listOf("ci:execute"))
        assertFalse(evaluator.isAllowed(auth, repository, PermissionAction.MANAGE))
    }

    // ── appended coverage: role-based access arms ────────────────────────

    @Test
    fun `verifyAllowed without an entity throws when role access is missing`() = runTest {
        coEvery { groupEvaluator.hasSaGroup(any()) } returns false
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false
        val auth = scopedAuthContext(emptyList(), null) // null scopes -> admin check only
        try { evaluator.verifyAllowed(auth, PermissionAction.MANAGE); kotlin.test.fail() }
        catch (_: SecurityException) { /* expected */ }
    }

    @Test
    fun `sa group short-circuits role access`() = runTest {
        coEvery { groupEvaluator.hasSaGroup(any()) } returns true
        val auth = scopedAuthContext(emptyList(), emptyList())
        evaluator.verifyAllowed(auth, PermissionAction.MANAGE) // must not throw
    }

    @Test
    fun `ci scopes grant their matching action without groups`() = runTest {
        coEvery { groupEvaluator.hasSaGroup(any()) } returns false
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false
        evaluator.verifyAllowed(scopedAuthContext(emptyList(), listOf("ci:read")), PermissionAction.VIEW)
        evaluator.verifyAllowed(scopedAuthContext(emptyList(), listOf("ci:edit")), PermissionAction.EDIT)
        evaluator.verifyAllowed(scopedAuthContext(emptyList(), listOf("ci:execute")), PermissionAction.EXECUTE)
        evaluator.verifyAllowed(scopedAuthContext(emptyList(), listOf("ci:manage")), PermissionAction.MANAGE)
    }

    @Test
    fun `git scopes require editors or managers and never grant MANAGE`() = runTest {
        coEvery { groupEvaluator.hasSaGroup(any()) } returns false
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false

        // Scope without a role group -> denied.
        try { evaluator.verifyAllowed(scopedAuthContext(emptyList(), listOf("git:write")), PermissionAction.EDIT); kotlin.test.fail() }
        catch (_: SecurityException) {}

        // Scope + editors group -> allowed for EDIT/VIEW.
        evaluator.verifyAllowed(scopedAuthContext(listOf(editorGroup), listOf("git:write")), PermissionAction.EDIT)
        evaluator.verifyAllowed(scopedAuthContext(listOf(editorGroup), listOf("git:read")), PermissionAction.VIEW)

        // MANAGE via git scope stays reserved for admins even with the editors group.
        try { evaluator.verifyAllowed(scopedAuthContext(listOf(editorGroup), listOf("git:manage")), PermissionAction.MANAGE); kotlin.test.fail() }
        catch (_: SecurityException) {}

        // Unmapped action -> denied outright.
        try { evaluator.verifyAllowed(scopedAuthContext(listOf(editorGroup), listOf("git:write")), PermissionAction.EXECUTE); kotlin.test.fail() }
        catch (_: SecurityException) {}
    }
}
