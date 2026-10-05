package bosca.git.service

import bosca.git.model.BranchInfo
import bosca.git.model.CreateRepositoryInput
import bosca.git.model.ForkRepositoryInput
import bosca.git.model.Repository
import bosca.git.model.RepositoryContentType
import bosca.git.model.UpdateRepositoryInput
import bosca.security.model.EntityPermission
import bosca.security.model.PermissibleEntity
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages the lifecycle of Bosca-hosted git repositories including creation,
 * update, forking, archival, soft-delete, restore, and transfer. Implementations
 * coordinate between the relational metadata in PostgreSQL and the DFS object
 * storage for git packfiles.
 */
interface RepositoryService : PermissionService<Repository, UUID> {

    /** Creates a repository, optionally initializing it with a README, .gitignore, and LICENSE. */
    suspend fun create(input: CreateRepositoryInput): Repository

    /** Updates mutable fields on an existing repository. */
    suspend fun update(id: UUID, input: UpdateRepositoryInput): Repository

    /**
     * Renames a repository by changing its [Repository.slug]. The slug forms part of the clone URL
     * (`https://{host}/{owner}/{slug}.git`), so this is a breaking change for anyone who has already
     * cloned the repository — existing remotes must be re-pointed at the new URL. Physical git storage
     * is keyed by repository id and is unaffected. Throws if [newSlug] is malformed or is already taken
     * within the owner's namespace. Renaming to the current slug is a no-op that returns the repository
     * unchanged.
     */
    suspend fun rename(id: UUID, newSlug: String): Repository

    /** Marks a repository as archived, preventing further pushes. */
    suspend fun archive(id: UUID): Repository

    /** Soft-deletes a repository. Data is retained for 30 days before purge. */
    suspend fun delete(id: UUID): Repository

    /** Restores a previously soft-deleted repository. */
    suspend fun restore(id: UUID): Repository

    /** Transfers ownership of a repository to a new owner. */
    suspend fun transfer(id: UUID, newOwnerId: UUID): Repository

    /** Forks a repository, sharing the parent's object store via alternates. */
    suspend fun fork(input: ForkRepositoryInput): Repository

    /** Resolves a repository by its owner slug and repository slug — the primary URL lookup path. */
    suspend fun findByOwnerAndSlug(ownerSlug: String, repoSlug: String): Repository?

    /** Finds a non-deleted repository by its unique identifier. */
    suspend fun findById(id: UUID): Repository?

    /** Finds a repository by its unique identifier, including soft-deleted repositories. */
    suspend fun findByIdIncludingDeleted(id: UUID): Repository?

    /** Lists all non-deleted repositories. When [includeArchived] is true, archived repositories are included. */
    suspend fun findAll(includeArchived: Boolean = false): List<Repository>

    /** Lists repositories owned by the given profile or organization. */
    suspend fun findByOwner(ownerId: UUID, includeArchived: Boolean = false): List<Repository>

    /** Lists repositories by content type across all owners. */
    suspend fun findByContentType(contentType: RepositoryContentType): List<Repository>

    /** Grants a permission action to a security group on a repository. */
    suspend fun addPermission(repositoryId: UUID, groupId: UUID, action: PermissionAction)

    /** Revokes a permission action from a security group on a repository. */
    suspend fun removePermission(repositoryId: UUID, groupId: UUID, action: PermissionAction)

    /**
     * Creates a new branch in the repository pointing to the same commit as [sourceRef].
     * The source ref can be a branch name, a tag name, or a full 40-character SHA.
     * Throws if the source ref cannot be resolved or if the branch already exists.
     */
    suspend fun createBranch(repositoryId: UUID, branchName: String, sourceRef: String): BranchInfo

    /**
     * Creates a branch and attributes its ref-update events and triggered pipelines to
     * [initiatingPrincipalId]. The unattributed overload remains available to non-request callers.
     */
    suspend fun createBranch(
        repositoryId: UUID,
        branchName: String,
        sourceRef: String,
        initiatingPrincipalId: UUID,
    ): BranchInfo
}
