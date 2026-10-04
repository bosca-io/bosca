package bosca.git.service

import bosca.git.github.GitHubClient
import bosca.git.github.GitHubRequestRejectedException
import bosca.git.model.*
import bosca.git.repository.GitHubSyncRepository
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive

/** Runs under the pair's transaction and repository write lock. Provider writes recover through the common snapshot. */
internal class GitHubPullRequestSynchronization(
    private val repository: GitHubSyncRepository,
    private val pulls: PullRequestService,
    private val profiles: ProfileService,
    private val security: SecurityService,
    private val client: GitHubClient,
    private val verifyImport: suspend (GitHubPullRequest) -> Boolean,
    private val synchronizeBranches: suspend (List<String>, RefSynchronizationDirection, GitHubPullRequest?) -> Boolean,
) {
    suspend fun import(pair: GitHubRepositoryPair, token: String, number: Int): GitHubSyncResult {
        val remote = client.getPullRequest(pair, token, number)
        if (!eligible(pair, remote)) return GitHubSyncResult.IGNORED
        var state = repository.findPullRequestState(pair.repositoryId, number)
        if (state == null) {
            // Copied references cannot claim another PR's identity or its reserved source branch.
            val reservations = mutableListOf<GitHubPullRequestState>()
            for (id in markerIds(remote.body)) {
                val candidate = repository.findPullRequestStateById(pair.repositoryId, id) ?: continue
                if (candidate.githubId != null && candidate.githubId != remote.id) continue
                if (candidate.snapshot?.sourceBranch != remote.head.ref) continue
                reservations += candidate
            }
            if (reservations.size > 1) {
                for (candidate in reservations) problem(candidate, candidate.boscaSnapshot, snapshot(remote),
                    "GitHub pull request contains multiple Bosca counterpart references")
                return GitHubSyncResult.CONFLICT
            }
            val reservation = reservations.singleOrNull()
            if (reservation != null) {
                if (reservation.githubId == null && counterparts(pair, token, reservation.id, remote.head.ref).any { it.id != remote.id }) {
                    return problem(reservation, reservation.boscaSnapshot, snapshot(remote),
                        "Multiple GitHub counterparts contain the same Bosca reference")
                }
                state = reservation.copy(githubId = remote.id, githubNumber = number)
            } else {
                state = GitHubPullRequestState(repositoryId = pair.repositoryId, githubId = remote.id, githubNumber = number, imported = true)
            }
        }
        check(state.githubId == remote.id) { "GitHub pull request no longer matches its counterpart" }
        val nativeId = state.pullRequestId
        if (nativeId == null) {
            // Completed GitHub history is not imported. Only paired PRs continue synchronizing after completion.
            if (remote.state != "open") return GitHubSyncResult.IGNORED
            val author = remote.user.takeIf { it.type == "User" }?.let { repository.findUser(it.id) }
                ?.let { security.getPrincipalById(it.principalId) }?.takeIf { it.deletedAt == null }
                ?.let { profiles.getPrimaryProfile(it) }
            if (author == null) return problem(state, null, snapshot(remote), "GitHub author needs a mapped Bosca principal with a primary profile")
            if (!verifyImport(remote)) return problem(state, null, snapshot(remote), "A matching verified GitHub occurrence and current Bosca repository EDIT permission are required")
            if (!synchronizeBranches(listOf(remote.head.ref, remote.base.ref), RefSynchronizationDirection.INBOUND, remote)) {
                return problem(state, null, snapshot(remote), "Pull request branches have unresolved ref changes")
            }
            val created = pulls.create(CreatePullRequestInput(pair.repositoryId, remote.title, remote.body,
                remote.head.ref, remote.base.ref, isDraft = remote.draft), author.id)
            state = state.copy(pullRequestId = created.id)
            val desired = snapshot(remote)
            val imported = if (snapshot(created) == desired) created else pulls.synchronize(
                created.id, created.version, desired, remote.mergedAt, merger(remote),
            ) ?: error("New pull request changed concurrently")
            agree(state, snapshot(imported))
            return GitHubSyncResult.APPLIED
        }
        val native = pulls.findById(nativeId) ?: error("Paired pull request no longer exists")
        require(native.repositoryId == pair.repositoryId) { "Pull request belongs to another repository" }
        return synchronize(pair, token, state, native, remote)
    }

    suspend fun export(pair: GitHubRepositoryPair, token: String, id: UUID): GitHubSyncResult {
        val native = pulls.findById(id) ?: return GitHubSyncResult.IGNORED
        if (native.sourceRepositoryId != null && native.sourceRepositoryId != pair.repositoryId) return GitHubSyncResult.IGNORED
        var state = repository.findPullRequestState(pair.repositoryId, id) ?: return GitHubSyncResult.IGNORED
        var created = false
        val number = state.githubNumber
        val completed = native.status == PullRequestStatus.MERGED || native.status == PullRequestStatus.CLOSED
        // Counterparts are only created for active PRs, so an earlier search after completion remains final.
        if (number == null && completed && state.problem == NO_COUNTERPART) return problem(state, snapshot(native), null, NO_COUNTERPART)
        var remote = number?.let { client.getPullRequest(pair, token, it) }
        if (remote == null) {
            // A durable reservation and exact marker recover a successful create whose response was lost.
            val found = counterparts(pair, token, state.id, native.sourceBranch)
            if (found.size > 1) return problem(state, snapshot(native), null, "Multiple GitHub counterparts contain the same Bosca reference")
            // The listing omits merge fields, so read the matched counterpart in full.
            remote = found.singleOrNull()?.let { client.getPullRequest(pair, token, it.number) }
            if (remote == null) {
                if (completed) return problem(state, snapshot(native), null, NO_COUNTERPART)
                val intended = state.pending ?: snapshot(native)
                if (!synchronizeBranches(listOf(intended.sourceBranch, intended.targetBranch), RefSynchronizationDirection.OUTBOUND, null)) {
                    return problem(state, snapshot(native), null, "Pull request branches have unresolved ref changes")
                }
                // GitHub refuses a source branch with no new commits or a second open PR for the same branches.
                remote = try {
                    client.createPullRequest(pair, token, GitHubCreatePullRequestInput(
                        intended.title, body(state, native.authorId, intended), intended.sourceBranch, intended.targetBranch,
                        intended.status == PullRequestStatus.DRAFT,
                    ))
                } catch (e: GitHubRequestRejectedException) {
                    return problem(state.copy(pending = null), snapshot(native), null, "GitHub rejected the counterpart pull request")
                }
                created = true
            }
            state = state.copy(githubId = remote.id, githubNumber = remote.number)
        }
        check(remote.id == state.githubId) { "GitHub pull request no longer matches its counterpart" }
        if (!eligible(pair, remote)) return problem(state, snapshot(native), snapshot(remote), "Counterpart branches belong to another repository")
        val result = synchronize(pair, token, state, native, remote)
        return if (created && result == GitHubSyncResult.UNCHANGED) GitHubSyncResult.APPLIED else result
    }

    /** Finds complete references on the paired source branch before adopting a recovered identity. */
    private suspend fun counterparts(pair: GitHubRepositoryPair, token: String, id: UUID, sourceBranch: String): List<GitHubPullRequest> {
        val found = mutableListOf<GitHubPullRequest>()
        var page = 1
        while (true) {
            val candidates = client.listPullRequests(pair, token, page++, sourceBranch)
            found += candidates.filter { eligible(pair, it) && it.head.ref == sourceBranch && id in markerIds(it.body) }
            if (candidates.isEmpty()) break
        }
        return found.distinctBy { it.id }
    }

    private suspend fun synchronize(
        pair: GitHubRepositoryPair, token: String, state: GitHubPullRequestState,
        native: PullRequest, remote: GitHubPullRequest,
    ): GitHubSyncResult {
        val local = snapshot(native)
        val other = snapshot(remote, state, local)
        if (local == other) { agree(state, local); return GitHubSyncResult.UNCHANGED }
        val common = state.snapshot
        if (common == null) return problem(state, local, other, "Counterparts differ before their first common snapshot")
        val recovering = state.pending?.let { pending -> matchesPending(other, common, pending) } == true
        if (local != common && other != common && !recovering) return problem(state, local, other, "Both pull requests changed since their last common snapshot")
        if (other != common && !recovering) {
            if (local.status == PullRequestStatus.MERGED &&
                (other.status != PullRequestStatus.MERGED || other.mergeSha != local.mergeSha)) {
                return problem(state, local, other, "A completed merge cannot be reverted or rewritten")
            }
            if (!verifyImport(remote)) return problem(state, local, other, "A matching verified GitHub occurrence and current Bosca repository EDIT permission are required")
            val merge = remote.takeIf { it.merged && native.status != PullRequestStatus.MERGED }
            if (merge != null) {
                if (other.sourceBranch != native.sourceBranch || other.targetBranch != native.targetBranch) {
                    return problem(state, local, other, "The completed GitHub merge targets different Bosca branches")
                }
                try { pulls.verifyMergeAllowed(native.id, native.version, remote.head.sha) }
                catch (e: IllegalArgumentException) { return problem(state, local, other, e.message ?: "Bosca merge protection rejected the import") }
            }
            if (!synchronizeBranches(listOf(other.sourceBranch, other.targetBranch), RefSynchronizationDirection.INBOUND, remote)) {
                return problem(state, local, other, "Pull request branches have unresolved ref changes")
            }
            val updated = pulls.synchronize(native.id, native.version, other, remote.mergedAt ?: native.mergedAt, merger(remote))
                ?: return problem(state, snapshot(pulls.findById(native.id) ?: error("Pull request disappeared")), other,
                    "Bosca pull request changed during synchronization")
            agree(state, snapshot(updated))
            return GitHubSyncResult.APPLIED
        }
        // Only the committed intention is written, so a failure partway through is always recognizable on retry.
        // A newer Bosca edit is exported by a later round after that intention is agreed.
        val intended = state.pending ?: local
        if (remote.merged && (intended.status != PullRequestStatus.MERGED || intended.mergeSha != remote.mergeSha)) {
            return problem(state, local, other, "A completed merge cannot be reverted or rewritten")
        }
        if (remote.head.ref != intended.sourceBranch) return problem(state, local, other, "GitHub cannot change a counterpart's source branch")
        if (!synchronizeBranches(listOf(intended.sourceBranch, intended.targetBranch), RefSynchronizationDirection.OUTBOUND, null)) {
            return problem(state, local, other, "Pull request branches have unresolved ref changes")
        }
        // Ref transfers can cause GitHub to recognize an indirect merge. Read again before changing lifecycle.
        var current = client.getPullRequest(pair, token, remote.number)
        val observed = snapshot(current, state, intended)
        val previous = snapshot(remote, state, intended)
        val completedByTransfer = intended.status == PullRequestStatus.MERGED && current.merged &&
            observed.mergeSha == intended.mergeSha &&
            observed.copy(status = previous.status, mergeSha = previous.mergeSha) == previous
        if (observed != previous && observed != intended && !completedByTransfer) {
            return problem(state, local, snapshot(current, state, local), "GitHub pull request changed during synchronization")
        }
        val draft = intended.status == PullRequestStatus.DRAFT
        val desiredState = if (intended.status in setOf(PullRequestStatus.OPEN, PullRequestStatus.DRAFT)) "open" else "closed"
        val descriptionChanged = intended.description != observed.description
        val needsMergeRecord = intended.status == PullRequestStatus.MERGED && !current.merged && observed.status != PullRequestStatus.MERGED
        val changedBody = if (descriptionChanged || needsMergeRecord) {
            body(state, native.authorId, intended).takeUnless { it == current.body?.replace("\r\n", "\n") }?.let(::JsonPrimitive)
        } else null
        val changes = GitHubUpdatePullRequestInput(
            title = intended.title.takeUnless { it == current.title },
            body = changedBody,
            base = intended.targetBranch.takeUnless { it == current.base.ref },
            state = desiredState.takeUnless { it == current.state },
        )
        if (changes != GitHubUpdatePullRequestInput()) {
            current = try {
                client.updatePullRequest(pair, token, current.number, changes)
            } catch (e: GitHubRequestRejectedException) {
                return problem(state.copy(pending = null), local, observed, "GitHub rejected the counterpart pull request update")
            }
        }
        if (desiredState == "open" && current.draft != draft) client.setPullRequestDraft(pair, token, current.nodeId, draft)
        current = client.getPullRequest(pair, token, current.number)
        val latest = pulls.findById(native.id) ?: error("Pull request disappeared")
        if (latest.version != native.version || snapshot(current, state, intended) != intended) {
            val latestSnapshot = snapshot(latest)
            return problem(state, latestSnapshot, snapshot(current, state, latestSnapshot), "Pull request changed during synchronization")
        }
        agree(state, intended, local)
        return GitHubSyncResult.APPLIED
    }

    private suspend fun body(state: GitHubPullRequestState, authorId: UUID, content: GitHubPullRequestSnapshot): String {
        val author = profiles.getAllByIds(listOf(authorId)).firstOrNull()
            ?: security.getPrincipalById(authorId)?.let { profiles.getPrimaryProfile(it) }
        val attribution = if (state.imported) "Mirrored by Bosca." else "Originally opened in Bosca by ${author?.name ?: "the original author"}."
        val merge = if (content.status == PullRequestStatus.MERGED) "\n\nMerged in Bosca at `${content.mergeSha}`." else ""
        return content.description.orEmpty() + "\n\n<!-- bosca-pull-request:${state.id} -->\n" + attribution + merge +
            "\n<!-- /bosca-pull-request:${state.id} -->"
    }

    private suspend fun merger(remote: GitHubPullRequest): UUID? = remote.mergedBy?.takeIf { it.type == "User" }
        ?.let { repository.findUser(it.id) }?.let { security.getPrincipalById(it.principalId) }
        ?.takeIf { it.deletedAt == null }?.let { profiles.getPrimaryProfile(it)?.id }

    private fun eligible(pair: GitHubRepositoryPair, remote: GitHubPullRequest) =
        remote.head.repo?.id == pair.githubRepositoryId && remote.base.repo?.id == pair.githubRepositoryId

    private suspend fun agree(state: GitHubPullRequestState, content: GitHubPullRequestSnapshot, bosca: GitHubPullRequestSnapshot = content) {
        repository.savePullRequestState(state.copy(snapshot = content, pending = null, boscaSnapshot = bosca, githubSnapshot = content, problem = null))
    }

    private suspend fun problem(state: GitHubPullRequestState, local: GitHubPullRequestSnapshot?, remote: GitHubPullRequestSnapshot?, reason: String): GitHubSyncResult {
        repository.savePullRequestState(state.copy(boscaSnapshot = local, githubSnapshot = remote, problem = reason))
        return GitHubSyncResult.CONFLICT
    }

    internal companion object {
        private const val NO_COUNTERPART = "A completed pull request has no GitHub counterpart"
        /** Every observed field must be either unchanged or exactly the committed outbound value. */
        private fun matchesPending(observed: GitHubPullRequestSnapshot, common: GitHubPullRequestSnapshot, intended: GitHubPullRequestSnapshot): Boolean =
            observed.title in setOf(common.title, intended.title) &&
                observed.description in setOf(common.description, intended.description) &&
                observed.sourceBranch in setOf(common.sourceBranch, intended.sourceBranch) &&
                observed.targetBranch in setOf(common.targetBranch, intended.targetBranch) &&
                observed.status in setOf(common.status, intended.status) &&
                observed.mergeSha in setOf(common.mergeSha, intended.mergeSha)
        private val marker = Regex("<!-- bosca-pull-request:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}) -->")
        /** Only complete blocks identify reservations; an opening marker in ordinary text cannot hide one. */
        private fun markerIds(body: String?): List<UUID> {
            val text = body.orEmpty().replace("\r\n", "\n")
            return marker.findAll(text).map { UUID.parse(it.groupValues[1]) }.distinct()
                .filter { footerRanges(text, it).isNotEmpty() }.toList()
        }

        /** A complete footer cannot span another opening marker for the same reservation. */
        private fun footerRanges(body: String, id: UUID): List<IntRange> {
            val startMarker = "\n\n<!-- bosca-pull-request:$id -->\n"
            val endMarker = "\n<!-- /bosca-pull-request:$id -->"
            val candidates = mutableListOf<IntRange>()
            var offset = 0
            while (true) {
                val start = body.indexOf(startMarker, offset)
                if (start < 0) break
                offset = start + startMarker.length
                val end = body.indexOf(endMarker, offset)
                val next = body.indexOf(startMarker, offset)
                if (end >= 0 && (next < 0 || end < next)) candidates += start until end + endMarker.length
            }
            return candidates
        }

        /** Quoted blocks are matched against known user content before choosing a managed footer. */
        private fun footer(body: String, state: GitHubPullRequestState, reference: GitHubPullRequestSnapshot?): IntRange? {
            val candidates = footerRanges(body, state.id)
            if (candidates.isEmpty()) return null
            val descriptions = listOfNotNull(reference, state.pending, state.snapshot)
                .map { it.description?.replace("\r\n", "\n") }.distinct()
            // The managed footer was removed; matching known user content must keep any quoted blocks.
            if (body in descriptions) return null
            for (description in descriptions) {
                candidates.firstOrNull {
                    val remaining = body.removeRange(it)
                    remaining == description || (remaining.isEmpty() && description == null)
                }?.let { return it }
            }
            // Appended user text can follow a footer whose original description still identifies its prefix.
            for (description in descriptions) {
                candidates.firstOrNull { body.substring(0, it.first) == description.orEmpty() }?.let { return it }
            }
            // A known quotation remains user content even when the surrounding description changes.
            // Ambiguous blocks remain ordinary text rather than choosing one to discard.
            val candidate = candidates.singleOrNull() ?: return null
            return candidate.takeUnless {
                descriptions.any { it != null && footerRanges(it, state.id).isNotEmpty() }
            }
        }

        fun snapshot(pr: PullRequest) = GitHubPullRequestSnapshot(pr.title, pr.description?.replace("\r\n", "\n"), pr.sourceBranch, pr.targetBranch,
            pr.status, if (pr.status == PullRequestStatus.MERGED) pr.mergeSha else null)

        /** [reference] is the Bosca content the footer is compared against. */
        private fun snapshot(
            remote: GitHubPullRequest, state: GitHubPullRequestState? = null, reference: GitHubPullRequestSnapshot? = null,
        ): GitHubPullRequestSnapshot {
            // Both hosts' descriptions compare using LF, including GitHub's CRLF web-editor content.
            val body = remote.body?.replace("\r\n", "\n")
            var description = body
            var recordedMergeSha: String? = null
            if (state != null && body != null) {
                val footer = footer(body, state, reference)
                if (footer != null) {
                    description = body.removeRange(footer)
                        .takeUnless { it.isEmpty() && reference?.description == null }
                    if (!remote.merged && remote.state == "closed" && reference?.status == PullRequestStatus.MERGED &&
                        body.substring(footer).contains("\n\nMerged in Bosca at `${reference.mergeSha}`.")) {
                        recordedMergeSha = reference.mergeSha
                    }
                }
            }
            val status = when {
                remote.merged || recordedMergeSha != null -> PullRequestStatus.MERGED
                remote.state == "closed" -> PullRequestStatus.CLOSED
                remote.draft -> PullRequestStatus.DRAFT
                else -> PullRequestStatus.OPEN
            }
            return GitHubPullRequestSnapshot(remote.title, description, remote.head.ref, remote.base.ref, status,
                recordedMergeSha ?: if (remote.merged) remote.mergeSha else null)
        }
    }
}
