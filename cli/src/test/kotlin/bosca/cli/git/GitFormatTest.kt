package bosca.cli.git

import bosca.graphql.gen.GitMergeStrategy
import bosca.graphql.gen.GitPullRequestStatus
import bosca.graphql.gen.GitRepositoryContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitFormatTest {

    @Test
    fun `humanBytes scales through units`() {
        assertEquals("0 B", GitFormat.humanBytes(0))
        assertEquals("512 B", GitFormat.humanBytes(512))
        assertEquals("1.0 KB", GitFormat.humanBytes(1024))
        assertEquals("1.5 KB", GitFormat.humanBytes(1536))
        assertEquals("1.0 MB", GitFormat.humanBytes(1024L * 1024))
        assertEquals("1.0 GB", GitFormat.humanBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `parseContentType is case-insensitive and validated`() {
        assertEquals(GitRepositoryContentType.GENERAL, GitFormat.parseContentType("general"))
        assertEquals(GitRepositoryContentType.GENERAL, GitFormat.parseContentType("GENERAL"))
        assertTrue(GitFormat.contentTypeNames().contains("GENERAL"))
        assertFailsWith<IllegalArgumentException> { GitFormat.parseContentType("not-a-type") }
    }

    @Test
    fun `parsePullRequestStatus is case-insensitive and validated`() {
        assertEquals(GitPullRequestStatus.OPEN, GitFormat.parsePullRequestStatus("open"))
        assertTrue(GitFormat.pullRequestStatusNames().contains("OPEN"))
        assertFailsWith<IllegalArgumentException> { GitFormat.parsePullRequestStatus("nope") }
    }

    @Test
    fun `parseMergeStrategy is case-insensitive and validated`() {
        assertEquals(GitMergeStrategy.SQUASH, GitFormat.parseMergeStrategy("squash"))
        assertTrue(GitFormat.mergeStrategyNames().contains("MERGE_COMMIT"))
        assertFailsWith<IllegalArgumentException> { GitFormat.parseMergeStrategy("nope") }
    }
}
