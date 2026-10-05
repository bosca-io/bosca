package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio

/**
 * Persists [Portfolio] rows in `workops.portfolio`. Every mutation is
 * optimistic-locked through the row's `version` column per the
 * Excellence Bar non-negotiable in `specs/workops/requirements.md` —
 * a no-rows-returned outcome from [update] / [archive] / [unarchive]
 * is the canonical signal of a stale write attempt.
 */
@Repository
interface PortfolioRepository {

    @Query("select * from workops.portfolio order by key limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Portfolio>

    @Query("select * from workops.portfolio where id = :id")
    suspend fun getById(id: UUID): Portfolio?

    @Query("select * from workops.portfolio where key = :key")
    suspend fun getByKey(key: String): Portfolio?

    @Query("select * from workops.portfolio where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Portfolio>

    @Query(
        """
        insert into workops.portfolio (key, name, description, owner_profile_id)
        values (:key, :name, :description, :ownerProfileId)
        returning *
        """
    )
    suspend fun add(portfolio: Portfolio): Portfolio

    @Query(
        """
        update workops.portfolio
        set name = :name,
            description = :description,
            owner_profile_id = :ownerProfileId,
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
        expectedVersion: Long,
    ): Portfolio?

    @Query(
        """
        update workops.portfolio
        set archived_at = now(), modified_at = now(), version = version + 1
        where id = :id and archived_at is null and version = :expectedVersion
        returning *
        """
    )
    suspend fun archive(id: UUID, expectedVersion: Long): Portfolio?

    @Query(
        """
        update workops.portfolio
        set archived_at = null, modified_at = now(), version = version + 1
        where id = :id and archived_at is not null and version = :expectedVersion
        returning *
        """
    )
    suspend fun unarchive(id: UUID, expectedVersion: Long): Portfolio?
}
