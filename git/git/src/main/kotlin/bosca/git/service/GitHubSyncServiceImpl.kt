package bosca.git.service

import bosca.profile.profile.service.ProfileService
import bosca.git.model.GitHubPullRequestDelivery
import bosca.git.model.GitHubPullRequest
import bosca.git.model.GitHubPullRequestState
import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.PullRequestStatus

import bosca.git.model.GitHubUser
import bosca.lock.DistributedLockFactory
import bosca.db.withConnectionManager
import bosca.db.transaction
import bosca.git.model.GitHubSynchronizationFailed
import bosca.git.github.GitHubClient
import bosca.git.model.GitHubPush
import bosca.git.model.GitHubRefState
import bosca.git.model.GitHubRefResolution
import bosca.git.model.GitHubRefResolutionInput
import bosca.git.model.GitHubSyncResult
import bosca.git.model.RefUpdateEvent
import bosca.git.model.GitHubDelivery
import bosca.git.model.dispatch
import bosca.git.model.GitHubDeliveryConflictException
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.model.GitHubWebhookPayload
import bosca.git.model.GitHubWebhookRejectedException
import bosca.git.model.GitHubWebhookInputException
import bosca.git.model.GitHubWebhookUnavailableException
import bosca.git.repository.GitHubSyncRepository
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.SecurityService
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityException
import bosca.security.service.impersonate
import bosca.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

