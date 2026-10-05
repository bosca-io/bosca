package bosca.git.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RepositoryConfigurationTest {

    @Test
    fun `defaults include merge commit, squash, and rebase strategies`() {
        val config = RepositoryConfiguration()
        assertEquals(
            listOf(MergeStrategy.MERGE_COMMIT, MergeStrategy.SQUASH, MergeStrategy.REBASE),
            config.mergeStrategies
        )
    }

    @Test
    fun `defaults disable squash-by-default and auto-delete-branch`() {
        val config = RepositoryConfiguration()
        assertFalse(config.squashByDefault)
        assertFalse(config.deleteBranchOnMerge)
        assertFalse(config.requireSignedCommits)
    }

    @Test
    fun `custom configuration preserves all fields`() {
        val config = RepositoryConfiguration(
            mergeStrategies = listOf(MergeStrategy.FAST_FORWARD),
            squashByDefault = true,
            deleteBranchOnMerge = true,
            requireSignedCommits = true
        )
        assertEquals(listOf(MergeStrategy.FAST_FORWARD), config.mergeStrategies)
        assertEquals(true, config.squashByDefault)
        assertEquals(true, config.deleteBranchOnMerge)
        assertEquals(true, config.requireSignedCommits)
    }
}
