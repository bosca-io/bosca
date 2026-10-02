package bosca.git.github

import bosca.git.model.GitHubRepositoryPair
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.*

class GitHubClientTest {
    @Test fun `verifies immutable repository identity and keeps credentials out of the Git URL`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"id":123,"full_name":"owner/repo"}""").build())
            val client = GitHubClient(baseUrl = server.url("/").toString().removeSuffix("/"))
            val pair = GitHubRepositoryPair(UUID.random(), 123, "owner", "repo", "webhook", "token")
            assertEquals("https://github.com/owner/repo.git", client.repositoryUrl(pair, "secret-token"))
            val request = server.takeRequest()
            assertEquals("/repos/owner/repo", request.url.encodedPath)
            assertEquals("Bearer secret-token", request.headers["Authorization"])
            assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
        }
    }

    @Test fun `refuses mismatched repositories and reports HTTP failures without response credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = GitHubClient(baseUrl = server.url("/").toString().removeSuffix("/"))
            val pair = GitHubRepositoryPair(UUID.random(), 123, "owner", "repo", "webhook", "token")
            server.enqueue(MockResponse.Builder().body("""{"id":999}""").build())
            assertFailsWith<IllegalStateException> { client.repositoryUrl(pair, "secret-token") }
            server.enqueue(MockResponse.Builder().code(403).body("secret-token").build())
            val failure = assertFailsWith<IllegalStateException> { client.repositoryUrl(pair, "secret-token") }
            assertTrue(failure.message.orEmpty().contains("403"))
            assertFalse(failure.message.orEmpty().contains("secret-token"))
        }
    }
}
