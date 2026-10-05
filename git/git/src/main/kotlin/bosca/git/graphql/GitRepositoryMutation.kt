package bosca.git.graphql

import bosca.git.model.AddReviewCommentInput
import bosca.git.model.BranchInfo
import bosca.git.model.BranchProtectionRule
import bosca.git.model.BranchProtectionRuleInput
import bosca.git.model.CreatePullRequestInput
import bosca.git.model.CreateRepositoryInput
import bosca.git.model.ForkRepositoryInput
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.QuerySourceRef
import bosca.git.model.Repository
import bosca.git.model.Review
import bosca.git.model.ReviewComment
import bosca.git.model.ScriptSourceRef
import bosca.git.model.SourceRefInput
import bosca.git.model.SubmitReviewInput
import bosca.git.model.UpdatePullRequestInput
import bosca.git.model.UpdateRepositoryInput
import bosca.git.model.Webhook
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.BranchProtectionService
import bosca.di.ObjectProvider
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.git.service.CommitFileInput
import bosca.git.service.CommitFileResult
import bosca.git.service.DeleteFileInput
import bosca.git.service.PullRequestService
import bosca.git.service.QuerySourceBackfill
import bosca.git.service.RepositoryLifecycleService
import bosca.git.service.ScriptSourceBackfill
import bosca.git.service.RepositoryService
import bosca.git.service.RepositoryWriteBusyException
import bosca.git.service.RepositoryWriteService
import bosca.git.service.SourceRefService
import bosca.git.service.WebhookService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class GraphQLCommitFileInput(
    @Contextual val repositoryId: UUID,
    val branch: String = "main",
    val path: String,
    val content: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
)

object GitMutation

/**
 * Mutation entry point for git repository lifecycle operations: create, update,
 * archive, delete, restore, transfer, fork, permission management, and branch
 * protection rule CRUD.
 */
