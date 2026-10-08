package bosca.git.github

import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubPullRequest
import bosca.git.model.GitHubCreatePullRequestInput
import bosca.git.model.GitHubUpdatePullRequestInput
import bosca.git.model.GitHubWebhookUser
import bosca.serialization.OffsetDateTimeSerializer
import bosca.server.http.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** The Git transport uses credentials only after REST confirms the paired immutable repository ID. */
class GitHubClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.github.com",
) {
    /** Resolves a human account by username without requiring a repository token. */
    suspend fun humanUserId(username: String): Long {
        require(username.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))) {
            "Enter a GitHub username, such as octocat."
        }
        val request = Request.Builder().url(url("users", username))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10").build()
        val user = json.decodeFromString(GitHubWebhookUser.serializer(), execute(request, "user lookup"))
        require(user.id > 0 && user.type == "User") { "Choose a GitHub person’s account; organizations and bots cannot be mapped." }
        return user.id
    }

    /** Resolves the immutable repository identity from the owner/name using the configured token. */
    suspend fun repositoryId(owner: String, name: String, token: String): Long {
        val remote = json.decodeFromString(GitHubRepository.serializer(), execute(
            request(token, "repos", owner, name).build(), "repository lookup",
        ))
        check(remote.id > 0) { "GitHub returned an invalid repository ID" }
        return remote.id
    }

    suspend fun repositoryUrl(pair: GitHubRepositoryPair, token: String): String {
        verifyRepository(pair, token)
        return "https://github.com/${pair.owner}/${pair.name}.git"
    }

    /** Reads the current PR instead of replaying an old webhook snapshot. */
    suspend fun getPullRequest(pair: GitHubRepositoryPair, token: String, number: Int): GitHubPullRequest {
        require(number > 0) { "Invalid GitHub pull request number" }
        verifyRepository(pair, token)
        return json.decodeFromString(GitHubPullRequest.serializer(), execute(
            request(token, "repos", pair.owner, pair.name, "pulls", number.toString()).build(), "pull request lookup",
        ))
    }

    /** Lists open and closed PRs; callers advance ordinary page numbers until a page is empty. */
    suspend fun listPullRequests(
        pair: GitHubRepositoryPair, token: String, page: Int = 1, head: String? = null,
    ): List<GitHubPullRequest> {
        require(page > 0) { "Invalid GitHub pull request page" }
        verifyRepository(pair, token)
        val url = url("repos", pair.owner, pair.name, "pulls").newBuilder()
            .addQueryParameter("state", "all").addQueryParameter("per_page", "100")
            .addQueryParameter("page", page.toString())
        head?.let { url.addQueryParameter("head", "${pair.owner}:$it") }
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubPullRequest.serializer()), execute(
            request(token).url(url.build()).build(), "pull request listing",
        ))
    }

    /** Creates a counterpart after verifying the pair's immutable repository identity. */
    suspend fun createPullRequest(
        pair: GitHubRepositoryPair, token: String, input: GitHubCreatePullRequestInput,
    ): GitHubPullRequest {
        verifyRepository(pair, token)
        val body = json.encodeToString(GitHubCreatePullRequestInput.serializer(), input).toRequestBody(JSON_MEDIA_TYPE)
        return json.decodeFromString(GitHubPullRequest.serializer(), execute(
            request(token, "repos", pair.owner, pair.name, "pulls").post(body).build(), "pull request creation",
        ))
    }

    /**
     * Patches only supplied metadata and open/closed fields, without invoking GitHub's merge operation.
     * Omitted fields are preserved. GitHub provides no conditional-write protection for this operation.
     */
    suspend fun updatePullRequest(
        pair: GitHubRepositoryPair, token: String, number: Int, input: GitHubUpdatePullRequestInput,
    ): GitHubPullRequest {
        require(number > 0 && (input.state == null || input.state in setOf("open", "closed"))) { "Invalid GitHub pull request update" }
        require(input.title != null || input.body != null || input.base != null || input.state != null) { "A GitHub pull request update needs a changed field" }
        input.body?.let { require(it == JsonNull || it.isString) { "GitHub pull request body must be a string or null" } }
        verifyRepository(pair, token)
        val body = json.encodeToString(GitHubUpdatePullRequestInput.serializer(), input).toRequestBody(JSON_MEDIA_TYPE)
        return json.decodeFromString(GitHubPullRequest.serializer(), execute(
            request(token, "repos", pair.owner, pair.name, "pulls", number.toString()).patch(body).build(), "pull request update",
        ))
    }

    /** Changes draft status through GitHub's typed GraphQL stage mutation, without merging. */
    suspend fun setPullRequestDraft(pair: GitHubRepositoryPair, token: String, nodeId: String, draft: Boolean) {
        require(nodeId.isNotBlank()) { "GitHub pull request node ID is required" }
        verifyRepository(pair, token)
        val inputType = if (draft) "ConvertPullRequestToDraftInput" else "MarkPullRequestReadyForReviewInput"
        val mutation = if (draft) "convertPullRequestToDraft" else "markPullRequestReadyForReview"
        val query = "mutation(${'$'}input: $inputType!) { changeStage: $mutation(input: ${'$'}input) { pullRequest { id isDraft } } }"
        val body = json.encodeToString(GitHubDraftRequest.serializer(), GitHubDraftRequest(
            query, GitHubDraftVariables(GitHubDraftInput(nodeId)),
        )).toRequestBody(JSON_MEDIA_TYPE)
        val response = json.decodeFromString(GitHubDraftResponse.serializer(), execute(
            request(token, "graphql").post(body).build(), "pull request stage update",
        ))
        check(response.errors.isEmpty()) { "GitHub pull request stage update failed" }
        val updated = response.data?.changeStage?.pullRequest
        check(updated?.id == nodeId && updated.isDraft == draft) { "GitHub pull request stage was not updated" }
    }

    private suspend fun verifyRepository(pair: GitHubRepositoryPair, token: String) {
        check(repositoryId(pair.owner, pair.name, token) == pair.githubRepositoryId) { "GitHub repository no longer matches its pair" }
    }

    private fun url(vararg segments: String) = baseUrl.toHttpUrl().newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun request(token: String, vararg segments: String) = Request.Builder().url(url(*segments))
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2026-03-10")

    private suspend fun execute(request: Request, operation: String): String = client.newCall(request).await().use { response ->
        if (response.code == 422) throw GitHubRequestRejectedException("GitHub $operation failed: HTTP 422")
        check(response.isSuccessful) {
            val guidance = if (response.code == 404 && operation == "repository lookup") {
                ". The repository was not found or is not accessible to the selected token. " +
                    "Check the GitHub owner and repository name, and ensure the token has access to this repository. " +
                    "For organization repositories, check token approval and SSO authorization."
            } else if (response.code == 404 && operation == "user lookup") {
                ". No account was found. Check the GitHub username and try again."
            } else ""
            "GitHub $operation failed: HTTP ${response.code}$guidance"
        }
        response.body.string()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) }
        }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** GitHub refused the request (HTTP 422), including validation and abuse-limit responses. */
class GitHubRequestRejectedException(message: String) : IllegalStateException(message)

@Serializable
internal data class GitHubRepository(val id: Long)

@Serializable
private data class GitHubDraftInput(val pullRequestId: String)
@Serializable
private data class GitHubDraftVariables(val input: GitHubDraftInput)
@Serializable
private data class GitHubDraftRequest(val query: String, val variables: GitHubDraftVariables)
@Serializable
private data class GitHubDraftResponse(val data: GitHubDraftData? = null, val errors: List<GitHubGraphQLError> = emptyList())
@Serializable
private data class GitHubGraphQLError(val message: String)
@Serializable
private data class GitHubDraftData(val changeStage: GitHubDraftChange? = null)
@Serializable
private data class GitHubDraftChange(val pullRequest: GitHubDraftPullRequest)
@Serializable
private data class GitHubDraftPullRequest(val id: String, val isDraft: Boolean)
