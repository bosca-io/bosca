package bosca.git.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryTest {

    private fun repository(
        visibility: Visibility = Visibility.PRIVATE,
        archived: Boolean = false,
        deleted: Boolean = false
    ) = Repository(
        id = UUID.random(),
        slug = "test-repo",
        name = "Test Repository",
        ownerId = UUID.random(),
        visibility = visibility,
        archived = archived,
        deleted = deleted
    )

    @Test
    fun `public visibility maps to public PermissibleEntity flags`() {
        val repo = repository(visibility = Visibility.PUBLIC)
        assertTrue(repo.public)
        assertTrue(repo.publicContent)
        assertTrue(repo.publicList)
    }

    @Test
    fun `internal visibility sets publicList but not public or publicContent`() {
        val repo = repository(visibility = Visibility.INTERNAL)
        assertFalse(repo.public)
        assertFalse(repo.publicContent)
        assertTrue(repo.publicList)
    }

    @Test
    fun `private visibility sets all public flags to false`() {
        val repo = repository(visibility = Visibility.PRIVATE)
        assertFalse(repo.public)
        assertFalse(repo.publicContent)
        assertFalse(repo.publicList)
    }

    @Test
    fun `archived repository is not published`() {
        val repo = repository(archived = true)
        assertFalse(repo.isPublished)
    }

    @Test
    fun `deleted repository is flagged as deleted and not published`() {
        val repo = repository(deleted = true)
        assertTrue(repo.isDeleted)
        assertFalse(repo.isPublished)
    }

    @Test
    fun `active repository is published and not deleted`() {
        val repo = repository()
        assertTrue(repo.isPublished)
        assertFalse(repo.isDeleted)
    }

    @Test
    fun `default branch is main`() {
        val repo = repository()
        assertEquals("main", repo.defaultBranch)
    }

    @Test
    fun `default configuration includes standard merge strategies`() {
        val repo = repository()
        assertEquals(
            listOf(MergeStrategy.MERGE_COMMIT, MergeStrategy.SQUASH, MergeStrategy.REBASE),
            repo.configuration.mergeStrategies
        )
    }
}