@TypeController
class GitRepositoryMutation(
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val branchProtectionService: BranchProtectionService,
    private val pullRequestService: PullRequestService,
    private val webhookService: WebhookService,
    private val sourceRefService: SourceRefService,
    private val writeService: RepositoryWriteService,
    private val querySourceBackfill: ObjectProvider<QuerySourceBackfill>,
    private val scriptSourceBackfill: ObjectProvider<ScriptSourceBackfill>,
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val lifecycleService: RepositoryLifecycleService,
) : GraphQLController<GitMutation> {

    @Field
    suspend fun createRepository(authentication: AuthenticationContext, input: CreateRepositoryInput): Repository {
        val inCreatorGroup = groupEvaluator.hasGroup(authentication, "git.creator") ||
            groupEvaluator.hasAdminGroup(authentication)
        if (!inCreatorGroup) groupEvaluator.throwUnauthorized()
        val owner = profileService.getById(input.ownerId)
        profilePermissionEvaluator.verifyAllowed(authentication, owner, PermissionAction.MANAGE)
        return repositoryService.create(input)
    }

    @Field
    suspend fun updateRepository(authentication: AuthenticationContext, id: UUID, input: UpdateRepositoryInput): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.update(id, input)
    }

    @Field
    suspend fun archiveRepository(authentication: AuthenticationContext, id: UUID): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.archive(id)
    }

    @Field
    suspend fun deleteRepository(authentication: AuthenticationContext, id: UUID): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.delete(id)
    }

    @Field
    suspend fun restoreRepository(authentication: AuthenticationContext, id: UUID): Repository {
        val repository = repositoryService.findByIdIncludingDeleted(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.restore(id)
    }

    @Field
    suspend fun transferRepository(authentication: AuthenticationContext, id: UUID, newOwnerId: UUID): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.transfer(id, newOwnerId)
    }

    @Field
    suspend fun renameRepository(authentication: AuthenticationContext, id: UUID, newSlug: String): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return repositoryService.rename(id, newSlug)
    }

    @Field
    suspend fun runRepositoryGc(authentication: AuthenticationContext, id: UUID): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        if (!lifecycleService.runGc(id)) {
            throw RepositoryWriteBusyException(id)
        }
        return repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found after GC: $id")
    }

    @Field
    suspend fun runRepositoryRepair(authentication: AuthenticationContext, id: UUID): Repository {
        val repository = repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found: $id")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        if (!lifecycleService.repair(id)) {
            throw RepositoryWriteBusyException(id)
        }
        return repositoryService.findById(id)
            ?: throw NoSuchElementException("Repository not found after repair: $id")
    }

    @Field
    suspend fun forkRepository(authentication: AuthenticationContext, input: ForkRepositoryInput): Repository {
        val repository = repositoryService.findById(input.sourceRepositoryId)
            ?: throw NoSuchElementException("Repository not found: ${input.sourceRepositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return repositoryService.fork(input)
    }

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        val repository = repositoryService.findById(permission.entityId)
            ?: throw NoSuchElementException("Repository not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        repositoryService.addPermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    @Field
    suspend fun removePermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        val repository = repositoryService.findById(permission.entityId)
            ?: throw NoSuchElementException("Repository not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        repositoryService.removePermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    @Field
    suspend fun createBranch(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        branchName: String,
        sourceRef: String
    ): BranchInfo {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return repositoryService.createBranch(repositoryId, branchName, sourceRef, principalId)
    }

    @Field
    suspend fun deleteTag(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        tag: String
    ): Boolean {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        writeService.deleteTag(repositoryId, tag)
        return true
    }

    @Field
    suspend fun deleteBranch(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        branchName: String
    ): Boolean {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        require(branchName != repository.defaultBranch) {
            "'$branchName' is the default branch — change the default first"
        }
        branchProtectionService.findMatchingRule(repositoryId, branchName)?.let {
            error("'$branchName' matches the branch protection rule '${it.pattern}' — remove the rule first")
        }
        writeService.deleteBranch(repositoryId, branchName)
        return true
    }

    @Field
    suspend fun createBranchProtectionRule(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        input: BranchProtectionRuleInput
    ): BranchProtectionRule {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return branchProtectionService.create(input.toRule(repositoryId))
    }

    @Field
    suspend fun updateBranchProtectionRule(
        authentication: AuthenticationContext,
        id: UUID,
        input: BranchProtectionRuleInput
    ): BranchProtectionRule {
        val existing = branchProtectionService.findById(id)
            ?: throw NoSuchElementException("Branch protection rule not found: $id")
        val repository = repositoryService.findById(existing.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${existing.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return branchProtectionService.update(input.toRule(existing.repositoryId, id))
    }

    @Field
    suspend fun deleteBranchProtectionRule(authentication: AuthenticationContext, id: UUID): Boolean {
        val existing = branchProtectionService.findById(id)
            ?: throw NoSuchElementException("Branch protection rule not found: $id")
        val repository = repositoryService.findById(existing.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${existing.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        branchProtectionService.delete(id)
        return true
    }

    @Field
    suspend fun createPullRequest(authentication: AuthenticationContext, input: CreatePullRequestInput): PullRequest {
        val repository = repositoryService.findById(input.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${input.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return pullRequestService.create(input, actorProfileId(authentication))
    }

    @Field
    suspend fun updatePullRequest(authentication: AuthenticationContext, id: UUID, input: UpdatePullRequestInput): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return pullRequestService.update(id, input)
    }

    @Field
    suspend fun mergePullRequest(authentication: AuthenticationContext, id: UUID, strategy: MergeStrategy): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        val authenticatedPrincipal = authentication.principal()
            ?: throw SecurityException("Authentication required")
        val actorProfile = profileService.getPrimaryProfile(authenticatedPrincipal.asPrincipal())
            ?: throw SecurityException("Authenticated principal has no profile")
        return pullRequestService.merge(
            id, strategy, actorProfile.id,
            actorProfile.name,
            authenticatedPrincipal.id.toString()
        )
    }

    @Field
    suspend fun mergePullRequestWithDependencies(
        authentication: AuthenticationContext,
        id: UUID,
        strategy: MergeStrategy
    ): List<PullRequest> {
        val plan = pullRequestService.getMergePlan(id)
        if (plan.isEmpty()) {
            throw IllegalArgumentException("Pull request is already merged")
        }
        val repositoryIds = plan.map { it.repositoryId }.distinct()
        val repositories = repositoryIds.mapNotNull { repositoryService.findById(it) }
        if (repositories.size != repositoryIds.size || permissionEvaluator.filterAllowed(
                authentication, repositories, PermissionAction.VIEW,
            ).size != repositories.size
        ) {
            throw SecurityException("You cannot view every pull request required for this merge")
        }
        for (repository in repositories) {
            permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        }
        val authenticatedPrincipal = authentication.principal()
            ?: throw SecurityException("Authentication required")
        val actorProfile = profileService.getPrimaryProfile(authenticatedPrincipal.asPrincipal())
            ?: throw SecurityException("Authenticated principal has no profile")
        return pullRequestService.mergeWithDependencies(
            id, strategy, actorProfile.id,
            actorProfile.name,
            authenticatedPrincipal.id.toString(),
            plan.map { it.id },
        )
    }

    @Field
    suspend fun addPullRequestDependency(
        authentication: AuthenticationContext,
        id: UUID,
        dependencyId: UUID
    ): PullRequest {
        val pullRequest = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pullRequest.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pullRequest.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)

        val dependency = pullRequestService.findById(dependencyId)
            ?: throw NoSuchElementException("Pull request not found: $dependencyId")
        val dependencyRepository = repositoryService.findById(dependency.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${dependency.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, dependencyRepository, PermissionAction.VIEW)
        return pullRequestService.addDependency(id, dependencyId)
    }

    @Field
    suspend fun removePullRequestDependency(
        authentication: AuthenticationContext,
        id: UUID,
        dependencyId: UUID
    ): PullRequest {
        val pullRequest = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pullRequest.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pullRequest.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return pullRequestService.removeDependency(id, dependencyId)
    }

    @Field
    suspend fun closePullRequest(authentication: AuthenticationContext, id: UUID): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return pullRequestService.close(id)
    }

    @Field
    suspend fun reopenPullRequest(authentication: AuthenticationContext, id: UUID): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return pullRequestService.reopen(id)
    }

    @Field
    suspend fun markPullRequestReady(authentication: AuthenticationContext, id: UUID): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return pullRequestService.markReady(id)
    }

    @Field
    suspend fun assignPullRequest(authentication: AuthenticationContext, id: UUID, profileId: UUID): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        pullRequestService.addAssignee(id, profileId)
        return pr
    }

    @Field
    suspend fun unassignPullRequest(authentication: AuthenticationContext, id: UUID, profileId: UUID): PullRequest {
        val pr = pullRequestService.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        pullRequestService.removeAssignee(id, profileId)
        return pr
    }

    @Field
    suspend fun submitReview(authentication: AuthenticationContext, input: SubmitReviewInput): Review {
        val pr = pullRequestService.findById(input.pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: ${input.pullRequestId}")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return pullRequestService.submitReview(input, actorProfileId(authentication))
    }

    @Field
    suspend fun addReviewComment(authentication: AuthenticationContext, input: AddReviewCommentInput): ReviewComment {
        val pr = pullRequestService.findById(input.pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: ${input.pullRequestId}")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        val authorId = actorProfileId(authentication)
        return pullRequestService.addReviewComment(
            reviewId = input.reviewId,
            pullRequestId = input.pullRequestId,
            authorId = authorId,
            filePath = input.filePath,
            oldLineNumber = input.oldLineNumber,
            newLineNumber = input.newLineNumber,
            commitSha = input.commitSha,
            content = input.content,
        )
    }

    @Field
    suspend fun resolveReviewThread(
        authentication: AuthenticationContext,
        pullRequestId: UUID,
        filePath: String,
        lineNumber: Int
    ): Boolean {
        val pr = pullRequestService.findById(pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: $pullRequestId")
        val repository = repositoryService.findById(pr.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pr.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        pullRequestService.resolveThread(pullRequestId, filePath, lineNumber)
        return true
    }

    @Field
    suspend fun createWebhook(authentication: AuthenticationContext, repositoryId: UUID, input: Webhook): Webhook {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return webhookService.create(input.copy(repositoryId = repositoryId))
    }

    @Field
    suspend fun updateWebhook(authentication: AuthenticationContext, id: UUID, input: Webhook): Webhook {
        val existing = webhookService.findById(id)
            ?: throw NoSuchElementException("Webhook not found: $id")
        val repository = repositoryService.findById(existing.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${existing.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return webhookService.update(input.copy(id = id, repositoryId = existing.repositoryId))
    }

    @Field
    suspend fun deleteWebhook(authentication: AuthenticationContext, id: UUID): Boolean {
        val existing = webhookService.findById(id)
            ?: throw NoSuchElementException("Webhook not found: $id")
        val repository = repositoryService.findById(existing.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${existing.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        webhookService.delete(id)
        return true
    }

    @Field
    suspend fun setScriptSourceRef(
        authentication: AuthenticationContext,
        scriptId: UUID,
        repositoryId: UUID,
        path: String,
        ref: String
    ): ScriptSourceRef {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        val sourceRef = sourceRefService.setScriptSourceRef(scriptId, SourceRefInput(repositoryId, path, ref))
        if (scriptSourceBackfill.exists) {
            scriptSourceBackfill.get().backfillScript(scriptId)
        }
        return sourceRef
    }

    @Field
    suspend fun removeScriptSourceRef(authentication: AuthenticationContext, scriptId: UUID): Boolean {
        val existing = sourceRefService.findScriptSourceRef(scriptId)
            ?: throw NoSuchElementException("No source ref for script: $scriptId")
        val repository = repositoryService.findById(existing.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${existing.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        sourceRefService.removeScriptSourceRef(scriptId)
        return true
    }

    @Field
    suspend fun setQuerySourceRef(
        authentication: AuthenticationContext,
        queryId: UUID,
        repositoryId: UUID,
        path: String,
        ref: String
    ): QuerySourceRef {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        val sourceRef = sourceRefService.setQuerySourceRef(queryId, SourceRefInput(repositoryId, path, ref))
        if (querySourceBackfill.exists) {
            querySourceBackfill.get().backfillQuery(queryId)
        }
        return sourceRef
    }

    @Field
    suspend fun removeQuerySourceRef(authentication: AuthenticationContext, queryId: UUID): Boolean {
        val existing = sourceRefService.findQuerySourceRef(queryId)
            ?: throw NoSuchElementException("No source ref for query: $queryId")
        val repository = repositoryService.findById(existing.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${existing.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        sourceRefService.removeQuerySourceRef(queryId)
        return true
    }

    @Field
    suspend fun syncSourceRefs(authentication: AuthenticationContext, repositoryId: UUID): Int {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        var changed = 0
        if (querySourceBackfill.exists) changed += querySourceBackfill.get().backfillRepository(repositoryId)
        if (scriptSourceBackfill.exists) changed += scriptSourceBackfill.get().backfillRepository(repositoryId)
        return changed
    }

    /** Git collaboration rows and communications recipients are profile-scoped, not principal-scoped. */
    private suspend fun actorProfileId(authentication: AuthenticationContext): UUID {
        val principal = authentication.principal()?.asPrincipal()
            ?: throw SecurityException("Authentication required")
        return profileService.getPrimaryProfile(principal)?.id
            ?: throw SecurityException("Authenticated principal has no profile")
    }

    @Field
    suspend fun commitFile(
        authentication: AuthenticationContext,
        input: GraphQLCommitFileInput,
    ): CommitFileResult {
        val repository = repositoryService.findById(input.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${input.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return writeService.commitFile(
            CommitFileInput(
                repositoryId = input.repositoryId,
                branch = input.branch,
                path = input.path,
                content = input.content,
                message = input.message,
                authorName = input.authorName,
                authorEmail = input.authorEmail,
            ),
            principalId,
        )
    }

    @Field
    suspend fun deleteFile(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        branch: String,
        path: String,
        authorName: String,
        authorEmail: String,
    ): CommitFileResult {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return writeService.deleteFile(
            DeleteFileInput(
                repositoryId = repositoryId,
                branch = branch,
                path = path,
                message = "Delete $path",
                authorName = authorName,
                authorEmail = authorEmail,
            ),
            principalId,
        )
    }
}

private fun BranchProtectionRuleInput.toRule(
    repositoryId: UUID,
    id: UUID = UUID.NIL,
) = BranchProtectionRule(
    id = id,
    repositoryId = repositoryId,
    pattern = pattern,
    requirePullRequest = requirePullRequest,
    requiredApprovals = requiredApprovals,
    dismissStaleReviews = dismissStaleReviews,
    requireCodeOwnerReview = requireCodeOwnerReview,
    requireStatusChecks = requireStatusChecks,
    requireLinearHistory = requireLinearHistory,
    allowForcePush = allowForcePush,
    allowDeletion = allowDeletion,
    restrictPushAccess = restrictPushAccess.orEmpty(),
)
