package bosca.git.github

import bosca.git.model.GitHubRepositoryPair
import bosca.server.http.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The Git transport uses credentials only after REST confirms the paired immutable repository ID. */
class GitHubClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.github.com",
) {
    suspend fun repositoryUrl(pair: GitHubRepositoryPair, token: String): String {
        val request = Request.Builder().url("$baseUrl/repos/${pair.owner}/${pair.name}")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "GitHub repository lookup failed: HTTP ${response.code}" }
            val remote = json.decodeFromString(GitHubRepository.serializer(), response.body.string())
            check(remote.id == pair.githubRepositoryId) { "GitHub repository no longer matches its pair" }
        }
        return "https://github.com/${pair.owner}/${pair.name}.git"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class GitHubRepository(val id: Long)
