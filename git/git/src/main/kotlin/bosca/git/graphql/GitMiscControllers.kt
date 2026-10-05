package bosca.git.graphql

import bosca.git.model.CommitInfo
import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusState
import bosca.git.model.ComparisonResult
import bosca.git.model.CodeSearchResponse
import bosca.git.model.CodeSearchResult
import bosca.git.model.DiffFile
import bosca.git.model.QuerySourceRef
import bosca.git.model.RepositorySearchResponse
import bosca.git.model.RepositorySearchResult
import bosca.git.model.ScriptSourceRef
import bosca.git.model.SearchResult
import bosca.git.model.TaskCommitReference
import bosca.git.model.TaskPullRequestReference
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves all fields on [GitCommitStatus] representing a CI/CD status check
 * reported against a specific commit.
 */
@TypeController(type = "GitCommitStatus")
class GitCommitStatusController : GraphQLController<CommitStatus> {

    @Field
    fun id(source: CommitStatus): UUID = source.id

    @Field
    fun repositoryId(source: CommitStatus): UUID = source.repositoryId

    @Field
    fun commitSha(source: CommitStatus): String = source.commitSha

    @Field
    fun context(source: CommitStatus): String = source.context

    @Field
    fun state(source: CommitStatus): CommitStatusState = source.state

    @Field
    fun description(source: CommitStatus): String? = source.description

    @Field
    fun targetUrl(source: CommitStatus): String? = source.targetUrl

    @Field
    fun created(source: CommitStatus): OffsetDateTime = source.created
}

/**
 * Resolves all fields on [GitTaskCommitReference] linking a Work Ops task key
 * to a commit SHA extracted from commit messages.
 */
@TypeController(type = "GitTaskCommitReference")
class GitTaskCommitReferenceController : GraphQLController<TaskCommitReference> {

    @Field
    fun id(source: TaskCommitReference): UUID = source.id

    @Field
    fun repositoryId(source: TaskCommitReference): UUID = source.repositoryId

    @Field
    fun taskKey(source: TaskCommitReference): String = source.taskKey

    @Field
    fun commitSha(source: TaskCommitReference): String = source.commitSha

    @Field
    fun created(source: TaskCommitReference): OffsetDateTime = source.created
}

/**
 * Resolves all fields on [GitTaskPullRequestReference] linking a Work Ops task
 * key to a pull request.
 */
@TypeController(type = "GitTaskPullRequestReference")
class GitTaskPullRequestReferenceController : GraphQLController<TaskPullRequestReference> {

    @Field
    fun id(source: TaskPullRequestReference): UUID = source.id

    @Field
    fun repositoryId(source: TaskPullRequestReference): UUID = source.repositoryId

    @Field
    fun taskKey(source: TaskPullRequestReference): String = source.taskKey

    @Field
    fun pullRequestId(source: TaskPullRequestReference): UUID = source.pullRequestId

    @Field
    fun pullRequestNumber(source: TaskPullRequestReference): Int = source.pullRequestNumber

    @Field
    fun created(source: TaskPullRequestReference): OffsetDateTime = source.created
}

/**
 * Resolves all fields on [GitScriptSourceRef] binding a script to a file path
 * within a git repository at a given ref.
 */
@TypeController(type = "GitScriptSourceRef")
class GitScriptSourceRefController : GraphQLController<ScriptSourceRef> {

    @Field
    fun scriptId(source: ScriptSourceRef): UUID = source.scriptId

    @Field
    fun repositoryId(source: ScriptSourceRef): UUID = source.repositoryId

    @Field
    fun path(source: ScriptSourceRef): String = source.path

    @Field
    fun ref(source: ScriptSourceRef): String = source.ref

    @Field
    fun resolvedCommit(source: ScriptSourceRef): String? = source.resolvedCommit
}

/**
 * Resolves all fields on [GitQuerySourceRef] binding a saved query to a file
 * path within a git repository at a given ref.
 */
