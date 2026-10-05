package bosca.git.graphql

import bosca.git.model.GitHubPullRequestState
import bosca.git.model.GitHubPullRequestSnapshot
import bosca.git.model.PullRequestStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "GitHubPullRequestState")
class GitHubPullRequestStateController : GraphQLController<GitHubPullRequestState> {
    @Field
    fun repositoryId(source: GitHubPullRequestState): UUID = source.repositoryId
    @Field
    fun pullRequestId(source: GitHubPullRequestState): UUID? = source.pullRequestId
    @Field
    fun githubId(source: GitHubPullRequestState): Long? = source.githubId
    @Field
    fun githubNumber(source: GitHubPullRequestState): Int? = source.githubNumber
    @Field
    fun snapshot(source: GitHubPullRequestState): GitHubPullRequestSnapshot? = source.snapshot
    @Field
    fun bosca(source: GitHubPullRequestState): GitHubPullRequestSnapshot? = source.boscaSnapshot
    @Field
    fun github(source: GitHubPullRequestState): GitHubPullRequestSnapshot? = source.githubSnapshot
    @Field
    fun problem(source: GitHubPullRequestState): String? = source.problem
    @Field
    fun modified(source: GitHubPullRequestState): OffsetDateTime = source.modified
}

@TypeController(type = "GitHubPullRequestSnapshot")
class GitHubPullRequestSnapshotController : GraphQLController<GitHubPullRequestSnapshot> {
    @Field
    fun title(source: GitHubPullRequestSnapshot): String = source.title
    @Field
    fun description(source: GitHubPullRequestSnapshot): String? = source.description
    @Field
    fun sourceBranch(source: GitHubPullRequestSnapshot): String = source.sourceBranch
    @Field
    fun targetBranch(source: GitHubPullRequestSnapshot): String = source.targetBranch
    @Field
    fun status(source: GitHubPullRequestSnapshot): PullRequestStatus = source.status
    @Field
    fun mergeSha(source: GitHubPullRequestSnapshot): String? = source.mergeSha
}
