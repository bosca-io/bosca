package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.CreateGitPullRequest
import bosca.graphql.gen.CreateGitPullRequestInput
import bosca.graphql.gen.GetGitPullRequest
import bosca.graphql.gen.GetGitPullRequests
import bosca.graphql.gen.GetGitRepositories
import bosca.graphql.gen.GetGitRepositoriesData
import bosca.graphql.gen.GetGitRepository
import bosca.graphql.gen.GetGitRepositoryData
import bosca.graphql.gen.GitMergeStrategy
import bosca.graphql.gen.GitPullRequestStatus
import bosca.graphql.gen.GitRepositoryContentType
import bosca.graphql.gen.IGitPullRequestFragment
import bosca.graphql.gen.MergeGitPullRequest
import kotlin.uuid.Uuid

/**
 * Read/write access to the Bosca git domain over GraphQL. Repository
 * discovery and pull-request management live here; the actual fetch/push
 * of objects happens over git's HTTPS protocol, authenticated by the
 * credential helper.
 */
class GitApi(network: NetworkClient) : Api(network) {

    suspend fun listRepositories(
        contentType: GitRepositoryContentType? = null,
        includeArchived: Boolean = false,
        ownerId: Uuid? = null,
    ): List<GetGitRepositoriesData.Git.Repositories> =
        network.boscaGraphql.execute(
            GetGitRepositories,
            GetGitRepositories.Variables(contentType = contentType, includeArchived = includeArchived, ownerId = ownerId),
        ).git.repositories

    suspend fun getRepository(owner: String, repo: String): GetGitRepositoryData.Git.Repository? =
        network.boscaGraphql.execute(GetGitRepository, GetGitRepository.Variables(owner, repo)).git.repository

    suspend fun listPullRequests(
        repositoryId: Uuid,
        status: GitPullRequestStatus? = null,
        limit: Int = 50,
        offset: Long = 0,
    ): List<IGitPullRequestFragment> =
        network.boscaGraphql.execute(
            GetGitPullRequests,
            GetGitPullRequests.Variables(repositoryId = repositoryId, status = status, limit = limit, offset = offset),
        ).git.pullRequests

    suspend fun getPullRequest(repositoryId: Uuid, number: Int): IGitPullRequestFragment? =
        network.boscaGraphql.execute(GetGitPullRequest, GetGitPullRequest.Variables(repositoryId, number)).git.pullRequest

    suspend fun createPullRequest(
        repositoryId: Uuid,
        title: String,
        sourceBranch: String,
        targetBranch: String,
        description: String? = null,
        isDraft: Boolean = false,
    ): IGitPullRequestFragment {
        val input = CreateGitPullRequestInput(
            repositoryId = repositoryId,
            title = title,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            description = description,
            isDraft = isDraft,
            sourceRepositoryId = null,
        )
        return network.boscaGraphql.execute(CreateGitPullRequest, CreateGitPullRequest.Variables(input)).git.createPullRequest
    }

    suspend fun mergePullRequest(id: Uuid, strategy: GitMergeStrategy): IGitPullRequestFragment =
        network.boscaGraphql.execute(MergeGitPullRequest, MergeGitPullRequest.Variables(id, strategy)).git.mergePullRequest
}
