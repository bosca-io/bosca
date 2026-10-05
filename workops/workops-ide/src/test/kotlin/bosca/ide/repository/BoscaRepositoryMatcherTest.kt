package bosca.ide.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class BoscaRepositoryMatcherTest {
    @Test
    fun `matching remains server scoped when two servers expose the same clone identity`() {
        val repositories = listOf(
            repository("server-a", "repo-a", "https://git.bosca.example/team/repo.git"),
            repository("server-b", "repo-b", "https://git.bosca.example/team/repo.git"),
            repository("server-b", "other", "https://git.bosca.example/team/other.git"),
        )

        val matches = BoscaRepositoryMatcher.match(
            listOf("git@git.bosca.example:team/repo.git"),
            repositories,
        )

        assertEquals(listOf("server-a", "server-b"), matches.map { it.serverProfileId })
        assertEquals(listOf("repo-a", "repo-b"), matches.map { it.id })
    }

    private fun repository(serverId: String, id: String, cloneUrl: String) = BoscaRemoteRepository(
        serverProfileId = serverId,
        id = id,
        name = id,
        slug = id,
        cloneUrl = cloneUrl,
        defaultBranch = "main",
    )
}
