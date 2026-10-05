package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.Repository as GitRepository
import bosca.git.model.RepositoryContentType
import bosca.serialization.UUID

/**
 * Database access layer for git repository metadata in the `git.repositories` table.
 * Handles CRUD and owner-scoped lookups. DFS pack and ref storage are managed by
 * separate repositories.
 */
@Repository
interface GitRepositoryRepository {

    @Query("select * from git.repositories where id = :id and deleted = false")
    suspend fun findById(id: UUID): GitRepository?

    @Query("select * from git.repositories where id = any(:id) and deleted = false")
    suspend fun findByIds(id: List<UUID>): List<GitRepository>

    @Query("select * from git.repositories where id = :id")
    suspend fun findByIdIncludingDeleted(id: UUID): GitRepository?

    @Query("select * from git.repositories where deleted = false order by updated desc")
    suspend fun findAll(): List<GitRepository>

    @Query("select * from git.repositories order by updated desc")
    suspend fun findAllIncludingArchived(): List<GitRepository>

    @Query("""
        select * from git.repositories
        where owner_id = :ownerId and slug = :repoSlug and deleted = false
    """)
    suspend fun findByOwnerAndSlug(ownerId: UUID, repoSlug: String): GitRepository?

    @Query("""
        select * from git.repositories
        where owner_id = :ownerId and deleted = false
        order by updated desc
    """)
    suspend fun findByOwner(ownerId: UUID): List<GitRepository>

    @Query("""
        select * from git.repositories
        where owner_id = :ownerId
        order by updated desc
    """)
    suspend fun findByOwnerIncludingArchived(ownerId: UUID): List<GitRepository>

    @Query("""
        select * from git.repositories
        where content_type = :contentType::git.repository_content_type and deleted = false
        order by updated desc
    """)
    suspend fun findByContentType(contentType: RepositoryContentType): List<GitRepository>

    @Query("""
        insert into git.repositories (slug, name, description, owner_id, visibility, default_branch, content_type, configuration)
        values (:slug, :name, :description, :ownerId, :visibility::git.visibility, :defaultBranch, :contentType::git.repository_content_type, :configuration::jsonb)
        returning *
    """)
    suspend fun create(repository: GitRepository): GitRepository

    @Query("""
        update git.repositories
        set name = :name, description = :description, visibility = :visibility::git.visibility,
            default_branch = :defaultBranch, content_type = :contentType::git.repository_content_type,
            configuration = :configuration::jsonb, updated = now()
        where id = :id
        returning *
    """)
    suspend fun update(repository: GitRepository): GitRepository

    @Query("""
        update git.repositories set slug = :slug, updated = now() where id = :id and deleted = false returning *
    """)
    suspend fun updateSlug(id: UUID, slug: String): GitRepository?

    @Query("""
        update git.repositories set archived = true, updated = now() where id = :id returning *
    """)
    suspend fun archive(id: UUID): GitRepository?

    @Query("""
        update git.repositories set deleted = true, deleted_at = now(), updated = now() where id = :id returning *
    """)
    suspend fun softDelete(id: UUID): GitRepository?

    @Query("""
        update git.repositories set deleted = false, deleted_at = null, updated = now() where id = :id returning *
    """)
    suspend fun restore(id: UUID): GitRepository?

    @Query("""
        update git.repositories set owner_id = :newOwnerId, updated = now() where id = :id returning *
    """)
    suspend fun transfer(id: UUID, newOwnerId: UUID): GitRepository?

    @Query("""
        update git.repositories set disk_size_bytes = :sizeBytes, updated = now() where id = :id
    """)
    suspend fun updateDiskSize(id: UUID, sizeBytes: Long)

    @Query("""
        update git.repositories set next_pr_number = next_pr_number + 1, updated = now()
        where id = :id returning next_pr_number - 1 as next_pr_number
    """)
    suspend fun incrementPrNumber(id: UUID): Int

    @Query("""
        select id from git.repositories where deleted = false order by updated asc
    """)
    suspend fun findActiveIds(): List<UUID>

    @Query("""
        select * from git.repositories where deleted = true and deleted_at < now() - interval '30 days'
    """)
    suspend fun findExpiredSoftDeletes(): List<GitRepository>

    @Query("delete from git.repositories where id = :id")
    suspend fun hardDelete(id: UUID)

    @Query("""
        select coalesce(sum(disk_size_bytes), 0) from git.repositories
        where owner_id = :ownerId and deleted = false
    """)
    suspend fun sumDiskSizeByOwner(ownerId: UUID): Long?
}