@ServiceImplementation
class GitHubSyncServiceImpl(
    private val repository: GitHubSyncRepository,
    private val repositoryService: RepositoryService,
    private val secrets: PipelineSecretService,
    private val securityService: SecurityService,
    private val writes: RepositoryWriteService,
    private val github: GitHubClient,
    private val locks: DistributedLockFactory,
    private val pullRequests: PullRequestService,
    private val profiles: ProfileService,
    private val permissions: RepositoryPermissionEvaluator,
    private val protections: BranchProtectionService,
) : GitHubSyncService {
    private val log = LoggerFactory.getLogger(GitHubSyncServiceImpl::class.java)

    private fun pullRequestSynchronization(pair: GitHubRepositoryPair) = GitHubPullRequestSynchronization(
        repository, pullRequests, profiles, securityService, github,
        verifyImport = { remote -> authorizedPullRequestPrincipal(pair, remote) != null },
        synchronizeBranches = { branches, direction, remote -> synchronizePullRequestBranches(pair, branches, direction, remote) },
    )

    private suspend fun synchronizePullRequestBranches(
        pair: GitHubRepositoryPair, branches: List<String>, direction: RefSynchronizationDirection, remote: GitHubPullRequest?,
    ): Boolean {
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        val comparisons = writes.compareRefs(pair.repositoryId, url, token).associateBy { it.ref }
        for (branch in branches.distinct()) {
            val ref = "refs/heads/$branch"
            val current = comparisons[ref]
            val after = if (direction == RefSynchronizationDirection.INBOUND) current?.remoteSha else current?.localSha
            // A deleted source branch is not recreated merely to mirror a historical PR.
            if (after == null) {
                if (branch == branches.last()) return false
                continue
            }
            val baseline = repository.findRefState(pair.repositoryId, ref)
            val target = if (direction == RefSynchronizationDirection.INBOUND) current?.localSha else current?.remoteSha
            // Only the other host moved or deleted this branch. Its own push or reconciliation transfers it in that direction.
            if (baseline != null && baseline.synchronized && !baseline.conflict && after == baseline.sha && target != baseline.sha) continue
            val result = if (direction == RefSynchronizationDirection.INBOUND) {
                if (after == target) {
                    val principal = remote?.let { authorizedPullRequestPrincipal(pair, it) } ?: return false
                    synchronize(pair, ref, baseline?.sha, after, direction, principal, url, token)
                } else if (remote?.merged == true && branch == remote.base.ref) {
                    if (after != remote.mergeSha) return false
                    val principal = authorizedPullRequestPrincipal(pair, remote) ?: return false
                    synchronize(pair, ref, baseline?.sha, after, direction, principal, url, token, pullRequestMerge = true)
                } else {
                    // A metadata editor cannot authorize different commits observed while reading the PR.
                    val delivery = repository.findPushDelivery(pair.repositoryId, ref, after)
                        ?: return false
                    synchronizeVerifiedPush(pair, delivery)
                }
            } else synchronize(pair, ref, baseline?.sha, after, direction, null, url, token)
            if (result !in setOf(GitHubSyncResult.APPLIED, GitHubSyncResult.UNCHANGED)) return false
        }
        return true
    }

    private suspend fun canEdit(pair: GitHubRepositoryPair, principalId: UUID?): Boolean {
        val principal = principalId?.let { securityService.getPrincipalById(it) }?.takeIf { it.deletedAt == null } ?: return false
        val hosted = repositoryService.findById(pair.repositoryId) ?: return false
        return permissions.isAllowed(securityService.impersonate(principal.id), hosted, PermissionAction.EDIT)
    }

    private suspend fun authorizedPullRequestPrincipal(pair: GitHubRepositoryPair, remote: GitHubPullRequest): UUID? {
        val delivery = repository.findPullRequestDelivery(pair.repositoryId, remote.number) ?: return null
        val observed = json.decodeFromJsonElement(GitHubPullRequestDelivery.serializer(), delivery.payload)
        if (observed.number != remote.number || observed.pullRequest != remote || !canEdit(pair, delivery.principalId)) return null
        if (remote.merged && remote.mergedBy?.takeIf { it.type == "User" }?.let { repository.findUser(it.id)?.principalId } != delivery.principalId) return null
        return delivery.principalId
    }

    override suspend fun synchronizePullRequest(delivery: GitHubDelivery): GitHubSyncResult = trackDelivery(delivery, "pull_request") {
        withPair(delivery.repositoryId, GitHubSyncResult.IGNORED) { pair ->
            val verified = repository.findDelivery(delivery.deliveryId)
                ?: throw NoSuchElementException("Verified GitHub delivery not found")
            require(verified.repositoryId == pair.repositoryId) { "Delivery belongs to another repository" }
            if (!verified.ignored && verified.event == "pull_request") {
                val payload = json.decodeFromJsonElement(GitHubPullRequestDelivery.serializer(), verified.payload)
                repository.findPullRequestState(pair.repositoryId, payload.number)?.let { preparePullRequest(it) }
            }
            GitHubSyncResult.UNCHANGED
        }
        withPair(delivery.repositoryId, GitHubSyncResult.IGNORED) { pair ->
            val verified = repository.findDelivery(delivery.deliveryId)
                ?: throw NoSuchElementException("Verified GitHub delivery not found")
            require(verified.repositoryId == pair.repositoryId) { "Delivery belongs to another repository" }
            if (verified.ignored || verified.event != "pull_request") return@withPair GitHubSyncResult.IGNORED
            val payload = json.decodeFromJsonElement(GitHubPullRequestDelivery.serializer(), verified.payload)
            pullRequestSynchronization(pair).import(pair, token(pair), payload.number)
        }
    }

    private suspend fun preparePullRequest(state: GitHubPullRequestState) {
        val current = state.pullRequestId?.let { pullRequests.findById(it) } ?: return
        val snapshot = GitHubPullRequestSynchronization.snapshot(current)
        val pending = state.pending
        // Ref transfers use current history. A committed merge supersedes an earlier open/draft intention.
        val intended = when {
            pending == null -> snapshot
            snapshot.status == PullRequestStatus.MERGED && pending.status != snapshot.status ->
                pending.copy(status = snapshot.status, mergeSha = snapshot.mergeSha)
            else -> pending
        }
        if (intended != pending) repository.savePullRequestState(state.copy(pending = intended))
    }

    override suspend fun synchronizePullRequest(event: PullRequestEvent): GitHubSyncResult {
        var result = GitHubSyncResult.UNCHANGED
        // A round writes only the intention committed before it. A Bosca edit after that commit needs another round.
        repeat(MAX_EXPORT_ROUNDS) {
            val (round, behind) = exportPullRequest(event)
            result = if (round == GitHubSyncResult.UNCHANGED && result == GitHubSyncResult.APPLIED) result else round
            if (!behind) return result
        }
        return result
    }

    /** Returns the round's result and whether Bosca still differs from the newly agreed snapshot. */
    private suspend fun exportPullRequest(event: PullRequestEvent): Pair<GitHubSyncResult, Boolean> {
        var behind = false
        // Commit a reservation before an external create; a lost response can recover the same counterpart.
        withPair(event.repositoryId, GitHubSyncResult.IGNORED) { pair ->
            val current = pullRequests.findById(event.pullRequestId) ?: return@withPair GitHubSyncResult.IGNORED
            require(current.repositoryId == pair.repositoryId) { "Pull request belongs to another repository" }
            if (current.sourceRepositoryId != null && current.sourceRepositoryId != current.repositoryId) return@withPair GitHubSyncResult.IGNORED
            val state = repository.findPullRequestState(pair.repositoryId, current.id)
            if (state == null) {
                // Completed Bosca history is not exported. Only PRs tracked while active continue after completion.
                if (current.status == PullRequestStatus.MERGED || current.status == PullRequestStatus.CLOSED) {
                    return@withPair GitHubSyncResult.IGNORED
                }
                val snapshot = GitHubPullRequestSynchronization.snapshot(current)
                repository.savePullRequestState(GitHubPullRequestState(repositoryId = pair.repositoryId,
                    pullRequestId = current.id, snapshot = snapshot, boscaSnapshot = snapshot, pending = snapshot))
            } else preparePullRequest(state)
            GitHubSyncResult.UNCHANGED
        }
        val result = withPair(event.repositoryId, GitHubSyncResult.IGNORED) { pair ->
            pullRequestSynchronization(pair).export(pair, token(pair), event.pullRequestId).also { exported ->
                if (exported == GitHubSyncResult.APPLIED) {
                    val state = repository.findPullRequestState(pair.repositoryId, event.pullRequestId)
                    val current = pullRequests.findById(event.pullRequestId)
                    behind = state != null && current != null && state.problem == null &&
                        state.snapshot != GitHubPullRequestSynchronization.snapshot(current)
                }
            }
        }
        return result to behind
    }

    override suspend fun findPullRequestStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubPullRequestState> {
        validatePage(offset, limit)
        return repository.findPullRequestStates(repositoryId, offset, limit)
    }

    override suspend fun reconcilePullRequests(repositoryId: UUID?): List<GitHubPullRequestState> {
        val pairs = if (repositoryId == null) repository.findEnabledPairs() else listOfNotNull(repository.findPair(repositoryId))
        val result = mutableListOf<GitHubPullRequestState>()
        val failures = mutableListOf<Exception>()
        suspend fun recover(repositoryId: UUID, block: suspend () -> Unit) {
            try { block()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                log.error("GitHub pull request reconciliation failed for repository {}", repositoryId, e)
                failures += e
            }
        }
        for (pair in pairs) {
            try {
                if (availablePair(pair) == null) continue
                var page = 1
                while (true) {
                    val remote = github.listPullRequests(pair, token(pair), page++)
                    if (remote.isEmpty()) break
                    for (pr in remote) recover(pair.repositoryId) {
                        val tracked = withPair(pair.repositoryId, false) {
                            val state = repository.findPullRequestState(pair.repositoryId, pr.number)
                            state?.let { preparePullRequest(it) }
                            state?.pullRequestId != null
                        }
                        // Only open PRs are imported. Paired PRs keep synchronizing in every state.
                        if (pr.state != "open" && !tracked) return@recover
                        withPair(pair.repositoryId, GitHubSyncResult.IGNORED) { locked ->
                            pullRequestSynchronization(locked).import(locked, token(locked), pr.number)
                        }
                    }
                }
                var offset = 0L
                while (true) {
                    val native = pullRequests.findByRepository(pair.repositoryId, offset = offset, limit = 100)
                    if (native.isEmpty()) break
                    offset += native.size
                    for (pr in native) recover(pair.repositoryId) {
                        synchronizePullRequest(PullRequestEvent(
                            pr.repositoryId, pr.id, pr.number, PullRequestEventAction.UPDATED,
                            pr.title, pr.sourceBranch, pr.targetBranch, pr.authorId,
                        ))
                    }
                }
                offset = 0L
                while (true) {
                    val states = repository.findPullRequestStates(pair.repositoryId, offset, 100)
                    result += states
                    if (states.size < 100) break
                    offset += states.size
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                log.error("GitHub pull request reconciliation failed for repository {}", pair.repositoryId, e)
                failures += e
            }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("GitHub pull request reconciliation failed", failures.first()).also {
            failures.drop(1).forEach(it::addSuppressed)
        }
        return result
    }

    override suspend fun findPair(repositoryId: UUID): GitHubRepositoryPair? = repository.findPair(repositoryId)

    override suspend fun savePair(input: GitHubRepositoryPairInput): GitHubRepositoryPair {
        require(input.githubRepositoryId?.let { it > 0 } != false && input.version >= 0) { "Invalid GitHub repository ID or version" }
        require(input.owner.matches(SEGMENT) && input.name.matches(SEGMENT)) { "Invalid GitHub owner or repository name" }
        require(input.webhookSecretName.isNotBlank() && input.tokenSecretName.isNotBlank()) { "Secret names are required" }
        val hosted = repositoryService.findById(input.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${input.repositoryId}")
        require(!input.enabled || (!hosted.deleted && !hosted.archived)) { "Repository is unavailable for synchronization" }
        val current = repository.findPair(input.repositoryId)
        if (current == null) require(input.version == 0L) { "New pair must have version zero" }
        if (input.enabled) {
            require(!secrets.resolve(input.webhookSecretName).isNullOrBlank()) { "Webhook secret is not configured" }
            require(!secrets.resolve(input.tokenSecretName).isNullOrBlank()) { "GitHub token is not configured" }
        }
        val githubRepositoryId = input.githubRepositoryId ?: if (current != null && current.owner == input.owner && current.name == input.name) {
            current.githubRepositoryId
        } else {
            val token = secrets.resolve(input.tokenSecretName)
            require(!token.isNullOrBlank()) { "GitHub token is not configured" }
            github.repositoryId(input.owner, input.name, token)
        }
        require(current == null || current.githubRepositoryId == githubRepositoryId) { "A repository pair cannot change GitHub repository ID" }
        val pair = GitHubRepositoryPair(
            repositoryId = input.repositoryId, githubRepositoryId = githubRepositoryId,
            owner = input.owner, name = input.name, webhookSecretName = input.webhookSecretName,
            tokenSecretName = input.tokenSecretName, enabled = input.enabled, version = input.version,
        )
        if (current == null) {
            return repository.createPair(pair)
        }
        return repository.updatePair(pair) ?: error("Repository pair changed concurrently")
    }

    override suspend fun findUsers(offset: Long, limit: Int): List<GitHubUser> {
        validatePage(offset, limit)
        return repository.findUsers(offset, limit)
    }

    override suspend fun mapUser(githubUserId: Long, principalId: UUID): GitHubUser {
        require(githubUserId > 0) { "Invalid GitHub user ID" }
        val principal = securityService.getPrincipalById(principalId)
            ?: throw NoSuchElementException("Principal not found: $principalId")
        require(principal.deletedAt == null) { "Principal is deleted" }
        return repository.mapUser(githubUserId, principalId)
    }

    override suspend fun mapUserByUsername(username: String, principalId: UUID): GitHubUser = withConnectionManager {
        val login = username.trim().removePrefix("@")
        val id = github.humanUserId(login)
        transaction {
            mapUser(id, principalId)
            repository.setUsername(id, login)
        }
    }

    override suspend fun deliveryImportProblem(delivery: GitHubDelivery): String? {
        if (delivery.ignored || (delivery.event == "push" && repository.findPushResult(delivery.deliveryId) != null)) return null
        val principalId = delivery.principalId ?: return "No Bosca user was mapped when this delivery arrived. " +
            "Add a GitHub user mapping for future deliveries. For existing branches and tags, use Pull from GitHub; redelivery preserves the original unmapped identity."
        val principal = securityService.getPrincipalById(principalId)?.takeIf { it.deletedAt == null }
            ?: return "The mapped Bosca user is no longer available. Update the GitHub user mapping and send a new delivery."
        val hosted = repositoryService.findById(delivery.repositoryId) ?: return "The Bosca repository is no longer available."
        if (!permissions.isAllowed(securityService.impersonate(principal.id), hosted, PermissionAction.EDIT)) {
            return "The mapped Bosca user does not have repository Edit permission. " +
                "In Repository Settings → Permissions, grant Edit to a group the user belongs to. The existing delivery can then be retried."
        }
        return null
    }

    /** Keeps delivery diagnostics and one notification outside a rolled-back ref import. */
    private suspend fun trackDelivery(delivery: GitHubDelivery, event: String, block: suspend () -> GitHubSyncResult): GitHubSyncResult = withConnectionManager {
        try {
            val result = block()
            if (result != GitHubSyncResult.IGNORED) repository.setDeliveryProblem(delivery.deliveryId, null)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                withConnectionManager {
                    transaction {
                        val verified = repository.findDelivery(delivery.deliveryId)
                            ?.takeIf { it.repositoryId == delivery.repositoryId && it.event == event && !it.ignored }
                        if (verified != null) {
                            val problem = (if (e is SecurityException) deliveryImportProblem(verified) else null)
                                ?: e.message ?: e.javaClass.simpleName
                            repository.setDeliveryProblem(verified.deliveryId, problem)
                            val firstFailure = repository.reserveFailureNotification(verified.deliveryId)
                            if (firstFailure != null) {
                                val hosted = repositoryService.findById(verified.repositoryId)
                                    ?: throw NoSuchElementException("Repository not found")
                                GitHubSynchronizationFailed(
                                    repositoryId = hosted.id, repositoryName = hosted.name,
                                    deliveryId = verified.deliveryId, event = verified.event, problem = problem,
                                    recipientIds = setOf(hosted.ownerId),
                                ).dispatch()
                            }
                        }
                    }
                }
            } catch (notificationFailure: CancellationException) {
                throw notificationFailure
            } catch (notificationFailure: Exception) {
                e.addSuppressed(notificationFailure)
            }
            throw e
        }
    }

    override suspend fun unmapUser(githubUserId: Long) {
        require(githubUserId > 0) { "Invalid GitHub user ID" }
        repository.unmapUser(githubUserId)
    }

    override suspend fun onDelivery(
        repositoryId: UUID, deliveryId: String, event: String, signature: String?, body: ByteArray,
    ): GitHubDelivery {
        // Delivery headers are not covered by the HMAC. Bind them to the persisted occurrence too.
        if (!deliveryId.matches(DELIVERY_ID) || !event.matches(EVENT_NAME)) {
            throw GitHubWebhookInputException("Invalid GitHub delivery headers")
        }
        val pair = repository.findPair(repositoryId)?.takeIf { it.enabled }
            ?: throw GitHubWebhookRejectedException()
        val hosted = repositoryService.findById(repositoryId)
        if (hosted == null || hosted.deleted || hosted.archived) throw GitHubWebhookRejectedException()
        val secret = secrets.resolve(pair.webhookSecretName)?.takeIf { it.isNotBlank() }
            ?: throw GitHubWebhookUnavailableException()
        if (!verifySignature(secret, signature, body)) throw GitHubWebhookRejectedException()
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw GitHubWebhookInputException("GitHub payload is not UTF-8")
        }
        val (payload, envelope) = try {
            val payload = json.parseToJsonElement(text)
            payload to json.decodeFromJsonElement(GitHubWebhookPayload.serializer(), payload)
        } catch (e: SerializationException) {
            throw GitHubWebhookInputException("Invalid GitHub payload", e)
        }
        if (envelope.repository.id != pair.githubRepositoryId) throw GitHubWebhookRejectedException()
        val user = envelope.sender?.takeIf { it.id > 0 && it.type == "User" }
        val fork = event.startsWith("pull_request") &&
            envelope.pullRequest?.head?.repo?.id != pair.githubRepositoryId
        val delivery = GitHubDelivery(
            deliveryId = deliveryId.lowercase(), repositoryId = repositoryId, event = event,
            payload = payload, payloadDigest = hex(MessageDigest.getInstance("SHA-256").digest(body)),
            githubUserId = envelope.sender?.id,
            principalId = user?.let { repository.findUser(it.id)?.principalId },
            ignored = event !in SUPPORTED_EVENTS || fork,
        )
        val accepted = repository.createDelivery(delivery)
            ?: repository.findDelivery(delivery.deliveryId) ?: error("Conflicting delivery was not found")
        if (accepted.repositoryId != repositoryId || accepted.event != event || accepted.payloadDigest != delivery.payloadDigest) {
            throw GitHubDeliveryConflictException()
        }
        if (!accepted.ignored) accepted.dispatch()
        return accepted
    }

    override suspend fun findDeliveries(repositoryId: UUID, offset: Long, limit: Int): List<GitHubDelivery> {
        validatePage(offset, limit)
        return repository.findDeliveries(repositoryId, offset, limit)
    }

    override suspend fun synchronizePush(delivery: GitHubDelivery): GitHubSyncResult = trackDelivery(delivery, "push") {
        withPair(delivery.repositoryId, GitHubSyncResult.IGNORED) { pair ->
            // Use persisted signed input, including its original user mapping, rather than caller-supplied attribution.
            val verified = repository.findDelivery(delivery.deliveryId)
                ?: throw NoSuchElementException("Verified GitHub delivery not found")
            synchronizeVerifiedPush(pair, verified)
        }
    }

    /** The owning pair transaction is already locked, including during PR and ref recovery. */
    private suspend fun synchronizeVerifiedPush(pair: GitHubRepositoryPair, verified: GitHubDelivery): GitHubSyncResult {
        require(verified.repositoryId == pair.repositoryId) { "Delivery belongs to another repository" }
        if (verified.ignored || verified.event != "push") return GitHubSyncResult.IGNORED
        repository.findPushResult(verified.deliveryId)?.let { return GitHubSyncResult.valueOf(it) }
        val push = json.decodeFromJsonElement(GitHubPush.serializer(), verified.payload)
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        if (!canEdit(pair, verified.principalId)) {
            val current = writes.compareRefs(pair.repositoryId, url, token).firstOrNull { it.ref == push.ref }
            val after = push.after.nonZeroSha()
            if (current?.localSha == after && current?.remoteSha == after) {
                // An already converged echo neither writes a ref nor grants build attribution.
                repository.savePushResult(verified.deliveryId, GitHubSyncResult.UNCHANGED.name)
                return GitHubSyncResult.UNCHANGED
            }
            throw SecurityException("The originating GitHub user requires Bosca repository EDIT permission")
        }
        val result = synchronize(pair, push.ref, push.before.nonZeroSha(), push.after.nonZeroSha(),
            RefSynchronizationDirection.INBOUND, verified.principalId, url, token, verifiedPush = true)
        repository.savePushResult(verified.deliveryId, result.name)
        return result
    }

    override suspend fun synchronizeRef(event: RefUpdateEvent): GitHubSyncResult = withPair(event.repositoryId, GitHubSyncResult.IGNORED) { pair ->
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        synchronize(pair, event.ref, event.beforeSha, event.afterSha, RefSynchronizationDirection.OUTBOUND, null, url, token)
    }

    override suspend fun findRefStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubRefState> {
        validatePage(offset, limit)
        return repository.findRefStates(repositoryId, offset, limit)
    }

    override suspend fun pullRefs(repositoryId: UUID, principalId: UUID): List<GitHubRefState> =
        transferRefs(repositoryId, principalId, RefSynchronizationDirection.INBOUND)

    override suspend fun pushRefs(repositoryId: UUID, principalId: UUID): List<GitHubRefState> =
        transferRefs(repositoryId, principalId, RefSynchronizationDirection.OUTBOUND)

    override suspend fun resolveRef(input: GitHubRefResolutionInput, principalId: UUID): GitHubRefState = withConnectionManager {
        require(org.eclipse.jgit.lib.Repository.isValidRefName(input.ref) &&
            (input.ref.startsWith("refs/heads/") || input.ref.startsWith("refs/tags/"))) { "Invalid synchronized ref" }
        require(listOf(input.expectedBoscaSha, input.expectedGitHubSha).all { it == null || it.matches(OBJECT_ID) }) {
            "Invalid reviewed object ID"
        }
        val (outcome, resolved) = withRefSynchronizationTransaction(input.repositoryId, locks) {
            val pair = availablePair(repository.lockPair(input.repositoryId))
                ?: error("An enabled, available repository pairing is required")
            if (!canEdit(pair, principalId)) throw SecurityException("Repository EDIT permission is required")
            val state = repository.findRefState(input.repositoryId, input.ref)
            require(state?.conflict == true) { "This ref no longer has a recorded conflict. Refresh synchronization history." }
            require(state.boscaSha == input.expectedBoscaSha && state.githubSha == input.expectedGitHubSha) {
                "The conflict changed. Refresh synchronization history and review both values again."
            }
            val inbound = input.resolution == GitHubRefResolution.GITHUB
            val token = token(pair)
            val url = github.repositoryUrl(pair, token)
            val outcome = synchronize(pair, input.ref,
                if (inbound) input.expectedBoscaSha else input.expectedGitHubSha,
                if (inbound) input.expectedGitHubSha else input.expectedBoscaSha,
                if (inbound) RefSynchronizationDirection.INBOUND else RefSynchronizationDirection.OUTBOUND,
                principalId, url, token, triggerBuild = inbound, resolveConflict = true)
            outcome to checkNotNull(repository.findRefState(input.repositoryId, input.ref))
        }
        check(outcome == GitHubSyncResult.APPLIED || outcome == GitHubSyncResult.UNCHANGED) {
            "The refs changed during resolution. Refresh synchronization history and review both values again."
        }
        resolved
    }

    private suspend fun transferRefs(
        repositoryId: UUID, principalId: UUID, direction: RefSynchronizationDirection,
    ): List<GitHubRefState> = withConnectionManager {
        val pair = availablePair(repository.findPair(repositoryId))
            ?: error("An enabled, available repository pairing is required")
        if (!canEdit(pair, principalId)) throw SecurityException("Repository EDIT permission is required")
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        val current = writes.compareRefs(repositoryId, url, token).associateBy { it.ref }
        val baselines = repository.findAllRefStates(repositoryId).associateBy { it.ref }
        val result = mutableListOf<GitHubRefState>()
        for (ref in (current.keys + baselines.keys).sorted()) {
            withRefSynchronizationTransaction(repositoryId, locks) {
                val lockedPair = availablePair(repository.lockPair(repositoryId))
                    ?: error("Repository pairing is no longer enabled or available")
                check(lockedPair.version == pair.version) { "GitHub repository pair changed during transfer" }
                if (!canEdit(lockedPair, principalId)) throw SecurityException("Repository EDIT permission is required")
                val comparison = current[ref]
                val after = if (direction == RefSynchronizationDirection.INBOUND) comparison?.remoteSha else comparison?.localSha
                val baseline = repository.findRefState(repositoryId, ref)
                // A one-direction transfer does not delete destination-only refs without a common baseline.
                if (after != null || baseline?.synchronized == true) {
                    synchronize(lockedPair, ref, baseline?.sha, after, direction, principalId, url, token,
                        triggerBuild = direction == RefSynchronizationDirection.INBOUND)
                    repository.findRefState(repositoryId, ref)?.let(result::add)
                }
            }
        }
        result
    }

    override suspend fun reconcileRefs(repositoryId: UUID, principalId: UUID): List<GitHubRefState> =
        reconcilePair(repositoryId, principalId)

    override suspend fun reconcileRefs(repositoryId: UUID?): List<GitHubRefState> {
        if (repositoryId != null) return reconcilePair(repositoryId)
        // Each pair commits or rolls back on its own, so one failing pair cannot block the others.
        val reconciled = mutableListOf<GitHubRefState>()
        val failures = mutableListOf<Pair<UUID, Exception>>()
        for (pair in repository.findEnabledPairs()) {
            try {
                reconciled += reconcilePair(pair.repositoryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("GitHub ref reconciliation failed for repository {}", pair.repositoryId, e)
                failures += pair.repositoryId to e
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "GitHub ref reconciliation failed for ${failures.size} repositories: ${failures.joinToString { it.first.toString() }}",
                failures.first().second,
            ).apply { failures.drop(1).forEach { addSuppressed(it.second) } }
        }
        return reconciled
    }

    private suspend fun reconcilePair(repositoryId: UUID, principalId: UUID? = null): List<GitHubRefState> = withConnectionManager {
        val pair = availablePair(repository.findPair(repositoryId)) ?: run {
            check(principalId == null) { "An enabled, available repository pairing is required" }
            return@withConnectionManager emptyList()
        }
        if (principalId != null && !canEdit(pair, principalId)) throw SecurityException("Repository EDIT permission is required")
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        val current = writes.compareRefs(repositoryId, url, token).associateBy { it.ref }
        val baselines = repository.findAllRefStates(repositoryId).associateBy { it.ref }
        val reconciled = mutableListOf<GitHubRefState>()
        val pendingRefs = repository.findPendingPushRefs(repositoryId)
        for (ref in (current.keys + baselines.keys + pendingRefs).sorted()) {
            reconciled += withRefSynchronizationTransaction(repositoryId, locks) {
                val lockedPair = availablePair(repository.lockPair(repositoryId)) ?: run {
                    check(principalId == null) { "Repository pairing is no longer enabled or available" }
                    return@withRefSynchronizationTransaction emptyList<GitHubRefState>()
                }
                check(lockedPair.version == pair.version) { "GitHub repository pair changed during reconciliation" }
                if (principalId != null && !canEdit(lockedPair, principalId)) throw SecurityException("Repository EDIT permission is required")
                val pending = repository.findPendingPushDeliveries(repositoryId, ref)
                if (principalId == null && pending.isNotEmpty()) {
                    // The normal import pipeline owns these verified occurrences and their attribution.
                    pending.forEach { it.dispatch() }
                    return@withRefSynchronizationTransaction listOfNotNull(repository.findRefState(repositoryId, ref))
                }
                val local = current[ref]?.localSha
                val remote = current[ref]?.remoteSha
                val baseline = repository.findRefState(repositoryId, ref)
                // Both sides still hold the recorded common value: nothing to transfer or record.
                if (baseline != null && baseline.synchronized && !baseline.conflict && local == baseline.sha && remote == baseline.sha) {
                    return@withRefSynchronizationTransaction listOf(baseline)
                }
                if (local == remote) {
                    return@withRefSynchronizationTransaction listOf(repository.saveRefState(GitHubRefState(repositoryId, ref, sha = local,
                        synchronized = true, boscaSha = local, githubSha = remote)))
                }
                val outbound = if (baseline?.synchronized == true) remote == baseline.sha && local != remote
                    else remote == null && local != null
                if (principalId != null) {
                    val direction = if (outbound) RefSynchronizationDirection.OUTBOUND else RefSynchronizationDirection.INBOUND
                    val result = synchronize(lockedPair, ref, baseline?.sha, if (outbound) local else remote,
                        direction, principalId, url, token, triggerBuild = !outbound)
                    // Both hosts may have advanced along the same history; the safe direction can be outbound.
                    if (result == GitHubSyncResult.CONFLICT && !outbound && local != null && remote != null && ref.startsWith("refs/heads/")) {
                        synchronize(lockedPair, ref, baseline?.sha, local, RefSynchronizationDirection.OUTBOUND,
                            principalId, url, token)
                    }
                    return@withRefSynchronizationTransaction listOfNotNull(repository.findRefState(repositoryId, ref))
                }
                if (!outbound) {
                    val receipt = repository.findPushDelivery(repositoryId, ref, remote ?: "0000000000000000000000000000000000000000")
                    // Missing webhooks supply no user authority. Observe the divergence without importing it.
                    if (receipt == null) {
                        if (baseline?.synchronized == true && local != baseline.sha && local != null && remote != null && ref.startsWith("refs/heads/")) {
                            synchronize(lockedPair, ref, baseline.sha, local, RefSynchronizationDirection.OUTBOUND, null, url, token)
                            return@withRefSynchronizationTransaction listOfNotNull(repository.findRefState(repositoryId, ref))
                        }
                        val observed = repository.saveRefState(GitHubRefState(repositoryId, ref, sha = baseline?.sha,
                            synchronized = baseline?.synchronized == true, boscaSha = local, githubSha = remote,
                            conflict = baseline?.conflict == true || (baseline?.synchronized == true && local != baseline.sha && remote != baseline.sha)))
                        return@withRefSynchronizationTransaction listOf(observed)
                    }
                    synchronizeVerifiedPush(lockedPair, receipt)
                    return@withRefSynchronizationTransaction listOfNotNull(repository.findRefState(repositoryId, ref))
                }
                val result = synchronize(lockedPair, ref, baseline?.sha, local, RefSynchronizationDirection.OUTBOUND, null, url, token)
                if (result == GitHubSyncResult.STALE) emptyList() else listOfNotNull(repository.findRefState(repositoryId, ref))
            }
        }
        reconciled
    }

    /** Rechecks the pair under the write lock and commits each operation before that lock is released. */
    private suspend fun <T : Any> withPair(
        repositoryId: UUID, unavailable: T, block: suspend (GitHubRepositoryPair) -> T,
    ): T = withConnectionManager {
        if (availablePair(repository.findPair(repositoryId)) == null) return@withConnectionManager unavailable
        withRefSynchronizationTransaction(repositoryId, locks) {
            val pair = availablePair(repository.lockPair(repositoryId)) ?: return@withRefSynchronizationTransaction unavailable
            block(pair)
        }
    }

    private suspend fun availablePair(candidate: GitHubRepositoryPair?): GitHubRepositoryPair? {
        val pair = candidate?.takeIf { it.enabled } ?: return null
        val hosted = repositoryService.findById(pair.repositoryId)?.takeUnless { it.deleted || it.archived } ?: return null
        return pair.takeIf { it.repositoryId == hosted.id }
    }

    private suspend fun synchronize(
        pair: GitHubRepositoryPair, ref: String, before: String?, after: String?,
        direction: RefSynchronizationDirection, principalId: UUID?, remoteUrl: String, token: String,
        verifiedPush: Boolean = false,
        pullRequestMerge: Boolean = false,
        triggerBuild: Boolean = verifiedPush,
        resolveConflict: Boolean = false,
    ): GitHubSyncResult {
        if (direction == RefSynchronizationDirection.INBOUND && !canEdit(pair, principalId)) {
            throw SecurityException("The originating GitHub user requires Bosca repository EDIT permission")
        }
        val state = repository.findRefState(pair.repositoryId, ref)
        val unattributedBefore = if (verifiedPush && principalId != null && after != null) {
            repository.findUnattributedBeforeSha(pair.repositoryId, ref, after)
        } else null
        val outcome = writes.synchronizeRef(RefSynchronizationInput(
            repositoryId = pair.repositoryId, remoteUrl = remoteUrl,
            token = token, ref = ref, beforeSha = before, afterSha = after, synchronizedSha = state?.sha,
            hasSynchronized = state?.synchronized == true, direction = direction, principalId = principalId,
            hasConflict = state?.conflict == true,
            unattributedBeforeSha = unattributedBefore,
            protection = if (direction == RefSynchronizationDirection.INBOUND && ref.startsWith("refs/heads/"))
                protections.findMatchingRule(pair.repositoryId, ref.removePrefix("refs/heads/")) else null,
            pullRequestMerge = pullRequestMerge,
            triggerBuild = triggerBuild,
            resolveConflict = resolveConflict,
        ))
        val converged = outcome.result == GitHubSyncResult.APPLIED || outcome.result == GitHubSyncResult.UNCHANGED
        val anonymousImport = outcome.result == GitHubSyncResult.APPLIED &&
            direction == RefSynchronizationDirection.INBOUND && !triggerBuild && outcome.boscaSha != null
        val retainAttribution = outcome.result != GitHubSyncResult.APPLIED &&
            !(verifiedPush && outcome.result == GitHubSyncResult.UNCHANGED) && state?.sha == outcome.boscaSha
        repository.saveRefState(GitHubRefState(
            repositoryId = pair.repositoryId, ref = ref,
            sha = if (converged) outcome.boscaSha else state?.sha,
            synchronized = converged || state?.synchronized == true,
            boscaSha = outcome.boscaSha, githubSha = outcome.remoteSha,
            conflict = if (converged) false else outcome.result == GitHubSyncResult.CONFLICT || state?.conflict == true,
            unattributedBeforeSha = when {
                anonymousImport -> outcome.beforeSha ?: "0000000000000000000000000000000000000000"
                retainAttribution -> state?.unattributedBeforeSha
                else -> null
            },
            unattributedRefModified = if (retainAttribution) state?.unattributedRefModified else null,
        ))
        return outcome.result
    }

    private suspend fun token(pair: GitHubRepositoryPair): String =
        secrets.resolve(pair.tokenSecretName)?.takeIf { it.isNotBlank() } ?: throw GitHubWebhookUnavailableException()

    private fun String.nonZeroSha(): String? = takeUnless { it == "0000000000000000000000000000000000000000" }

    private fun validatePage(offset: Long, limit: Int) {
        require(offset >= 0 && limit in 1..100) { "Invalid pagination" }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) } }
        private val SEGMENT = Regex("[A-Za-z0-9_.-]+")
        private val OBJECT_ID = Regex("[0-9a-f]{40}")
        /** Bounds export rounds for a PR edited faster than it can be exported. Later edits have their own events. */
        private const val MAX_EXPORT_ROUNDS = 3
        private val DELIVERY_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EVENT_NAME = Regex("[a-z_]+")
        private val SIGNATURE = Regex("sha256=[0-9a-fA-F]{64}")
        private val SUPPORTED_EVENTS = setOf("push", "pull_request")

        internal fun verifySignature(secret: String, signature: String?, body: ByteArray): Boolean {
            if (signature == null || !signature.matches(SIGNATURE)) return false
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(body)
            val received = signature.removePrefix("sha256=").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return MessageDigest.isEqual(expected, received)
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
