package bosca.security.service

import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissibleEntity
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.security.model.Principal
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PermissionEvaluatorTest {

    data class TestEntity(
        override val id: UUID = UUID.random(),
        override val public: Boolean = false,
        override val publicContent: Boolean = false,
        override val publicList: Boolean = false,
        override val publicSupplementary: Boolean = false,
        override val isPublished: Boolean = true,
        override val isAdvertised: Boolean = false,
        override val isDeleted: Boolean = false
    ) : PermissibleEntity<UUID>

    data class TestPermission(
        override val entityId: UUID,
        override val groupId: UUID,
        override val action: PermissionAction
    ) : EntityPermission

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = GroupEvaluator(securityService)
    private val permissionService = mockk<PermissionService<TestEntity, UUID>>()

    private val evaluator = object : PermissionEvaluator<TestEntity, UUID>() {
        override val service = permissionService
        override val securityService = this@PermissionEvaluatorTest.securityService
        override val groupEvaluator = this@PermissionEvaluatorTest.groupEvaluator
    }

    private fun authContext(vararg groupNames: String): AuthenticationContext {
        val principal = Principal(id = UUID.random())
        val groups = groupNames.map { Group(id = UUID.random(), name = it, description = "", type = GroupType.SYSTEM) }
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    @BeforeTest
    fun stubNoParent() {
        coEvery { permissionService.isParentAllowed(any(), any(), any()) } returns false
    }

    // --- isAllowed: deleted entity ---

    @Test
    fun `isAllowed returns false for deleted entity`() = runTest {
        val entity = TestEntity(isDeleted = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    // --- isAllowed: public VIEW on published entity ---

    @Test
    fun `isAllowed returns true for public VIEW on published entity`() = runTest {
        val entity = TestEntity(public = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed returns true for public VIEW on advertised entity`() = runTest {
        val entity = TestEntity(public = true, isAdvertised = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed returns false for public VIEW on unpublished entity`() = runTest {
        val entity = TestEntity(public = true, isPublished = false, isAdvertised = false)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    // --- isAllowed: public LIST ---

    @Test
    fun `isAllowed returns true for publicList LIST on published entity`() = runTest {
        val entity = TestEntity(publicList = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(null, entity, PermissionAction.LIST))
    }

    // --- isAllowed: admin can access anything ---

    @Test
    fun `isAllowed returns true for administrators`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(authContext("administrators"), entity, PermissionAction.VIEW))
    }

    // --- isAllowed: sa can access anything ---

    @Test
    fun `isAllowed returns true for sa group`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(authContext("sa"), entity, PermissionAction.VIEW))
    }

    // --- isAllowed: editor EDIT access ---

    @Test
    fun `isAllowed returns true for editor with EDIT action`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(authContext("editors"), entity, PermissionAction.EDIT))
    }

    // --- isAllowed: regular user without permission ---

    @Test
    fun `isAllowed returns false for regular user without permission`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(authContext("users"), entity, PermissionAction.EDIT))
    }

    // --- isAllowed: permission-based access ---

    @Test
    fun `isAllowed returns true when user has matching permission via group`() = runTest {
        val groupId = UUID.random()
        val principal = Principal(id = UUID.random())
        val groups = listOf(Group(id = groupId, name = "team-a", description = "", type = GroupType.SYSTEM))
        val auth = ImpersonatedAuthenticationContext(principal, groups)
        val entity = TestEntity()
        val permission = TestPermission(entityId = entity.id, groupId = groupId, action = PermissionAction.VIEW)
        coEvery { permissionService.getPermissions(entity) } returns listOf(permission)

        assertTrue(evaluator.isAllowed(auth, entity, PermissionAction.VIEW))
    }

    // --- isAllowed: MANAGE permission grants EDIT ---

    @Test
    fun `isAllowed returns true when user has MANAGE permission and action is EDIT`() = runTest {
        val groupId = UUID.random()
        val principal = Principal(id = UUID.random())
        val groups = listOf(Group(id = groupId, name = "team-a", description = "", type = GroupType.SYSTEM))
        val auth = ImpersonatedAuthenticationContext(principal, groups)
        val entity = TestEntity()
        val permission = TestPermission(entityId = entity.id, groupId = groupId, action = PermissionAction.MANAGE)
        coEvery { permissionService.getPermissions(entity) } returns listOf(permission)

        assertTrue(evaluator.isAllowed(auth, entity, PermissionAction.EDIT))
    }

    // --- isAllowed: MANAGE action requires explicit permission ---

    @Test
    fun `isAllowed returns false for editor with MANAGE action`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(authContext("editors"), entity, PermissionAction.MANAGE))
    }

    // --- isAllowed: deleted entity accessible to sa/admin ---

    @Test
    fun `isAllowed allows sa to view deleted entity`() = runTest {
        val entity = TestEntity(isDeleted = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(authContext("sa"), entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed allows administrators to view deleted entity`() = runTest {
        val entity = TestEntity(isDeleted = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(authContext("administrators"), entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed denies regular user on deleted entity even when public`() = runTest {
        val entity = TestEntity(isDeleted = true, public = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(authContext("users"), entity, PermissionAction.VIEW))
    }

    // --- verifyAllowed ---

    @Test
    fun `verifyAllowed throws when not allowed`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFailsWith<SecurityException> {
            evaluator.verifyAllowed(null, entity, PermissionAction.VIEW)
        }
    }

    @Test
    fun `verifyAllowed succeeds when allowed`() = runTest {
        val entity = TestEntity(public = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        evaluator.verifyAllowed(null, entity, PermissionAction.VIEW)
    }

    // --- isSupplementaryAllowed ---

    @Test
    fun `isSupplementaryAllowed returns true for publicSupplementary VIEW on published entity`() = runTest {
        val entity = TestEntity(publicSupplementary = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isSupplementaryAllowed(null, entity, PermissionAction.VIEW))
    }

    @Test
    fun `isSupplementaryAllowed returns false for non-public entity`() = runTest {
        val entity = TestEntity(publicSupplementary = false)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isSupplementaryAllowed(null, entity, PermissionAction.VIEW))
    }

    // --- isContentAllowed ---

    @Test
    fun `isContentAllowed returns true for publicContent VIEW on published entity`() = runTest {
        val entity = TestEntity(publicContent = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isContentAllowed(null, entity, PermissionAction.VIEW))
    }

    @Test
    fun `isContentAllowed returns false for non-public content`() = runTest {
        val entity = TestEntity(publicContent = false)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isContentAllowed(null, entity, PermissionAction.VIEW))
    }

    // --- verifyContentAllowed ---

    @Test
    fun `verifyContentAllowed throws when not allowed`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFailsWith<SecurityException> {
            evaluator.verifyContentAllowed(null, entity, PermissionAction.VIEW)
        }
    }

    @Test
    fun `verifyContentAllowed succeeds when public content is published`() = runTest {
        val entity = TestEntity(publicContent = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        evaluator.verifyContentAllowed(null, entity, PermissionAction.VIEW)
    }

    // --- verifySupplementaryAllowed ---

    @Test
    fun `verifySupplementaryAllowed throws when not allowed`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFailsWith<SecurityException> {
            evaluator.verifySupplementaryAllowed(null, entity, PermissionAction.VIEW)
        }
    }

    @Test
    fun `verifySupplementaryAllowed succeeds when public supplementary is published`() = runTest {
        val entity = TestEntity(publicSupplementary = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        evaluator.verifySupplementaryAllowed(null, entity, PermissionAction.VIEW)
    }

    // --- isAllowed: EXECUTE and IMPERSONATE blocked for editors ---

    @Test
    fun `isAllowed returns false for editor with EXECUTE action`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(authContext("editors"), entity, PermissionAction.EXECUTE))
    }

    @Test
    fun `isAllowed returns false for editor with IMPERSONATE action`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(authContext("editors"), entity, PermissionAction.IMPERSONATE))
    }

    @Test
    fun `isAllowed returns true for admin with MANAGE action`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertTrue(evaluator.isAllowed(authContext("administrators"), entity, PermissionAction.MANAGE))
    }

    // --- batch isAllowed ---

    @Test
    fun `batch isAllowed returns correct results for mixed entities`() = runTest {
        val publicEntity = TestEntity(public = true, isPublished = true)
        val privateEntity = TestEntity()
        val entities = listOf(publicEntity, privateEntity)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val results = evaluator.isAllowed(null, entities, PermissionAction.VIEW)
        assertTrue(results[0])
        assertFalse(results[1])
    }

    @Test
    fun `batch isAllowed grants admin access to all entities`() = runTest {
        val entity1 = TestEntity()
        val entity2 = TestEntity()
        val entities = listOf(entity1, entity2)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val results = evaluator.isAllowed(authContext("administrators"), entities, PermissionAction.VIEW)
        assertTrue(results[0])
        assertTrue(results[1])
    }

    // --- filterAllowed ---

    @Test
    fun `filterAllowed returns only allowed entities`() = runTest {
        val publicEntity = TestEntity(public = true, isPublished = true)
        val privateEntity = TestEntity()
        val entities = listOf(publicEntity, privateEntity)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val filtered = evaluator.filterAllowed(null, entities, PermissionAction.VIEW)
        assertEquals(1, filtered.size)
        assertEquals(publicEntity, filtered[0])
    }

    @Test
    fun `filterAllowed returns all entities for admin`() = runTest {
        val entity1 = TestEntity()
        val entity2 = TestEntity()
        val entities = listOf(entity1, entity2)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val filtered = evaluator.filterAllowed(authContext("administrators"), entities, PermissionAction.VIEW)
        assertEquals(2, filtered.size)
    }

    @Test
    fun `filterAllowed returns empty list when none allowed`() = runTest {
        val entity1 = TestEntity()
        val entity2 = TestEntity()
        val entities = listOf(entity1, entity2)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val filtered = evaluator.filterAllowed(null, entities, PermissionAction.VIEW)
        assertTrue(filtered.isEmpty())
    }

    // --- batch isAllowed: deleted entities with SA ---

    @Test
    fun `batch isAllowed allows SA to access deleted entities`() = runTest {
        val deletedEntity = TestEntity(isDeleted = true)
        val entities = listOf(deletedEntity)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val results = evaluator.isAllowed(authContext("sa"), entities, PermissionAction.VIEW)
        assertTrue(results[0])
    }

    @Test
    fun `batch isAllowed allows administrators to access deleted entities`() = runTest {
        val deletedEntity = TestEntity(isDeleted = true)
        val entities = listOf(deletedEntity)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit

        val results = evaluator.isAllowed(authContext("administrators"), entities, PermissionAction.VIEW)
        assertTrue(results[0])
    }

    @Test
    fun `multi identity authorization loads entity permissions once`() = runTest {
        val entity = TestEntity()
        val allowedGroupId = UUID.random()
        val allowed = ImpersonatedAuthenticationContext(
            Principal(id = UUID.random()),
            listOf(Group(allowedGroupId, "allowed", "Allowed", GroupType.SYSTEM)),
        )
        val denied = authContext("denied")
        coEvery { permissionService.getPermissions(entity) } returns listOf(
            TestPermission(entity.id, allowedGroupId, PermissionAction.VIEW),
        )

        assertEquals(
            listOf(true, false),
            evaluator.isAllowed(listOf(allowed, denied), entity, PermissionAction.VIEW),
        )

        coVerify(exactly = 1) { permissionService.getPermissions(entity) }
    }

    // --- null authentication ---

    @Test
    fun `isAllowed returns false for null auth on non-public entity`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()

        assertFalse(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    // --- parent cascade ---

    @Test
    fun `isAllowed delegates to parent when entity-level checks fail`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()
        coEvery {
            permissionService.isParentAllowed(any(), entity, PermissionAction.VIEW)
        } returns true

        assertTrue(evaluator.isAllowed(authContext("users"), entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed denies when parent also denies`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()
        coEvery {
            permissionService.isParentAllowed(any(), entity, PermissionAction.VIEW)
        } returns false

        assertFalse(evaluator.isAllowed(authContext("users"), entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed does not call parent when entity grants access directly`() = runTest {
        val groupId = UUID.random()
        val principal = Principal(id = UUID.random())
        val groups = listOf(Group(id = groupId, name = "team-a", description = "", type = GroupType.SYSTEM))
        val auth = ImpersonatedAuthenticationContext(principal, groups)
        val entity = TestEntity()
        val permission = TestPermission(entityId = entity.id, groupId = groupId, action = PermissionAction.VIEW)
        coEvery { permissionService.getPermissions(entity) } returns listOf(permission)
        coEvery {
            permissionService.isParentAllowed(any(), entity, PermissionAction.VIEW)
        } returns false

        assertTrue(evaluator.isAllowed(auth, entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed downstream override grants access via public flag even if parent denies`() = runTest {
        val entity = TestEntity(public = true, isPublished = true)
        coEvery { permissionService.getPermissions(entity) } returns emptyList()
        coEvery {
            permissionService.isParentAllowed(any(), entity, PermissionAction.VIEW)
        } returns false

        assertTrue(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    @Test
    fun `isAllowed cascades for unauthenticated requests`() = runTest {
        val entity = TestEntity()
        coEvery { permissionService.getPermissions(entity) } returns emptyList()
        coEvery {
            permissionService.isParentAllowed(null, entity, PermissionAction.VIEW)
        } returns true

        assertTrue(evaluator.isAllowed(null, entity, PermissionAction.VIEW))
    }

    @Test
    fun `filterAllowed includes entities allowed via parent cascade`() = runTest {
        val cascadedEntity = TestEntity()
        val deniedEntity = TestEntity()
        val entities = listOf(cascadedEntity, deniedEntity)

        coEvery { permissionService.addPermissionsToBatch(any()) } returns Unit
        coEvery {
            permissionService.isParentAllowed(any(), cascadedEntity, PermissionAction.VIEW)
        } returns true
        coEvery {
            permissionService.isParentAllowed(any(), deniedEntity, PermissionAction.VIEW)
        } returns false

        val filtered = evaluator.filterAllowed(authContext("users"), entities, PermissionAction.VIEW)
        assertEquals(1, filtered.size)
        assertEquals(cascadedEntity, filtered[0])
    }
}
