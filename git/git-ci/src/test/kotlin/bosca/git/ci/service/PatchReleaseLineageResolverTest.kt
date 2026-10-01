package bosca.git.ci.service

import bosca.git.model.CommitInfo
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.model.TagInfo
import bosca.git.service.RepositoryBrowseService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatchReleaseLineageResolverTest {

    private val repositoryId = UUID.random()
    private val repositoryBrowse = mockk<RepositoryBrowseService>()
    private val resolver = PatchReleaseLineageResolver(repositoryBrowse)

    @Test
    fun `a commit marker supports any higher patch in the same release line`() = runTest {
        coEvery { repositoryBrowse.listTags(repositoryId) } returns listOf(
            TagInfo(name = "1.2.10", sha = "current"),
            TagInfo(name = "1.2.7", sha = "intermediate"),
            TagInfo(name = "1.2.0", sha = "base"),
        )
        coEvery {
            repositoryBrowse.listCommits(repositoryId, "current", null, 256, 0)
        } returns listOf(
            commit("current", "Fix packaging\n\nPatch for [1.2.0]"),
            commit("intermediate", "Release 1.2.7"),
            commit("base", "Release 1.2.0"),
        )

        val lineage = resolver.resolve(run("1.2.10", "current"))

        assertEquals("1.2.10", lineage?.currentVersion)
        assertEquals("1.2.0", lineage?.baseVersion)
        assertEquals("refs/tags/1.2.0", lineage?.rewrite("refs/tags/1.2.10"))
        assertEquals(
            "io.bosca:core-content:1.2.0",
            lineage?.rewrite("io.bosca:core-content:1.2.10"),
        )
    }

    @Test
    fun `an annotated tag marker supports any higher patch in the same release line`() = runTest {
        coEvery { repositoryBrowse.listTags(repositoryId) } returns listOf(
            TagInfo(
                name = "1.2.10",
                sha = "tag-object",
                targetSha = "current",
                message = "Release 1.2.10\n\nPatch for [1.2.0]",
                isAnnotated = true,
            ),
            TagInfo(name = "1.2.7", sha = "intermediate"),
            TagInfo(name = "1.2.0", sha = "base"),
        )
        coEvery {
            repositoryBrowse.listCommits(repositoryId, "current", null, 256, 0)
        } returns listOf(
            commit("current", "Release 1.2.10"),
            commit("intermediate", "Release 1.2.7"),
            commit("base", "Release 1.2.0"),
        )

        val lineage = resolver.resolve(run("1.2.10", "current"))

        assertEquals("1.2.0", lineage?.baseVersion)
        assertEquals("refs/tags/1.2.0", lineage?.rewrite("refs/tags/1.2.10"))
    }

    @Test
    fun `a structured trailer supports v-prefixed patch tags`() = runTest {
        coEvery { repositoryBrowse.listTags(repositoryId) } returns listOf(
            TagInfo(
                name = "v1.2.10",
                sha = "tag-object",
                targetSha = "current",
                message = "Release v1.2.10\n\nBosca-Patch-For: v1.2.0",
                isAnnotated = true,
            ),
            TagInfo(name = "v1.2.7", sha = "intermediate"),
            TagInfo(name = "v1.2.0", sha = "base"),
        )
        coEvery {
            repositoryBrowse.listCommits(repositoryId, "current", null, 256, 0)
        } returns listOf(
            commit("current", "Release v1.2.10"),
            commit("intermediate", "Release v1.2.7"),
            commit("base", "Release v1.2.0"),
        )

        val lineage = resolver.resolve(run("v1.2.10", "current"))

        assertEquals("1.2.0", lineage?.baseVersion)
        assertEquals("refs/tags/v1.2.0", lineage?.rewrite("refs/tags/v1.2.10"))
    }

    @Test
    fun `a marker at or before the most recent lower patch does not leak into a future release`() = runTest {
        coEvery { repositoryBrowse.listTags(repositoryId) } returns listOf(
            TagInfo(name = "1.2.10", sha = "current"),
            TagInfo(name = "1.2.7", sha = "intermediate"),
            TagInfo(name = "1.2.0", sha = "base"),
        )
        coEvery {
            repositoryBrowse.listCommits(repositoryId, "current", null, 256, 0)
        } returns listOf(
            commit("current", "Prepare another release"),
            commit("intermediate", "Release 1.2.7\n\nPatch for [1.2.0]"),
            commit("base", "Release 1.2.0"),
        )

        assertNull(resolver.resolve(run("1.2.10", "current")))
    }

    @Test
    fun `a marker cannot cross a major or minor release line`() = runTest {
        coEvery { repositoryBrowse.listTags(repositoryId) } returns listOf(
            TagInfo(name = "1.2.10", sha = "current", message = "Patch for [1.1.9]"),
            TagInfo(name = "1.2.7", sha = "intermediate"),
            TagInfo(name = "1.1.9", sha = "base"),
        )
        coEvery {
            repositoryBrowse.listCommits(repositoryId, "current", null, 256, 0)
        } returns listOf(
            commit("current", "Prepare release"),
            commit("intermediate", "Release 1.2.7"),
            commit("base", "Release 1.1.9"),
        )

        assertNull(resolver.resolve(run("1.2.10", "current")))
    }

    private fun run(version: String, commitSha: String) = PipelineRun(
        id = UUID.random(),
        pipelineId = UUID.random(),
        repositoryId = repositoryId,
        commitSha = commitSha,
        ref = "refs/tags/$version",
        triggerType = PipelineTriggerType.TAG,
        status = PipelineRunStatus.QUEUED,
        number = 1,
    )

    private fun commit(sha: String, message: String) = CommitInfo(
        sha = sha,
        message = message,
        authorName = "Test",
        authorEmail = "test@bosca.io",
        authorDate = "2026-07-28T00:00:00Z",
        committerName = "Test",
        committerEmail = "test@bosca.io",
        committerDate = "2026-07-28T00:00:00Z",
    )
}
