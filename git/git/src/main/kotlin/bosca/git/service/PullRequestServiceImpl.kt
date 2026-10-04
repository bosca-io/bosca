package bosca.git.service

import bosca.git.model.GitHubPullRequestSnapshot
import bosca.db.connection

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.BranchProtectionRule
import bosca.git.model.CreatePullRequestInput
import bosca.git.model.MergeResult
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.PullRequestStatus
import bosca.git.model.Review
import bosca.git.model.ReviewComment
import bosca.git.model.ReviewStatus
import bosca.git.model.SubmitReviewInput
import bosca.git.model.TaskKeyExtractor
import bosca.git.model.TaskPullRequestReference
import bosca.git.model.UpdatePullRequestInput
import bosca.git.model.dispatch
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.PullRequestAssigneeRepository
import bosca.git.repository.PullRequestDependencyRepository
import bosca.git.repository.PullRequestRepository
import bosca.git.repository.PullRequestRepositoryQuery
import bosca.git.repository.ReviewCommentRepository
import bosca.git.repository.ReviewRepository
import bosca.git.repository.TaskPullRequestReferenceRepository
import bosca.lock.DistributedLockFactory
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

/**
 * Orchestrates pull request operations including merge execution with branch
 * protection enforcement, review lifecycle, and task key extraction.
 */
