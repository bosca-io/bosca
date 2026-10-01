package bosca.git.model

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Events emitted by the git server for downstream consumption by Work Ops,
 * content versioning, and notification systems. Integrates with Bosca's
 * [Event] infrastructure for automatic PubSub broadcasting and script
 * trigger hooks.
 */
@Serializable
sealed class GitEvent : Event {
    abstract val repositoryId: UUID

    open override fun identityKey(): Any = repositoryId
}

/**
 * Emitted when a push is received on a Bosca-hosted repository. Contains the
 * ref that was updated, the before/after SHAs, and any extracted task keys.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.git.push")
@Serializable
data class PushEvent(
    override val repositoryId: UUID,
    val ref: String,
    val beforeSha: String,
    val afterSha: String,
    /** The principal that initiated the push and any builds triggered by it. */
    val pusherPrincipalId: UUID? = null,
    val taskKeys: Set<String> = emptySet(),
    val commitMessages: List<String> = emptyList()
) : GitEvent()

/**
 * Emitted for every user-visible pull-request activity. The event is deliberately
 * self-contained so notification pipelines can choose recipients, render useful
 * copy, and link back to the pull request without querying the Git database.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.git.pull_request")
@Serializable
data class PullRequestEvent(
    override val repositoryId: UUID,
    val pullRequestId: UUID,
    val number: Int,
    val action: PullRequestEventAction,
    val title: String,
    val sourceBranch: String,
    val targetBranch: String,
    val authorId: UUID,
    val taskKeys: Set<String> = emptySet(),
    val repositoryName: String = "Repository",
    val recipientIds: Set<UUID> = emptySet(),
    val actorId: UUID? = null,
    val actorName: String? = null,
    val body: String? = null,
    val filePath: String? = null,
    val lineNumber: Int? = null,
    val reviewStatus: ReviewStatus? = null,
    val assigneeId: UUID? = null,
) : GitEvent() {
    override fun identityKey(): Any = "pull-request:$pullRequestId:$action"
}

@Serializable
enum class PullRequestEventAction {
    OPENED,
    UPDATED,
    MERGED,
    CLOSED,
    REOPENED,
    READY_FOR_REVIEW,
    REVIEWED,
    COMMENTED,
    THREAD_RESOLVED,
    ASSIGNED,
    UNASSIGNED,
    SOURCE_UPDATED,
}

/**
 * Emitted whenever a branch or tag ref is created, advanced, or deleted.
 * [recipientIds] contains the repository profiles that default notification
 * pipelines should contact; operators can reshape that list in Studio.
 */
@JobEvent(jobs = [], pubsubChannel = "bosca.git.ref_update")
@Serializable
data class RefUpdateEvent(
    override val repositoryId: UUID,
    val repositoryName: String,
    val ref: String,
    val refName: String,
    val kind: GitRefKind,
    val action: GitRefUpdateAction,
    val beforeSha: String? = null,
    val afterSha: String? = null,
    val actorId: UUID? = null,
    val recipientIds: Set<UUID> = emptySet(),
    val taskKeys: Set<String> = emptySet(),
    val commitMessages: List<String> = emptyList(),
) : GitEvent() {
    override fun identityKey(): Any = "ref-update:$repositoryId:$ref:$action"
}

/** Kind of named Git ref carried by a [RefUpdateEvent]. */
@Serializable
enum class GitRefKind {
    BRANCH,
    TAG,
}

/** Lifecycle operation performed on the ref carried by a [RefUpdateEvent]. */
@Serializable
enum class GitRefUpdateAction {
    CREATED,
    UPDATED,
    DELETED,
}
