package bosca.git.service

import bosca.git.model.GitHubSyncResult
import bosca.git.model.BranchProtectionRule
import bosca.serialization.UUID

enum class RefSynchronizationDirection { INBOUND, OUTBOUND }

/** Credentials are transport-only and must never be persisted with synchronization state. */
class RefSynchronizationInput(
    val repositoryId: UUID,
    val remoteUrl: String,
    val token: String,
    val ref: String,
    val beforeSha: String?,
    val afterSha: String?,
    val synchronizedSha: String?,
    val hasSynchronized: Boolean,
    val direction: RefSynchronizationDirection,
    val principalId: UUID? = null,
    /** An unresolved initial conflict prevents a missing target from being treated as a new ref. */
    val hasConflict: Boolean = false,
    /** The actual old ref value of an anonymous import that this verified push may attribute. */
    val unattributedBeforeSha: String? = null,
    /** The destination's current branch rule, evaluated against the actual ref update under its write lock. */
    val protection: BranchProtectionRule? = null,
    /** Only a paired PR whose Bosca merge checks passed may satisfy a required-PR rule. */
    val pullRequestMerge: Boolean = false,
    /** PR merge imports preserve attribution but defer CI authorization to their verified push occurrence. */
    val triggerBuild: Boolean = true,
)

data class RefSynchronizationResult(
    val result: GitHubSyncResult,
    val boscaSha: String?,
    val remoteSha: String?,
    /** The target value before an applied write; null means the ref was created. */
    val beforeSha: String? = null,
)

/** Current values of a branch/tag on the two repositories, before any synchronization write. */
data class RefComparison(val ref: String, val localSha: String?, val remoteSha: String?)
