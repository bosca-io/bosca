package bosca.git.model

import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryPermissionTest {

    @Test
    fun `entityId delegates to repositoryId`() {
        val repoId = UUID.random()
        val perm = RepositoryPermission(
            repositoryId = repoId,
            groupId = UUID.random(),
            action = PermissionAction.VIEW
        )
        assertEquals(repoId, perm.entityId)
    }

    @Test
    fun `equality is based on repositoryId, groupId, and action`() {
        val repoId = UUID.random()
        val groupId = UUID.random()
        val a = RepositoryPermission(repoId, groupId, PermissionAction.EDIT)
        val b = RepositoryPermission(repoId, groupId, PermissionAction.EDIT)
        assertEquals(a, b)
    }
}
