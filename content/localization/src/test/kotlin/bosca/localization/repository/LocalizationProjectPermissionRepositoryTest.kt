@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.localization.LocalizationTestFixture
import bosca.localization.model.LocalizationProject
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Integration tests for `localization.project_permissions`. Confirms that the
 * `on conflict do nothing` insert makes permission grants idempotent, deletes
 * target only the exact (project, group, action) triple, and that the
 * [LocalizationProjectPermissionRepository.getByProjectIds] batch lookup groups
 * rows correctly for the permission cache.
 */
class LocalizationProjectPermissionRepositoryTest {

    private lateinit var permissions: LocalizationProjectPermissionRepository
    private lateinit var projectId: UUID
    private lateinit var secondProjectId: UUID
    private val groupId = UUID.random()
    private val otherGroupId = UUID.random()

    @BeforeTest
    fun setup() {
        LocalizationTestFixture.ensureSchema()
        LocalizationTestFixture.reset()
        LocalizationTestFixture.seedLanguage("en")
        permissions = LocalizationProjectPermissionRepositoryImpl()
        LocalizationTestFixture.withDb {
            val projects = LocalizationProjectRepositoryImpl()
            projectId = projects.add(LocalizationProject(name = "P1", sourceLanguage = "en")).id
            secondProjectId = projects.add(LocalizationProject(name = "P2", sourceLanguage = "en")).id
        }
    }

    @AfterTest
    fun tearDown() {
        LocalizationTestFixture.reset()
    }

    @Test
    fun `addPermission persists a grant and getByProjectId returns it`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        val rows = permissions.getByProjectId(projectId)
        assertEquals(1, rows.size)
        assertEquals(groupId, rows.first().groupId)
        assertEquals(PermissionAction.EDIT, rows.first().action)
        assertEquals(projectId, rows.first().projectId)
    }

    @Test
    fun `addPermission is idempotent thanks to on-conflict-do-nothing`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        assertEquals(1, permissions.getByProjectId(projectId).size, "duplicate grant must not insert a second row")
    }

    @Test
    fun `same group can hold multiple actions on the same project`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.VIEW)
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        permissions.addPermission(projectId, groupId, PermissionAction.MANAGE)
        val actions = permissions.getByProjectId(projectId).map { it.action }.toSet()
        assertEquals(setOf(PermissionAction.VIEW, PermissionAction.EDIT, PermissionAction.MANAGE), actions)
    }

    @Test
    fun `deletePermission removes exactly one (project, group, action) row`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.VIEW)
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        permissions.deletePermission(projectId, groupId, PermissionAction.VIEW)

        val remaining = permissions.getByProjectId(projectId)
        assertEquals(1, remaining.size)
        assertEquals(PermissionAction.EDIT, remaining.first().action)
    }

    @Test
    fun `deletePermission does nothing when the row is absent`() = LocalizationTestFixture.withDb {
        permissions.deletePermission(projectId, groupId, PermissionAction.VIEW)
        assertTrue(permissions.getByProjectId(projectId).isEmpty())
    }

    @Test
    fun `deleteByProjectId removes every row for a project but spares siblings`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        permissions.addPermission(projectId, otherGroupId, PermissionAction.VIEW)
        permissions.addPermission(secondProjectId, groupId, PermissionAction.MANAGE)

        permissions.deleteByProjectId(projectId)

        assertTrue(permissions.getByProjectId(projectId).isEmpty())
        assertEquals(1, permissions.getByProjectId(secondProjectId).size)
    }

    @Test
    fun `getByProjectIds batch-loads grouped by project`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        permissions.addPermission(secondProjectId, otherGroupId, PermissionAction.VIEW)

        val rows = permissions.getByProjectIds(listOf(projectId, secondProjectId))
        assertEquals(2, rows.size)
        val byProject = rows.groupBy { it.projectId }
        assertEquals(PermissionAction.EDIT, byProject[projectId]!!.single().action)
        assertEquals(PermissionAction.VIEW, byProject[secondProjectId]!!.single().action)
    }

    @Test
    fun `deleting the parent project cascades to its permissions`() = LocalizationTestFixture.withDb {
        permissions.addPermission(projectId, groupId, PermissionAction.EDIT)
        LocalizationProjectRepositoryImpl().deleteById(projectId)
        assertTrue(permissions.getByProjectId(projectId).isEmpty())
    }
}
