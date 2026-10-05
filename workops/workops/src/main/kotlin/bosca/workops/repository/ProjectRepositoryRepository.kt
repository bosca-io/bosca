package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.project.ProjectRepository

/** Persists the git repositories a project owns (the release relay reads them to tag/build). */
@Repository
interface ProjectRepositoryRepository {

    @Query("select * from workops.project_repository where project_id = :projectId order by repository_id")
    suspend fun list(projectId: UUID): List<ProjectRepository>

    /** Reverse lookup: the projects that own [repositoryId] — how a git-side event finds its programs. */
    @Query("select * from workops.project_repository where repository_id = :repositoryId order by project_id")
    suspend fun listByRepository(repositoryId: UUID): List<ProjectRepository>

    // Idempotent: re-adding the same (project, repository) returns the existing row rather than erroring.
    @Query(
        """
        insert into workops.project_repository (project_id, repository_id)
        values (:projectId, :repositoryId)
        on conflict (project_id, repository_id) do update set repository_id = excluded.repository_id
        returning *
        """,
    )
    suspend fun add(projectId: UUID, repositoryId: UUID): ProjectRepository

    @Query("delete from workops.project_repository where id = :id and project_id = :projectId")
    suspend fun remove(projectId: UUID, id: UUID)
}
