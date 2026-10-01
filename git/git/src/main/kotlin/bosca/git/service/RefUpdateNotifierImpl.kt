package bosca.git.service

import bosca.di.provide
import bosca.git.jobs.FileContentIndexJob
import bosca.git.jobs.RepositoryIndexJob
import bosca.git.model.PipelineTriggerJob
import bosca.git.model.GitRefKind
import bosca.git.model.GitRefUpdateAction
import bosca.git.model.PushEvent
import bosca.git.model.RefUpdateEvent
import bosca.git.model.TaskCommitReference
import bosca.git.model.TaskKeyExtractor
import bosca.git.model.WebhookEvent
import bosca.git.model.dispatch
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.slf4j.LoggerFactory

class RefUpdateNotifierImpl(
    private val repositoryRepository: GitRepositoryRepository,
    private val packRepository: DfsPackRepository,
    private val webhookService: WebhookService,
    private val taskCommitRefRepository: TaskCommitReferenceRepository,
    private val pullRequestService: PullRequestService? = null,
    private val profileService: ProfileService? = null,
    private val securityService: SecurityService? = null,
) : RefUpdateNotifier {

    override suspend fun notifyRefsUpdated(
        repository: Repository,
        repositoryId: UUID,
        updates: List<RefChange>,
        pusherPrincipalId: UUID?,
    ) {
        if (updates.isEmpty()) return

        updateDiskSize(repositoryId)

        for (update in updates) {
            runSideEffect("update open pull requests", repositoryId, update) {
                notifyOpenPullRequests(update, repositoryId)
            }
            val details = runSideEffect("collect commit details", repositoryId, update, PushDetails()) {
                collectPushDetails(repository, update)
            }
            runSideEffect("dispatch webhooks", repositoryId, update) {
                dispatchWebhookEvents(repositoryId, update)
            }
            runSideEffect("dispatch the internal push event", repositoryId, update) {
                dispatchInternalEvent(repositoryId, update, pusherPrincipalId, details)
            }
            runSideEffect("dispatch the ref-update event", repositoryId, update) {
                dispatchRefUpdateEvent(repositoryId, update, pusherPrincipalId, details)
            }
        }

        enqueueSearchIndex(repositoryId, updates)
        enqueuePipelineTrigger(repository, repositoryId, updates, pusherPrincipalId)
    }

    private suspend fun updateDiskSize(repositoryId: UUID) {
        try {
            val sizeBytes = packRepository.sumPackSizeBytes(repositoryId)
            repositoryRepository.updateDiskSize(repositoryId, sizeBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to update disk size for repository {}", repositoryId, e)
        }
    }

    private suspend fun dispatchWebhookEvents(repositoryId: UUID, update: RefChange) {
        val refName = update.refName
        val isCreate = update.oldId == ObjectId.zeroId()
        val isDelete = update.newId == ObjectId.zeroId()

        val payload = buildJsonObject {
            put("ref", refName)
            put("before", update.oldId.name())
            put("after", update.newId.name())
            put("repository_id", repositoryId.toString())
        }.toString()

        when {
            refName.startsWith("refs/heads/") -> {
                when {
                    isCreate -> webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_CREATED, payload)
                    isDelete -> webhookService.dispatch(repositoryId, WebhookEvent.BRANCH_DELETED, payload)
                    else -> webhookService.dispatch(repositoryId, WebhookEvent.PUSH, payload)
                }
            }
            refName.startsWith("refs/tags/") -> {
                when {
                    isCreate -> webhookService.dispatch(repositoryId, WebhookEvent.TAG_CREATED, payload)
                    isDelete -> webhookService.dispatch(repositoryId, WebhookEvent.TAG_DELETED, payload)
                }
            }
        }
    }

    private fun collectPushDetails(
        repository: Repository,
        update: RefChange,
    ): PushDetails {
        if (update.newId == ObjectId.zeroId()) return PushDetails()

        val commitMessages = mutableListOf<String>()
        RevWalk(repository).use { revWalk ->
            val newCommit = revWalk.parseCommit(revWalk.peel(revWalk.parseAny(update.newId)))
            revWalk.markStart(newCommit)
            if (update.oldId != ObjectId.zeroId()) {
                val oldCommit = revWalk.parseCommit(revWalk.peel(revWalk.parseAny(update.oldId)))
                revWalk.markUninteresting(oldCommit)
            }
            var count = 0
            for (commit in revWalk) {
                commitMessages.add(commit.fullMessage)
                if (++count >= 50) break
            }
        }

        val taskKeys = TaskKeyExtractor.extractFromAll(
            *commitMessages.toTypedArray(),
            update.refName
        )
        return PushDetails(taskKeys, commitMessages)
    }

    private suspend fun dispatchInternalEvent(
        repositoryId: UUID,
        update: RefChange,
        pusherPrincipalId: UUID?,
        details: PushDetails,
    ) {
        if (update.newId == ObjectId.zeroId()) return

        for (taskKey in details.taskKeys) {
            try {
                taskCommitRefRepository.create(
                    TaskCommitReference(
                        repositoryId = repositoryId,
                        taskKey = taskKey,
                        commitSha = update.newId.name()
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Failed to create task commit reference for {}: {}", taskKey, e.message)
            }
        }

        PushEvent(
            repositoryId = repositoryId,
            ref = update.refName,
            beforeSha = update.oldId.name(),
            afterSha = update.newId.name(),
            pusherPrincipalId = pusherPrincipalId,
            taskKeys = details.taskKeys,
            commitMessages = details.commitMessages,
        ).dispatch()
    }

    /** Emits a notification-friendly event for branch and tag create/update/delete operations. */
    private suspend fun dispatchRefUpdateEvent(
        repositoryId: UUID,
        update: RefChange,
        pusherPrincipalId: UUID?,
        details: PushDetails,
    ) {
        val kind = when {
            update.refName.startsWith("refs/heads/") -> GitRefKind.BRANCH
            update.refName.startsWith("refs/tags/") -> GitRefKind.TAG
            else -> return
        }
        val repository = repositoryRepository.findById(repositoryId) ?: return
        val created = update.oldId == ObjectId.zeroId()
        val deleted = update.newId == ObjectId.zeroId()
        RefUpdateEvent(
            repositoryId = repositoryId,
            repositoryName = repository.name,
            ref = update.refName,
            refName = when (kind) {
                GitRefKind.BRANCH -> update.refName.removePrefix("refs/heads/")
                GitRefKind.TAG -> update.refName.removePrefix("refs/tags/")
            },
            kind = kind,
            action = when {
                created -> GitRefUpdateAction.CREATED
                deleted -> GitRefUpdateAction.DELETED
                else -> GitRefUpdateAction.UPDATED
            },
            beforeSha = update.oldId.takeUnless { created }?.name(),
            afterSha = update.newId.takeUnless { deleted }?.name(),
            actorId = resolveActorProfileId(pusherPrincipalId),
            recipientIds = setOf(repository.ownerId),
            taskKeys = details.taskKeys,
            commitMessages = details.commitMessages.take(5),
        ).dispatch()
    }

    /** A source-branch push updates every open pull request based on that branch. */
    private suspend fun notifyOpenPullRequests(update: RefChange, repositoryId: UUID) {
        val service = pullRequestService ?: return
        if (!update.refName.startsWith("refs/heads/") || update.newId == ObjectId.zeroId()) return
        val branch = update.refName.removePrefix("refs/heads/")
        for (pullRequest in service.findOpenBySourceBranch(repositoryId, branch)) {
            try {
                service.onSourceBranchPushed(pullRequest.id, update.newId.name())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(
                    "Failed to update pull request {} after {} changed on repository {}",
                    pullRequest.id,
                    update.refName,
                    repositoryId,
                    e,
                )
            }
        }
    }

    /** Resolves an initiating profile or principal id to the primary profile used by communications. */
    private suspend fun resolveActorProfileId(id: UUID?): UUID? {
        id ?: return null
        val profiles = profileService ?: return id
        val security = securityService ?: return id
        return try {
            profiles.getAllByIds(listOf(id)).firstOrNull()?.id
                ?: security.getPrincipalById(id)?.let { profiles.getPrimaryProfile(it)?.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not resolve Git ref-update actor {} to a profile", id, e)
            null
        }
    }

    private suspend fun runSideEffect(
        operation: String,
        repositoryId: UUID,
        update: RefChange,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to {} for {} on repository {}", operation, update.refName, repositoryId, e)
        }
    }

    private suspend fun <T> runSideEffect(
        operation: String,
        repositoryId: UUID,
        update: RefChange,
        fallback: T,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to {} for {} on repository {}", operation, update.refName, repositoryId, e)
            fallback
        }
    }

    private suspend fun enqueueSearchIndex(repositoryId: UUID, updates: List<RefChange>) {
        try {
            val json = provide<Json>()

            val repoEnqueuer = provide<JobConfigurationEnqueuer>("repository-index")
            repoEnqueuer.enqueue(json.encodeToJsonElement(RepositoryIndexJob(repositoryId = repositoryId)))

            // Enqueue one file-content-index job per branch update — including deletions
            // (newId == zeroId). The FileContentIndexExecutor serializes per-repo, so
            // multiple branch jobs from a single push will run one at a time and can't
            // race each other on the shared `branches` overlay.
            val fileEnqueuer = provide<JobConfigurationEnqueuer>("file-content-index")
            for (update in updates) {
                if (!update.refName.startsWith("refs/heads/")) continue
                val beforeSha = update.oldId.takeIf { it != ObjectId.zeroId() }?.name()
                val afterSha = update.newId.takeIf { it != ObjectId.zeroId() }?.name()
                if (beforeSha == null && afterSha == null) continue
                fileEnqueuer.enqueue(json.encodeToJsonElement(
                    FileContentIndexJob(
                        repositoryId = repositoryId,
                        ref = update.refName,
                        beforeSha = beforeSha,
                        afterSha = afterSha,
                    )
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to enqueue search index for repository {}", repositoryId, e)
        }
    }

    private suspend fun enqueuePipelineTrigger(
        repository: Repository,
        repositoryId: UUID,
        updates: List<RefChange>,
        pusherPrincipalId: UUID?,
    ) {
        try {
            val json = provide<Json>()
            val enqueuer = provide<JobConfigurationEnqueuer>("pipeline-trigger")

            for (update in updates) {
                if (update.newId == ObjectId.zeroId()) continue
                if (!update.refName.startsWith("refs/heads/") && !update.refName.startsWith("refs/tags/")) continue

                // An ANNOTATED tag's ref points at the tag OBJECT, not the commit. Everything
                // downstream (the run's commitSha, commit statuses, run correlation) means the
                // commit — peel before enqueuing.
                val commitId = peelToCommit(repository, update.newId)

                // `[skip ci]` suppresses the trigger on every ref kind — read from the pushed object's
                // OWN message: a branch push checks its commit, an annotated tag checks the tag message.
                // (Deliberately NOT the peeled commit for tags: a release tag pointing at a `[skip ci]`
                // pin commit must still build — the pin commit's marker only silences the branch push.)
                if (hasSkipCiMarker(repository, update.newId)) {
                    log.info("Skipping pipeline trigger for {} on {} — [skip ci]", update.refName, repositoryId)
                    continue
                }

                enqueuer.enqueue(json.encodeToJsonElement(
                    PipelineTriggerJob(
                        repositoryId = repositoryId,
                        ref = update.refName,
                        beforeSha = update.oldId.name(),
                        afterSha = commitId.name(),
                        pusherPrincipalId = pusherPrincipalId,
                    )
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to enqueue pipeline trigger for repository {}", repositoryId, e)
        }
    }

    /**
     * Whether the pushed object's own message carries a CI-suppression marker (`[skip ci]` /
     * `[ci skip]`): the tag message for an annotated tag, the commit message otherwise.
     */
    private fun hasSkipCiMarker(repository: Repository, id: ObjectId): Boolean =
        RevWalk(repository).use { walk ->
            try {
                val message = when (val obj = walk.parseAny(id)) {
                    is org.eclipse.jgit.revwalk.RevTag -> obj.fullMessage
                    is org.eclipse.jgit.revwalk.RevCommit -> {
                        walk.parseBody(obj)
                        obj.fullMessage
                    }
                    else -> return@use false
                }.lowercase()
                "[skip ci]" in message || "[ci skip]" in message
            } catch (e: Exception) {
                // Unreadable — never suppress on uncertainty.
                false
            }
        }

    /** [id] peeled to the commit it ultimately points at (annotated tags dereferenced); [id] itself otherwise. */
    private fun peelToCommit(repository: Repository, id: ObjectId): ObjectId =
        RevWalk(repository).use { walk ->
            try {
                walk.peel(walk.parseAny(id)).id
            } catch (e: Exception) {
                log.warn("Could not peel {} to a commit; using it as-is", id.name(), e)
                id
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger(RefUpdateNotifierImpl::class.java)
    }

    private data class PushDetails(
        val taskKeys: Set<String> = emptySet(),
        val commitMessages: List<String> = emptyList(),
    )
}
