package bosca.git.graphql

import bosca.git.model.BlameLine
import bosca.git.model.Blob
import bosca.git.model.BranchInfo
import bosca.git.model.BranchProtectionRule
import bosca.git.model.CodeSearchResponse
import bosca.git.model.CommitInfo
import bosca.git.model.CommitStatus
import bosca.git.model.ComparisonResult
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.git.model.QuerySourceRef
import bosca.git.model.RepoStats
import bosca.git.model.Repository
import bosca.git.model.RepositoryContentType
import bosca.git.model.RepositorySearchResponse
import bosca.git.model.ScriptSourceRef
import bosca.git.model.SearchResult
import bosca.git.model.TagInfo
import bosca.git.model.TaskCommitReference
import bosca.git.model.TaskPullRequestReference
import bosca.git.model.TreeEntry
import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.git.repository.TaskPullRequestReferenceRepository
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.BranchProtectionService
import bosca.git.service.CommitStatusService
import bosca.git.service.DiffService
import bosca.git.service.PullRequestService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.git.service.SourceRefService
import bosca.git.service.WebhookService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object Git

/**
 * Root query entry point for the git repository API. All operations that access
 * repository content enforce VIEW permission via [RepositoryPermissionEvaluator].
 * Operations that modify configuration require MANAGE permission.
 */