@TypeController(type = "GitQuerySourceRef")
class GitQuerySourceRefController : GraphQLController<QuerySourceRef> {

    @Field
    fun queryId(source: QuerySourceRef): UUID = source.queryId

    @Field
    fun repositoryId(source: QuerySourceRef): UUID = source.repositoryId

    @Field
    fun path(source: QuerySourceRef): String = source.path

    @Field
    fun ref(source: QuerySourceRef): String = source.ref

    @Field
    fun resolvedCommit(source: QuerySourceRef): String? = source.resolvedCommit
}

/**
 * Resolves all fields on [GitComparisonResult] representing a diff and commit
 * list between two refs.
 */
@TypeController(type = "GitComparisonResult")
class GitComparisonResultController : GraphQLController<ComparisonResult> {

    @Field
    fun baseRef(source: ComparisonResult): String = source.baseRef

    @Field
    fun headRef(source: ComparisonResult): String = source.headRef

    @Field
    fun commits(source: ComparisonResult): List<CommitInfo> = source.commits

    @Field
    fun files(source: ComparisonResult): List<DiffFile> = source.files

    @Field
    fun filesChanged(source: ComparisonResult): Int = source.filesChanged

    @Field
    fun insertions(source: ComparisonResult): Int = source.insertions

    @Field
    fun deletions(source: ComparisonResult): Int = source.deletions
}

/**
 * Resolves all fields on [GitSearchResult] representing a single content search
 * match within a repository.
 */
@TypeController(type = "GitSearchResult")
class GitSearchResultController : GraphQLController<SearchResult> {

    @Field
    fun filePath(source: SearchResult): String = source.filePath

    @Field
    fun lineNumber(source: SearchResult): Int = source.lineNumber

    @Field
    fun snippet(source: SearchResult): String = source.snippet
}

@TypeController(type = "GitCodeSearchResult")
class GitCodeSearchResultController : GraphQLController<CodeSearchResult> {

    @Field
    fun repositoryId(source: CodeSearchResult): UUID = source.repositoryId

    @Field
    fun repositoryName(source: CodeSearchResult): String = source.repositoryName

    @Field
    fun repositorySlug(source: CodeSearchResult): String = source.repositorySlug

    @Field
    fun filePath(source: CodeSearchResult): String = source.filePath

    @Field
    fun language(source: CodeSearchResult): String = source.language

    @Field
    fun snippet(source: CodeSearchResult): String = source.snippet
}

@TypeController(type = "GitCodeSearchResponse")
class GitCodeSearchResponseController : GraphQLController<CodeSearchResponse> {

    @Field
    fun results(source: CodeSearchResponse): List<CodeSearchResult> = source.results

    @Field
    fun estimatedHits(source: CodeSearchResponse): Long = source.estimatedHits
}

@TypeController(type = "GitRepositorySearchResult")
class GitRepositorySearchResultController : GraphQLController<RepositorySearchResult> {

    @Field
    fun id(source: RepositorySearchResult): UUID = source.id

    @Field
    fun name(source: RepositorySearchResult): String = source.name

    @Field
    fun slug(source: RepositorySearchResult): String = source.slug

    @Field
    fun description(source: RepositorySearchResult): String = source.description

    @Field
    fun ownerId(source: RepositorySearchResult): UUID = source.ownerId

    @Field
    fun visibility(source: RepositorySearchResult): String = source.visibility

    @Field
    fun defaultBranch(source: RepositorySearchResult): String = source.defaultBranch

    @Field
    fun archived(source: RepositorySearchResult): Boolean = source.archived
}

@TypeController(type = "GitRepositorySearchResponse")
class GitRepositorySearchResponseController : GraphQLController<RepositorySearchResponse> {

    @Field
    fun results(source: RepositorySearchResponse): List<RepositorySearchResult> = source.results

    @Field
    fun estimatedHits(source: RepositorySearchResponse): Long = source.estimatedHits
}
