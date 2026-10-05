package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.version.Version

/** Persists [Version] rows (R9). */
@Repository
interface VersionRepository {

    @Query("select * from workops.version where id = :id")
    suspend fun getById(id: UUID): Version?

    @Query("select * from workops.version where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Version>

    @Query("select * from workops.version where project_id = :projectId order by sequence_number")
    suspend fun listByProject(projectId: UUID): List<Version>

    @Query("select coalesce(max(sequence_number), 0) from workops.version where project_id = :projectId")
    suspend fun maxSequenceNumber(projectId: UUID): Long

    @Query(
        """
        insert into workops.version (project_id, name, description, start_date, release_date, sequence_number)
        values (:projectId, :name, :description, :startDate, :releaseDate, :sequenceNumber)
        returning *
        """
    )
    suspend fun add(
        projectId: UUID,
        name: String,
        description: String?,
        startDate: OffsetDateTime?,
        releaseDate: OffsetDateTime?,
        sequenceNumber: Int,
    ): Version

    @Query(
        """
        update workops.version
        set name = :name,
            description = :description,
            start_date = :startDate,
            release_date = :releaseDate,
            released = :released,
            archived = :archived,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        startDate: OffsetDateTime?,
        releaseDate: OffsetDateTime?,
        released: Boolean,
        archived: Boolean,
        expectedVersion: Long,
    ): Version?

    /** Returns true when at least one task references this version (R9 deletion guard). */
    @Query(
        """
        select count(*) > 0
        from workops.task
        where :versionId = any(affects_version_ids)
           or :versionId = any(fix_version_ids)
        """
    )
    suspend fun isReferencedByTasks(versionId: UUID): Boolean

    @Query("delete from workops.version where id = :id")
    suspend fun deleteById(id: UUID)
}
