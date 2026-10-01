package bosca.git.graphql

import bosca.git.model.DiffFile
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.Review
import bosca.git.service.DiffService
import bosca.git.service.PullRequestService
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.RepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves all fields on [GitPullRequest], including property pass-throughs
 * and computed fields that require service calls (mergeability, reviews, diff).
 */
@TypeController(type = "GitPullRequest")
class GitPullRequestController(
    private val pullRequestService: PullRequestService,
    private val diffService: DiffService,
    private val profileService: ProfileService,
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
) : GraphQLController<PullRequest> {

    @Field
    fun id(source: PullRequest): UUID = source.id

    @Field
    fun repositoryId(source: PullRequest): UUID = source.repositoryId

    @Field
    fun number(source: PullRequest): Int = source.number

    @Field
    fun title(source: PullRequest): String = source.title

    @Field
    fun description(source: PullRequest): String? = source.description

    @Field
    fun authorId(source: PullRequest): UUID = source.authorId

    @Field
    fun sourceBranch(source: PullRequest): String = source.sourceBranch

    @Field
    fun targetBranch(source: PullRequest): String = source.targetBranch

    @Field
    fun sourceRepositoryId(source: PullRequest): UUID? = source.sourceRepositoryId

    @Field
    fun status(source: PullRequest): PullRequestStatus = source.status

    @Field
    fun mergeStrategy(source: PullRequest): MergeStrategy? = source.mergeStrategy

    @Field
    fun mergedBy(source: PullRequest): UUID? = source.mergedBy

    @Field
    fun mergedAt(source: PullRequest): OffsetDateTime? = source.mergedAt

    @Field
    fun mergeSha(source: PullRequest): String? = source.mergeSha

    @Field
    fun created(source: PullRequest): OffsetDateTime = source.created

    @Field
    fun updated(source: PullRequest): OffsetDateTime = source.updated

    @Field
    suspend fun mergeable(source: PullRequest): Boolean {
        if (source.status != PullRequestStatus.OPEN) return false
        val result = pullRequestService.checkMergeability(source.id)
        return result.success
    }

    @Field
    suspend fun conflictingFiles(source: PullRequest): List<String> {
        if (source.status != PullRequestStatus.OPEN) return emptyList()
        val result = pullRequestService.checkMergeability(source.id)
        return result.conflictingFiles
    }

    @Field
    suspend fun assigneeIds(source: PullRequest): List<UUID> {
        return pullRequestService.getAssignees(source.id)
    }

    @Field
    suspend fun assignees(source: PullRequest): List<Profile> {
        val ids = pullRequestService.getAssignees(source.id)
        return ids.mapNotNull {
            try { profileService.getById(it) } catch (_: Exception) { null }
        }
    }

    @Field
    suspend fun dependencies(authentication: AuthenticationContext?, source: PullRequest): List<PullRequest> {
        return visiblePullRequests(authentication, pullRequestService.getDependencies(source.id))
    }

    @Field
    suspend fun dependents(authentication: AuthenticationContext?, source: PullRequest): List<PullRequest> {
        return visiblePullRequests(authentication, pullRequestService.getDependents(source.id))
    }

    @Field
    suspend fun reviews(source: PullRequest): List<Review> {
        return pullRequestService.getReviews(source.id)
    }

    @Field
    suspend fun diff(source: PullRequest): List<DiffFile> {
        return diffService.computeDiff(
            source.repositoryId,
            "refs/heads/${source.targetBranch}",
            "refs/heads/${source.sourceBranch}"
        )
    }

    private suspend fun visiblePullRequests(
        authentication: AuthenticationContext?,
        pullRequests: List<PullRequest>
    ): List<PullRequest> {
        val repositories = pullRequests.map { it.repositoryId }.distinct().mapNotNull {
            repositoryService.findById(it)
        }
        val allowedRepositoryIds = permissionEvaluator
            .filterAllowed(authentication, repositories, PermissionAction.VIEW)
            .mapTo(mutableSetOf()) { it.id }
        return pullRequests.filter { it.repositoryId in allowedRepositoryIds }
    }
}