@ServiceImplementation
class PullRequestServiceImpl(
    private val prRepository: PullRequestRepository,
    private val dependencyRepository: PullRequestDependencyRepository,
    private val reviewRepository: ReviewRepository,
    private val reviewCommentRepository: ReviewCommentRepository,
    private val repoRepository: GitRepositoryRepository,
    private val assigneeRepository: PullRequestAssigneeRepository,
    private val branchProtectionService: BranchProtectionService,
    private val dfsManager: BoscaDfsRepositoryManager,
    private val taskPrRefRepository: TaskPullRequestReferenceRepository,
    private val commitStatusService: CommitStatusService,
    private val lockFactory: DistributedLockFactory,
    private val profileService: ProfileService,
    private val securityService: SecurityService,
) : PullRequestService {

    private val log = LoggerFactory.getLogger(PullRequestServiceImpl::class.java)

    override suspend fun synchronize(
        id: UUID, expectedVersion: Long, snapshot: GitHubPullRequestSnapshot,
        mergedAt: OffsetDateTime?, mergedById: UUID?,
    ): PullRequest? {
        val current = prRepository.findById(id) ?: throw NoSuchElementException("Pull request not found: $id")
        if (current.version != expectedVersion) return null
        require(
            current.status != PullRequestStatus.MERGED ||
                    (snapshot.status == PullRequestStatus.MERGED && snapshot.mergeSha == current.mergeSha)
        ) {
            "A completed merge cannot be reverted or rewritten"
        }
        require(snapshot.status != PullRequestStatus.MERGED || (!snapshot.mergeSha.isNullOrBlank() && mergedAt != null)) {
            "A completed merge requires its original commit and timestamp"
        }
        val updated = prRepository.synchronize(
            current.copy(
                title = snapshot.title, description = snapshot.description,
                sourceBranch = snapshot.sourceBranch, targetBranch = snapshot.targetBranch, status = snapshot.status,
                mergeSha = snapshot.mergeSha,
                mergedAt = if (snapshot.status == PullRequestStatus.MERGED) current.mergedAt ?: mergedAt else null,
                mergedBy = if (snapshot.status == PullRequestStatus.MERGED) current.mergedBy ?: mergedById else null,
            )
        ) ?: return null
        extractAndStoreTaskKeys(updated)
        val action = when {
            current.status == updated.status -> PullRequestEventAction.UPDATED
            updated.status == PullRequestStatus.MERGED -> PullRequestEventAction.MERGED
            updated.status == PullRequestStatus.CLOSED -> PullRequestEventAction.CLOSED
            current.status == PullRequestStatus.DRAFT && updated.status == PullRequestStatus.OPEN -> PullRequestEventAction.READY_FOR_REVIEW
            current.status == PullRequestStatus.CLOSED -> PullRequestEventAction.REOPENED
            else -> PullRequestEventAction.UPDATED
        }
        // Only the merge itself identifies who acted. Other observed metadata changes are unattributed.
        dispatchActivity(updated, action, actorId = mergedById.takeIf { action == PullRequestEventAction.MERGED })
        return updated
    }

    override suspend fun create(input: CreatePullRequestInput, authorId: UUID): PullRequest {
        val number = repoRepository.incrementPrNumber(input.repositoryId)
        val status = if (input.isDraft) PullRequestStatus.DRAFT else PullRequestStatus.OPEN
        val pr = prRepository.create(
            PullRequest(
                repositoryId = input.repositoryId,
                number = number,
                title = input.title,
                description = input.description,
                authorId = authorId,
                sourceBranch = input.sourceBranch,
                targetBranch = input.targetBranch,
                sourceRepositoryId = input.sourceRepositoryId,
                status = status
            )
        )
        log.info("Created PR #{} on repository {}", pr.number, pr.repositoryId)
        extractAndStoreTaskKeys(pr)
        dispatchActivity(pr, PullRequestEventAction.OPENED, actorId = authorId)
        return pr
    }

    override suspend fun update(id: UUID, input: UpdatePullRequestInput): PullRequest {
        val existing = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        val updated = prRepository.update(
            existing.copy(
                title = input.title ?: existing.title,
                description = input.description ?: existing.description
            )
        ) ?: throw IllegalStateException("Failed to update PR: $id")
        dispatchActivity(updated, PullRequestEventAction.UPDATED)
        return updated
    }

    override suspend fun findById(id: UUID): PullRequest? = prRepository.findById(id)

    override suspend fun findByNumber(repositoryId: UUID, number: Int): PullRequest? =
        prRepository.findByNumber(repositoryId, number)

    override suspend fun findByRepository(
        repositoryId: UUID,
        status: PullRequestStatus?,
        authorId: UUID?,
        offset: Long,
        limit: Int
    ): List<PullRequest> {
        return when {
            authorId != null -> prRepository.findByAuthor(repositoryId, authorId, offset, limit)
            status != null -> prRepository.findByRepositoryAndStatus(repositoryId, status, offset, limit)
            else -> prRepository.findByRepository(repositoryId, offset, limit)
        }
    }

    override suspend fun findByRepositories(
        repositoryIds: List<UUID>,
        status: PullRequestStatus?,
        authorId: UUID?,
        offset: Long,
        limit: Int
    ): List<PullRequest> {
        if (repositoryIds.isEmpty()) return emptyList()
        return prRepository.findByRepositories(
            PullRequestRepositoryQuery(repositoryIds, status, authorId, offset, limit)
        )
    }

    override suspend fun findOpenBySourceBranch(repositoryId: UUID, sourceBranch: String): List<PullRequest> =
        prRepository.findOpenBySourceBranch(repositoryId, sourceBranch)

    override suspend fun getDependencies(pullRequestId: UUID): List<PullRequest> =
        dependencyRepository.findDependencies(pullRequestId)

    override suspend fun getDependents(pullRequestId: UUID): List<PullRequest> =
        dependencyRepository.findDependents(pullRequestId)

    override suspend fun addDependency(pullRequestId: UUID, dependencyId: UUID): PullRequest {
        require(pullRequestId != dependencyId) { "A pull request cannot depend on itself" }
        val lock = lockFactory.create(DEPENDENCY_GRAPH_LOCK)
        check(lock.acquire(DEPENDENCY_GRAPH_LOCK_TTL_MILLIS, DEPENDENCY_GRAPH_LOCK_WAIT_MILLIS)) {
            "Pull request dependencies are being updated, please retry shortly"
        }
        try {
            val pullRequest = requireMutablePullRequest(pullRequestId)
            prRepository.findById(dependencyId)
                ?: throw NoSuchElementException("Pull request not found: $dependencyId")
            require(dependencyRepository.findDependencies(pullRequestId).none { it.id == dependencyId }) {
                "Pull request already depends on $dependencyId"
            }
            require(!isReachable(dependencyId, pullRequestId)) {
                "Adding this dependency would create a pull request dependency cycle"
            }
            dependencyRepository.add(pullRequestId, dependencyId)
            return pullRequest
        } finally {
            lock.release()
        }
    }

    override suspend fun removeDependency(pullRequestId: UUID, dependencyId: UUID): PullRequest {
        val pullRequest = requireMutablePullRequest(pullRequestId)
        require(dependencyRepository.findDependencies(pullRequestId).any { it.id == dependencyId }) {
            "Pull request does not depend on $dependencyId"
        }
        dependencyRepository.remove(pullRequestId, dependencyId)
        return pullRequest
    }

    override suspend fun getMergePlan(pullRequestId: UUID): List<PullRequest> {
        val root = prRepository.findById(pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: $pullRequestId")
        val ordered = mutableListOf<PullRequest>()
        buildMergePlan(root, mutableSetOf(), mutableSetOf(), ordered)
        return ordered
    }

    override suspend fun merge(
        id: UUID,
        strategy: MergeStrategy,
        mergedById: UUID,
        mergerName: String,
        mergerEmail: String
    ): PullRequest {
        val pr = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        require(pr.status == PullRequestStatus.OPEN) { "Can only merge OPEN pull requests" }
        val unresolvedDependencies = dependencyRepository.findDependencies(id)
            .filter { it.status != PullRequestStatus.MERGED }
        require(unresolvedDependencies.isEmpty()) {
            "Pull request has unresolved dependencies"
        }

        val rule = branchProtectionService.findMatchingRule(pr.repositoryId, pr.targetBranch)
        if (rule != null) {
            enforceProtectionForMerge(pr, rule)
        }

        // A merge inserts objects and advances the target ref, so it must hold
        // the per-repository write lock like a push: a concurrent GC computing
        // reachability without it could drop the just-written merge commit (see
        // RepositoryWriteLock).
        return withRefSynchronizationTransaction(pr.repositoryId, lockFactory) {
            val current = prRepository.lockById(id) ?: throw NoSuchElementException("Pull request not found: $id")
            check(current.version == pr.version) { "Pull request changed before merge; retry with its current state" }
            val merged = withRefSynchronizationLock(pr.repositoryId, lockFactory) { lockHandle ->
                // JGit's merge, object inserts, and ref update are synchronous: keep them off request threads.
                withContext(GitWorkDispatcher) {
                    val dfsRepo = dfsManager.open(pr.repositoryId, connection())
                    lockHandle.fence(dfsRepo)
                    dfsRepo.use { repo ->
                        val sourceRef = repo.refDatabase.findRef("refs/heads/${pr.sourceBranch}")
                            ?: throw IllegalStateException("Source branch '${pr.sourceBranch}' not found")
                        val targetRef = repo.refDatabase.findRef("refs/heads/${pr.targetBranch}")
                            ?: throw IllegalStateException("Target branch '${pr.targetBranch}' not found")

                        val message = when (strategy) {
                            MergeStrategy.SQUASH -> "${pr.title} (#${pr.number})"
                            else -> "Merge pull request #${pr.number} from ${pr.sourceBranch}"
                        }

                        val author = PersonIdent(mergerName, mergerEmail)
                        val mergeResult = MergeExecutor.merge(
                            repo, sourceRef.objectId, targetRef.objectId, strategy, message, author
                        )

                        if (!mergeResult.success) {
                            throw IllegalStateException(
                                "Merge failed: conflicts in ${mergeResult.conflictingFiles.joinToString(", ")}"
                            )
                        }

                        val merged = prRepository.updateMergeState(
                            pr.copy(
                                status = PullRequestStatus.MERGED, mergeStrategy = strategy, mergedBy = mergedById,
                                mergedAt = OffsetDateTime.now(), mergeSha = mergeResult.mergeSha,
                            )
                        ) ?: throw IllegalStateException("Failed to update PR merge state")
                        val refUpdate = repo.refDatabase.newUpdate("refs/heads/${pr.targetBranch}", false)
                        refUpdate.setNewObjectId(ObjectId.fromString(mergeResult.mergeSha))
                        refUpdate.setExpectedOldObjectId(targetRef.objectId)
                        check(
                            refUpdate.update() in setOf(
                                RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE,
                                RefUpdate.Result.FORCED
                            )
                        ) { "Target branch changed during merge" }

                        merged
                    }
                }
            }
            log.info("Merged PR #{} on repository {} via {}", pr.number, pr.repositoryId, strategy)
            dispatchActivity(merged, PullRequestEventAction.MERGED, actorId = mergedById)
            merged
        }
    }

    override suspend fun mergeWithDependencies(
        id: UUID,
        strategy: MergeStrategy,
        mergedById: UUID,
        mergerName: String,
        mergerEmail: String,
        expectedPullRequestIds: List<UUID>
    ): List<PullRequest> {
        val plan = getMergePlan(id)
        require(plan.isNotEmpty() && plan.last().id == id) { "Pull request is already merged" }
        require(plan.map { it.id } == expectedPullRequestIds) {
            "Pull request dependencies changed; review the merge plan and try again"
        }

        for (pullRequest in plan) {
            require(pullRequest.status == PullRequestStatus.OPEN) {
                "Pull request #${pullRequest.number} is ${pullRequest.status.name.lowercase()} and cannot be merged"
            }
            branchProtectionService.findMatchingRule(pullRequest.repositoryId, pullRequest.targetBranch)?.let {
                enforceProtectionForMerge(pullRequest, it)
            }
            val mergeability = checkMergeability(pullRequest.id)
            require(mergeability.success) {
                val conflicts = mergeability.conflictingFiles.joinToString(", ")
                if (conflicts.isEmpty()) {
                    "Pull request #${pullRequest.number} cannot be merged"
                } else {
                    "Pull request #${pullRequest.number} has conflicts in $conflicts"
                }
            }
        }

        val merged = mutableListOf<PullRequest>()
        for (planned in plan) {
            val current = prRepository.findById(planned.id)
                ?: throw NoSuchElementException("Pull request not found: ${planned.id}")
            if (current.status == PullRequestStatus.MERGED) continue
            merged += merge(current.id, strategy, mergedById, mergerName, mergerEmail)
        }
        return merged
    }

    override suspend fun checkMergeability(id: UUID): MergeResult {
        val pr = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")

        // JGit's in-memory merge is synchronous: keep it off request threads.
        return withContext(GitWorkDispatcher) {
            dfsManager.open(pr.repositoryId).use { repo ->
                val sourceRef = repo.refDatabase.findRef("refs/heads/${pr.sourceBranch}")
                    ?: return@use MergeResult(success = false)
                val targetRef = repo.refDatabase.findRef("refs/heads/${pr.targetBranch}")
                    ?: return@use MergeResult(success = false)

                MergeExecutor.checkMergeability(repo, sourceRef.objectId, targetRef.objectId)
            }
        }
    }

    override suspend fun close(id: UUID): PullRequest {
        val pr = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        require(pr.status == PullRequestStatus.OPEN || pr.status == PullRequestStatus.DRAFT) {
            "Can only close OPEN or DRAFT pull requests"
        }
        val closed = prRepository.updateStatus(id, PullRequestStatus.CLOSED, pr.version)
            ?: throw IllegalStateException("Failed to close PR")
        dispatchActivity(closed, PullRequestEventAction.CLOSED)
        return closed
    }

    override suspend fun reopen(id: UUID): PullRequest {
        val pr = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        require(pr.status == PullRequestStatus.CLOSED) { "Can only reopen CLOSED pull requests" }
        val reopened = prRepository.updateStatus(id, PullRequestStatus.OPEN, pr.version)
            ?: throw IllegalStateException("Failed to reopen PR")
        dispatchActivity(reopened, PullRequestEventAction.REOPENED)
        return reopened
    }

    override suspend fun markReady(id: UUID): PullRequest {
        val pr = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        require(pr.status == PullRequestStatus.DRAFT) { "Can only mark DRAFT pull requests as ready" }
        val ready = prRepository.updateStatus(id, PullRequestStatus.OPEN, pr.version)
            ?: throw IllegalStateException("Failed to mark PR ready")
        dispatchActivity(ready, PullRequestEventAction.READY_FOR_REVIEW)
        return ready
    }

    override suspend fun submitReview(input: SubmitReviewInput, reviewerId: UUID): Review {
        val pr = prRepository.findById(input.pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: ${input.pullRequestId}")
        require(pr.status == PullRequestStatus.OPEN) { "Can only review OPEN pull requests" }

        val review = reviewRepository.create(
            Review(
                pullRequestId = input.pullRequestId,
                reviewerId = reviewerId,
                status = input.status,
                body = input.body
            )
        )
        dispatchActivity(
            pr,
            PullRequestEventAction.REVIEWED,
            actorId = reviewerId,
            body = review.body,
            reviewStatus = review.status,
        )
        return review
    }

    override suspend fun getReviews(pullRequestId: UUID): List<Review> {
        return reviewRepository.findByPullRequest(pullRequestId)
    }

    override suspend fun addReviewComment(
        reviewId: UUID,
        pullRequestId: UUID,
        authorId: UUID,
        filePath: String,
        oldLineNumber: Int?,
        newLineNumber: Int?,
        commitSha: String,
        content: String
    ): ReviewComment {
        val pr = prRepository.findById(pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: $pullRequestId")
        val comment = reviewCommentRepository.create(
            ReviewComment(
                reviewId = reviewId,
                pullRequestId = pullRequestId,
                authorId = authorId,
                filePath = filePath,
                oldLineNumber = oldLineNumber,
                newLineNumber = newLineNumber,
                commitSha = commitSha,
                content = content
            )
        )
        dispatchActivity(
            pr,
            PullRequestEventAction.COMMENTED,
            actorId = authorId,
            body = comment.content,
            filePath = comment.filePath,
            lineNumber = comment.newLineNumber ?: comment.oldLineNumber,
        )
        return comment
    }

    override suspend fun getCommentsForPullRequest(pullRequestId: UUID): List<ReviewComment> {
        return reviewCommentRepository.findByPullRequest(pullRequestId)
    }

    override suspend fun resolveThread(pullRequestId: UUID, filePath: String, lineNumber: Int) {
        val pr = prRepository.findById(pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: $pullRequestId")
        reviewCommentRepository.resolveThread(pullRequestId, filePath, lineNumber)
        dispatchActivity(
            pr,
            PullRequestEventAction.THREAD_RESOLVED,
            filePath = filePath,
            lineNumber = lineNumber,
        )
    }

    override suspend fun onSourceBranchPushed(pullRequestId: UUID, newSourceSha: String) {
        val pr = prRepository.findById(pullRequestId) ?: return
        if (pr.status != PullRequestStatus.OPEN) return

        reviewCommentRepository.markOutdatedByPullRequest(pullRequestId, newSourceSha)

        val rule = branchProtectionService.findMatchingRule(pr.repositoryId, pr.targetBranch)
        if (rule != null && rule.dismissStaleReviews) {
            reviewRepository.dismissByPullRequest(pullRequestId, "source_updated")
            log.info("Dismissed stale reviews on PR #{} after source branch push", pr.number)
        }
        dispatchActivity(pr, PullRequestEventAction.SOURCE_UPDATED)
    }

    override suspend fun getAssignees(pullRequestId: UUID): List<UUID> {
        return assigneeRepository.findByPullRequest(pullRequestId)
    }

    override suspend fun addAssignee(pullRequestId: UUID, profileId: UUID) {
        val pr = prRepository.findById(pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: $pullRequestId")
        assigneeRepository.add(pullRequestId, profileId)
        dispatchActivity(pr, PullRequestEventAction.ASSIGNED, assigneeId = profileId, extraRecipients = setOf(profileId))
    }

    override suspend fun removeAssignee(pullRequestId: UUID, profileId: UUID) {
        val pr = prRepository.findById(pullRequestId)
            ?: throw NoSuchElementException("Pull request not found: $pullRequestId")
        assigneeRepository.remove(pullRequestId, profileId)
        dispatchActivity(pr, PullRequestEventAction.UNASSIGNED, assigneeId = profileId, extraRecipients = setOf(profileId))
    }

    private suspend fun extractAndStoreTaskKeys(pr: PullRequest) {
        val taskKeys = TaskKeyExtractor.extractFromAll(
            pr.title,
            pr.description ?: "",
            pr.sourceBranch
        )
        for (taskKey in taskKeys) {
            try {
                taskPrRefRepository.create(
                    TaskPullRequestReference(
                        repositoryId = pr.repositoryId,
                        taskKey = taskKey,
                        pullRequestId = pr.id,
                        pullRequestNumber = pr.number
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Failed to create task PR reference for {}: {}", taskKey, e.message)
            }
        }
    }

    /**
     * Publishes one self-contained event after a successful pull-request mutation. Candidate
     * identities are normalized to profile ids because legacy PR rows stored principal ids while
     * assignees and message delivery use profile ids.
     */
    private suspend fun dispatchActivity(
        pr: PullRequest,
        action: PullRequestEventAction,
        actorId: UUID? = null,
        body: String? = null,
        filePath: String? = null,
        lineNumber: Int? = null,
        reviewStatus: ReviewStatus? = null,
        assigneeId: UUID? = null,
        extraRecipients: Set<UUID> = emptySet(),
    ) {
        val repository = repoRepository.findById(pr.repositoryId)
        val candidates = buildSet {
            add(pr.authorId)
            repository?.ownerId?.let(::add)
            addAll(assigneeRepository.findByPullRequest(pr.id))
            addAll(reviewRepository.findByPullRequest(pr.id).map { it.reviewerId })
            addAll(extraRecipients)
        }
        val recipients = candidates.mapNotNullTo(linkedSetOf()) { deliveryProfile(it)?.id }
        val authorProfile = deliveryProfile(pr.authorId)
        val actorProfile = actorId?.let { deliveryProfile(it) }
        PullRequestEvent(
            repositoryId = pr.repositoryId,
            repositoryName = repository?.name ?: "Repository",
            pullRequestId = pr.id,
            number = pr.number,
            action = action,
            title = pr.title,
            sourceBranch = pr.sourceBranch,
            targetBranch = pr.targetBranch,
            authorId = authorProfile?.id ?: pr.authorId,
            recipientIds = recipients,
            actorId = actorProfile?.id,
            actorName = actorProfile?.name,
            taskKeys = TaskKeyExtractor.extractFromAll(pr.title, pr.description.orEmpty(), pr.sourceBranch),
            body = body,
            filePath = filePath,
            lineNumber = lineNumber,
            reviewStatus = reviewStatus,
            assigneeId = assigneeId,
        ).dispatch()
    }

    /** Resolves either a profile id or a legacy principal id to the primary deliverable profile. */
    private suspend fun deliveryProfile(id: UUID): Profile? {
        return try {
            profileService.getAllByIds(listOf(id)).firstOrNull()
                ?: securityService.getPrincipalById(id)?.let { profileService.getPrimaryProfile(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not resolve Git notification recipient {} to a profile", id, e)
            null
        }
    }

    override suspend fun verifyMergeAllowed(id: UUID, expectedVersion: Long, sourceSha: String) {
        val pr = prRepository.lockById(id) ?: throw NoSuchElementException("Pull request not found: $id")
        check(pr.version == expectedVersion) { "Pull request changed before merge; retry with its current state" }
        require(pr.status == PullRequestStatus.OPEN) { "Can only merge OPEN pull requests" }
        require(dependencyRepository.findDependencies(id).all { it.status == PullRequestStatus.MERGED }) { "Pull request has unresolved dependencies" }
        val head = withContext(GitWorkDispatcher) {
            dfsManager.open(pr.repositoryId).use { it.resolve("refs/heads/${pr.sourceBranch}")?.name() }
        }
        require(head == sourceSha) { "The GitHub merge source differs from the reviewed Bosca branch" }
        branchProtectionService.findMatchingRule(pr.repositoryId, pr.targetBranch)?.let { rule ->
            require(!rule.requireCodeOwnerReview) { "Code-owner approval cannot be verified for an imported merge" }
            enforceProtectionForMerge(pr, rule)
        }
    }

    private suspend fun enforceProtectionForMerge(pr: PullRequest, rule: BranchProtectionRule) {
        if (rule.requiredApprovals > 0) {
            val approvals = reviewRepository.findApprovedByPullRequest(pr.id)
            require(approvals.size >= rule.requiredApprovals) {
                "Requires ${rule.requiredApprovals} approvals, has ${approvals.size}"
            }
        }

        val changesRequested = reviewRepository.findLatestChangesRequested(pr.id)
        if (changesRequested != null) {
            val laterApproval = reviewRepository.findApprovedByPullRequest(pr.id)
                .any { it.reviewerId == changesRequested.reviewerId && it.created > changesRequested.created }
            require(laterApproval) {
                "Changes requested by reviewer ${changesRequested.reviewerId} have not been resolved"
            }
        }

        if (rule.requireStatusChecks.isNotEmpty()) {
            val headSha = withContext(GitWorkDispatcher) {
                dfsManager.open(pr.repositoryId).use { repo ->
                    repo.refDatabase.findRef("refs/heads/${pr.sourceBranch}")?.objectId?.name()
                }
            } ?: throw IllegalStateException("Source branch '${pr.sourceBranch}' not found")
            val passing = commitStatusService.areRequiredChecksPassing(
                pr.repositoryId, headSha, rule.requireStatusChecks
            )
            require(passing) {
                "Required CI status checks are not passing: ${rule.requireStatusChecks.joinToString(", ")}"
            }
        }
    }

    private suspend fun requireMutablePullRequest(id: UUID): PullRequest {
        val pullRequest = prRepository.findById(id)
            ?: throw NoSuchElementException("Pull request not found: $id")
        require(pullRequest.status == PullRequestStatus.OPEN || pullRequest.status == PullRequestStatus.DRAFT) {
            "Dependencies can only be changed on open or draft pull requests"
        }
        return pullRequest
    }

    private suspend fun isReachable(startId: UUID, targetId: UUID): Boolean {
        val pending = ArrayDeque<UUID>()
        val visited = mutableSetOf<UUID>()
        pending.add(startId)
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (id == targetId) return true
            if (!visited.add(id)) continue
            dependencyRepository.findDependencies(id).forEach { pending.add(it.id) }
        }
        return false
    }

    private suspend fun buildMergePlan(
        pullRequest: PullRequest,
        visiting: MutableSet<UUID>,
        visited: MutableSet<UUID>,
        ordered: MutableList<PullRequest>
    ) {
        if (pullRequest.status == PullRequestStatus.MERGED || pullRequest.id in visited) return
        check(visiting.add(pullRequest.id)) { "Pull request dependency cycle detected at ${pullRequest.id}" }
        for (dependency in dependencyRepository.findDependencies(pullRequest.id)) {
            buildMergePlan(dependency, visiting, visited, ordered)
        }
        visiting.remove(pullRequest.id)
        visited.add(pullRequest.id)
        ordered.add(pullRequest)
    }

    private companion object {
        const val DEPENDENCY_GRAPH_LOCK = "git-pull-request-dependencies"
        const val DEPENDENCY_GRAPH_LOCK_TTL_MILLIS = 30_000L
        const val DEPENDENCY_GRAPH_LOCK_WAIT_MILLIS = 30_000L
    }
}
