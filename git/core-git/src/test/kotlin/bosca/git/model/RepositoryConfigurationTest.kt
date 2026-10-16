package bosca.git.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `defaults enable branch deletion while leaving squash and signed commits disabled`() {
        val config = RepositoryConfiguration()
        assertFalse(config.squashByDefault)
        assertTrue(config.deleteBranchOnMerge)
        assertFalse(config.requireSignedCommits)
    }

    @Test
    fun `stored configuration defaults to branch deletion and preserves an explicit opt out`() {
        assertTrue(Json.decodeFromString(RepositoryConfiguration.serializer(), "{}").deleteBranchOnMerge)
        val optedOut = RepositoryConfiguration(deleteBranchOnMerge = false)
        val stored = Json.encodeToString(RepositoryConfiguration.serializer(), optedOut)
        assertFalse(Json.decodeFromString(RepositoryConfiguration.serializer(), stored).deleteBranchOnMerge)
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
