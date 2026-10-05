package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.milestone.Milestone

/** Persists [Milestone] rows (R9). */
@Repository
interface MilestoneRepository {

    @Query("select * from workops.milestone where id = :id")
    suspend fun getById(id: UUID): Milestone?

    @Query("select * from workops.milestone where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Milestone>

    @Query("select * from workops.milestone where program_id = :programId order by name")
    suspend fun listByProgram(programId: UUID): List<Milestone>

    @Query(
        """
        insert into workops.milestone (program_id, name, description, target_date)
        values (:programId, :name, :description, :targetDate)
        returning *
        """
    )
    suspend fun add(
        programId: UUID,
        name: String,
        description: String?,
        targetDate: OffsetDateTime?,
    ): Milestone

    @Query(
        """
        update workops.milestone
        set state = ('closed')::workops.milestone_state, closed_at = now(), version = version + 1
        where id = :id and version = :expectedVersion and state = 'open'
        returning *
        """
    )
    suspend fun close(id: UUID, expectedVersion: Long): Milestone?

    @Query("delete from workops.milestone where id = :id")
    suspend fun deleteById(id: UUID)
}