@TypeController(type = "Git")
class GitQuery(
    private val repositoryService: RepositoryService,
    private val branchProtectionService: BranchProtectionService,
    private val pullRequestService: PullRequestService,
    private val browseService: RepositoryBrowseService,
    private val webhookService: WebhookService,
    private val sourceRefService: SourceRefService,
    private val commitStatusService: CommitStatusService,
    private val diffService: DiffService,
    private val taskCommitRefRepository: TaskCommitReferenceRepository,
    private val taskPrRefRepository: TaskPullRequestReferenceRepository,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : GraphQLController<Git> {

    @Field
    suspend fun repository(authentication: AuthenticationContext?, owner: String, repo: String): Repository? {
        val repository = repositoryService.findByOwnerAndSlug(owner, repo) ?: return null
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return repository
    }

    @Field
    suspend fun repositoryById(authentication: AuthenticationContext?, id: UUID): Repository? {
        val repository = repositoryService.findById(id) ?: return null
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return repository
    }

    @Field
    suspend fun repositories(authentication: AuthenticationContext?, ownerId: UUID?, contentType: RepositoryContentType?, includeArchived: Boolean?): List<Repository> {
        val repos = when {
            contentType != null && ownerId != null -> repositoryService.findByOwner(ownerId, includeArchived ?: false)
                .filter { it.contentType == contentType }
            contentType != null -> repositoryService.findByContentType(contentType)
            ownerId != null -> repositoryService.findByOwner(ownerId, includeArchived ?: false)
            else -> repositoryService.findAll(includeArchived ?: false)
        }
        return permissionEvaluator.filterAllowed(authentication, repos, PermissionAction.VIEW)
    }

    @Field
    suspend fun branchProtectionRules(authentication: AuthenticationContext, repositoryId: UUID): List<BranchProtectionRule> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return branchProtectionService.findByRepository(repositoryId)
    }

    @Field
    suspend fun pullRequest(authentication: AuthenticationContext?, repositoryId: UUID, number: Int): PullRequest? {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return pullRequestService.findByNumber(repositoryId, number)
    }

    @Field
    suspend fun pullRequests(
        authentication: AuthenticationContext?,
        repositoryId: UUID,
        status: PullRequestStatus?,
        authorId: UUID?,
        offset: Long?,
        limit: Int?
    ): List<PullRequest> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return pullRequestService.findByRepository(
            repositoryId, status, authorId,
            offset ?: 0, limit?.coerceIn(1, 100) ?: 25
        )
    }

    @Field
    suspend fun allPullRequests(
        authentication: AuthenticationContext?,
        status: PullRequestStatus?,
        authorId: UUID?,
        offset: Long?,
        limit: Int?
    ): List<PullRequest> {
        val repositories = repositoryService.findAll()
        val allowed = permissionEvaluator.filterAllowed(authentication, repositories, PermissionAction.VIEW)
        return pullRequestService.findByRepositories(
            allowed.map { it.id }, status, authorId,
            offset ?: 0, limit?.coerceIn(1, 100) ?: 25
        )
    }

    @Field
    suspend fun pullRequestMergePlan(authentication: AuthenticationContext?, id: UUID): List<PullRequest> {
        val pullRequest = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pullRequest.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pullRequest.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)

        val plan = pullRequestService.getMergePlan(id)
        val repositoryIds = plan.map { it.repositoryId }.distinct()
        val repositories = repositoryIds.mapNotNull { repositoryService.findById(it) }
        if (repositories.size != repositoryIds.size) {
            throw SecurityException("You cannot view every pull request required for this merge")
        }
        val visibleRepositories = permissionEvaluator.filterAllowed(
            authentication, repositories, PermissionAction.VIEW,
        )
        if (visibleRepositories.size != repositories.size) {
            throw SecurityException("You cannot view every pull request required for this merge")
        }
        return plan
    }

    @Field
    suspend fun tree(authentication: AuthenticationContext?, repositoryId: UUID, ref: String?, path: String?): List<TreeEntry> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.listTree(repositoryId, ref ?: "HEAD", path)
    }

    @Field
    suspend fun blob(authentication: AuthenticationContext?, repositoryId: UUID, ref: String, path: String): Blob? {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.readBlob(repositoryId, ref, path)
    }

    @Field
    suspend fun commits(
        authentication: AuthenticationContext?,
        repositoryId: UUID,
        ref: String?,
        path: String?,
        limit: Int?,
        offset: Long?
    ): List<CommitInfo> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.listCommits(
            repositoryId, ref ?: "HEAD", path,
            limit?.coerceIn(1, 100) ?: 25, offset ?: 0
        )
    }

    @Field
    suspend fun commit(authentication: AuthenticationContext?, repositoryId: UUID, sha: String): CommitInfo? {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.getCommit(repositoryId, sha)
    }

    @Field
    suspend fun branches(authentication: AuthenticationContext?, repositoryId: UUID): List<BranchInfo> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.listBranches(repositoryId)
    }

    @Field
    suspend fun tags(authentication: AuthenticationContext?, repositoryId: UUID): List<TagInfo> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.listTags(repositoryId)
    }

    @Field
    suspend fun blame(authentication: AuthenticationContext?, repositoryId: UUID, ref: String, path: String): List<BlameLine> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.blame(repositoryId, ref, path)
    }

    @Field
    suspend fun stats(authentication: AuthenticationContext?, repositoryId: UUID): RepoStats {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.getStats(repositoryId)
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext, repositoryId: UUID): List<Permission> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.getPermissions(repository).map { Permission(it.groupId, it.action) }
    }

    @Field
    suspend fun webhooks(authentication: AuthenticationContext, repositoryId: UUID): List<Webhook> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return webhookService.findByRepository(repositoryId)
    }

    @Field
    suspend fun webhookDeliveries(authentication: AuthenticationContext, webhookId: UUID, offset: Long?, limit: Int?): List<WebhookDelivery> {
        val webhook = webhookService.findById(webhookId)
            ?: throw NoSuchElementException("Webhook not found: $webhookId")
        val repository = repositoryService.findById(webhook.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${webhook.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return webhookService.getDeliveries(webhookId, offset ?: 0, limit?.coerceIn(1, 100) ?: 25)
    }

    @Field
    suspend fun scriptSourceRef(authentication: AuthenticationContext?, scriptId: UUID): ScriptSourceRef? {
        val ref = sourceRefService.findScriptSourceRef(scriptId) ?: return null
        val repository = repositoryService.findById(ref.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${ref.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return ref
    }

    @Field
    suspend fun querySourceRef(authentication: AuthenticationContext?, queryId: UUID): QuerySourceRef? {
        val ref = sourceRefService.findQuerySourceRef(queryId) ?: return null
        val repository = repositoryService.findById(ref.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${ref.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return ref
    }

    @Field
    suspend fun sourceRefs(authentication: AuthenticationContext, repositoryId: UUID): List<ScriptSourceRef> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return sourceRefService.findSourceRefsByRepository(repositoryId)
    }

    @Field
    suspend fun compare(authentication: AuthenticationContext?, repositoryId: UUID, baseRef: String, headRef: String): ComparisonResult {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.compare(repositoryId, baseRef, headRef, diffService)
    }

    @Field
    suspend fun searchPaths(authentication: AuthenticationContext?, repositoryId: UUID, query: String, ref: String?): List<TreeEntry> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.searchPaths(repositoryId, query, ref ?: "HEAD")
    }

    @Field
    suspend fun searchCode(
        authentication: AuthenticationContext?,
        query: String,
        repositoryId: UUID?,
        language: String?,
        offset: Int?,
        limit: Int?
    ): CodeSearchResponse {
        if (repositoryId != null) {
            val repository = repositoryService.findById(repositoryId)
                ?: throw NoSuchElementException("Repository not found: $repositoryId")
            permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        }
        return browseService.searchCode(
            query = query,
            repositoryId = repositoryId,
            language = language,
            offset = offset ?: 0,
            limit = limit?.coerceIn(1, 100) ?: 20
        )
    }

    @Field
    suspend fun searchRepositories(
        authentication: AuthenticationContext?,
        query: String,
        visibility: String?,
        archived: Boolean?,
        offset: Int?,
        limit: Int?
    ): RepositorySearchResponse {
        val response = browseService.searchRepositories(
            query = query,
            visibility = visibility,
            archived = archived,
            offset = offset ?: 0,
            limit = limit?.coerceIn(1, 100) ?: 20
        )
        val resultIds = response.results.map { it.id }.toSet()
        val repositories = resultIds.mapNotNull { repositoryService.findById(it) }
        val allowed = permissionEvaluator.filterAllowed(authentication, repositories, PermissionAction.VIEW)
        val allowedIds = allowed.map { it.id }.toSet()
        val filteredResults = response.results.filter { it.id in allowedIds }
        return response.copy(results = filteredResults, estimatedHits = filteredResults.size.toLong())
    }

    @Field
    suspend fun searchContent(authentication: AuthenticationContext?, repositoryId: UUID, query: String, ref: String?, limit: Int?): List<SearchResult> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return browseService.searchContent(repositoryId, query, ref ?: "HEAD", limit ?: 20)
    }

    @Field
    suspend fun commitStatuses(authentication: AuthenticationContext?, repositoryId: UUID, commitSha: String): List<CommitStatus> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return commitStatusService.getStatuses(repositoryId, commitSha)
    }

    @Field
    suspend fun taskCommitReferences(authentication: AuthenticationContext?, repositoryId: UUID, taskKey: String): List<TaskCommitReference> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return taskCommitRefRepository.findByTaskKey(repositoryId, taskKey)
    }

    @Field
    suspend fun taskPullRequestReferences(authentication: AuthenticationContext?, repositoryId: UUID, taskKey: String): List<TaskPullRequestReference> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return taskPrRefRepository.findByTaskKey(repositoryId, taskKey)
    }
}
