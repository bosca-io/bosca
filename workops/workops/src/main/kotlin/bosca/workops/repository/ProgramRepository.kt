package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.project.Program

/**
 * Persists [Program] rows in `workops.program`. WorkOpsPrograms are unique by
 * `(portfolio_id, key)` rather than globally — every lookup that takes
 * a `key` therefore also takes a `portfolioId`.
 */
@Repository
interface ProgramRepository {

    @Query("select * from workops.program order by key")
    suspend fun getAll(): List<Program>

    @Query("select * from workops.program where portfolio_id = :portfolioId order by key limit :limit offset :offset")
    suspend fun getByPortfolio(portfolioId: UUID, offset: Long, limit: Int): List<Program>

    @Query("select * from workops.program where id = :id")
    suspend fun getById(id: UUID): Program?

    @Query("select * from workops.program where portfolio_id = :portfolioId and key = :key")
    suspend fun getByKey(portfolioId: UUID, key: String): Program?

    @Query("select * from workops.program where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Program>

    @Query(
        """
        insert into workops.program (portfolio_id, key, name, description, owner_profile_id, start_date, target_date)
        values (:portfolioId, :key, :name, :description, :ownerProfileId, :startDate, :targetDate)
        returning *
        """
    )
    suspend fun add(program: Program): Program

    @Query(
        """
        update workops.program
        set name = :name,
            description = :description,
            owner_profile_id = :ownerProfileId,
            start_date = :startDate,
            target_date = :targetDate,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        ownerProfileId: UUID,
        startDate: OffsetDateTime?,
        targetDate: OffsetDateTime?,
        expectedVersion: Long,
    ): Program?

    @Query(
        """
        update workops.program
        set archived_at = now(), modified_at = now(), version = version + 1
        where id = :id and archived_at is null and version = :expectedVersion
        returning *
        """
    )
    suspend fun archive(id: UUID, expectedVersion: Long): Program?

    @Query(
        """
        update workops.program
        set archived_at = null, modified_at = now(), version = version + 1
        where id = :id and archived_at is not null and version = :expectedVersion
        returning *
        """
    )
    suspend fun unarchive(id: UUID, expectedVersion: Long): Program?
}
