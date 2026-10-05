package bosca.git.configuration

import bosca.git.dfs.DfsBlockCacheInitializer
import bosca.git.graphql.GitRepositoryConfigurationController
import bosca.git.model.MergeStrategy
import bosca.git.model.RepositoryConfiguration
import io.mockk.every
import io.mockk.mockk
import bosca.sharedqueue.jobs.JobQueueFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the DI provider factory methods in [Configuration], the JGit block
 * cache initializer, and the repository-configuration type controller.
 */
class ConfigurationProvidersTest {

    private val config = Configuration()

    @Test
    fun `provider factories construct their services`() {
        assertEquals("git", config.migration().schema)
        assertNotNull(config.contentValidatorRegistry())
        assertNotNull(config.dfsRepoManager(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        assertNotNull(config.repositoryPermissionEvaluator(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        assertNotNull(config.preReceiveHook(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        assertNotNull(
            config.refUpdateNotifier(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
        )
        assertNotNull(config.postReceiveHook(mockk(relaxed = true)))

        val factory = mockk<JobQueueFactory>()
        val queue = mockk<bosca.sharedqueue.jobs.JobQueue>(relaxed = true)
        every { factory.create(JobQueueNames.gitJobQueue) } returns queue
        assertEquals(queue, config.gitJobQueue(factory))
    }

    @Test
    fun `block cache initializer reconfigures without error and is idempotent`() {
        DfsBlockCacheInitializer.initialize()
        DfsBlockCacheInitializer.initialize()
    }

    @Test
    fun `repository configuration controller passes fields through`() {
        val controller = GitRepositoryConfigurationController()
        val cfg = RepositoryConfiguration(
            mergeStrategies = listOf(MergeStrategy.SQUASH),
            squashByDefault = true,
            deleteBranchOnMerge = true,
            requireSignedCommits = true,
        )
        assertEquals(listOf(MergeStrategy.SQUASH), controller.mergeStrategies(cfg))
        assertTrue(controller.squashByDefault(cfg))
        assertTrue(controller.deleteBranchOnMerge(cfg))
        assertTrue(controller.requireSignedCommits(cfg))
    }
}
