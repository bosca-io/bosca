package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service

data class CommitFileInput(
    val repositoryId: UUID,
    val branch: String = "main",
    val path: String,
    val content: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
)

data class CommitFilesInput(
    val repositoryId: UUID,
    val branch: String = "main",
    /** Repo-relative path → full new content; all files land in ONE commit. */
    val files: Map<String, String>,
    val message: String,
    val authorName: String,
    val authorEmail: String,
)

data class CommitFileResult(
    val commitSha: String,
    val branch: String,
    val path: String,
)

data class DeleteFileInput(
    val repositoryId: UUID,
    val branch: String = "main",
    val path: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
)

data class CreateTagInput(
    val repositoryId: UUID,
    /** Tag name without the `refs/tags/` prefix, e.g. `v2.1.0`. */
    val tag: String,
    /** Ref or SHA to tag; defaults to the main branch tip. */
    val targetRef: String = "refs/heads/main",
    val message: String,
    val taggerName: String,
    val taggerEmail: String,
    /** The principal attributed as the pusher in the ref-update fan-out — the pipeline runs this
     *  tag triggers record it as their initiating principal. */
    val pusherPrincipalId: UUID? = null,
    /** Opt-in re-run safety (`uses: tag`): an existing tag at the SAME commit returns a
     *  `created = false` no-op instead of failing. Off by default — an ordinary caller creating a
     *  tag that already exists is an error and still throws. A tag at a DIFFERENT commit always
     *  fails, regardless. */
    val allowExisting: Boolean = false,
)

data class CreateTagResult(
    val tag: String,
    /** Fully-qualified tag ref, `refs/tags/<tag>`. */
    val ref: String,
    /** SHA of the tagged commit. */
    val commitSha: String,
    /** SHA of the annotated-tag object (the pre-existing one on an idempotent no-op). */
    val tagSha: String,
    /** False when the tag already existed at the same commit — the idempotent no-op. */
    val created: Boolean = true,
)

interface RepositoryWriteService : Service {

    suspend fun commitFile(input: CommitFileInput): CommitFileResult

    /**
     * Commits [input] and attributes every ref-update side effect, including triggered CI runs, to
     * [initiatingPrincipalId].
     *
     * This overload preserves the existing unattributed entry point for compatibility while giving
     * authenticated callers an explicit identity path.
     */
    suspend fun commitFile(input: CommitFileInput, initiatingPrincipalId: UUID): CommitFileResult

    /** Commits several files as ONE commit (e.g. a release pin across values files). */
    suspend fun commitFiles(input: CommitFilesInput): CommitFileResult

    /**
     * Commits [input] as one commit and attributes triggered ref-update work to
     * [initiatingPrincipalId].
     */
    suspend fun commitFiles(input: CommitFilesInput, initiatingPrincipalId: UUID): CommitFileResult

    suspend fun deleteFile(input: DeleteFileInput): CommitFileResult

    /**
     * Deletes [input]'s file in a commit and attributes triggered ref-update work to
     * [initiatingPrincipalId].
     */
    suspend fun deleteFile(input: DeleteFileInput, initiatingPrincipalId: UUID): CommitFileResult

    /**
     * Creates an annotated tag at [CreateTagInput.targetRef] and fires the ref-update
     * notifier — the same post-receive path a pushed tag takes, so downstream webhooks
     * and TAG-triggered pipelines run. Fails if the tag already exists — unless the caller
     * opted into [CreateTagInput.allowExisting], where a tag at the SAME commit becomes a
     * `created = false` no-op (notifier not re-fired — re-run safe) and a tag
     * at a DIFFERENT commit still fails loudly naming both commits.
     */
    suspend fun createTag(input: CreateTagInput): CreateTagResult

    /**
     * Deletes the tag ref `refs/tags/[tag]` and fires the ref-update notifier — the same
     * post-receive path a pushed deletion takes, so TAG_DELETED webhooks dispatch. Fails
     * if the tag doesn't exist.
     */
    suspend fun deleteTag(repositoryId: UUID, tag: String)

    /**
     * Deletes the branch ref `refs/heads/[branch]` and fires the ref-update notifier — the
     * same post-receive path a pushed deletion takes, so BRANCH_DELETED webhooks dispatch.
     * Fails if the branch doesn't exist. Callers gate the default branch and protection
     * rules — this is the raw ref operation.
     */
    suspend fun deleteBranch(repositoryId: UUID, branch: String)

    suspend fun readFile(repositoryId: UUID, ref: String, path: String): String?

    /**
     * Transfers original Git objects and reconciles one branch/tag under the repository write lock.
     * A stale occurrence cannot overwrite a newer source. Independent target edits produce a
     * conflict; deletion and force updates require an unchanged synchronized target. An unresolved
     * initial conflict cannot recreate an independently deleted target. Remote writes
     * use an expected-old lease. Inbound writes notify the ordinary push path with [input]'s principal.
     * Callers authorize that principal and supply the destination's current protection rule; the
     * actual update is checked under the write lock. PR merge imports may defer build attribution.
     */
    suspend fun synchronizeRef(input: RefSynchronizationInput): RefSynchronizationResult

    /** Reads both repositories' branch/tag refs for reconciliation; it does not change refs or emit events. */
    suspend fun compareRefs(repositoryId: UUID, remoteUrl: String, token: String): List<RefComparison>
}
