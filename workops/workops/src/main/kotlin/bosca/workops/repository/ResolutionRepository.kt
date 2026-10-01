package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.task.Resolution

/** Persists [Resolution] rows in `workops.resolution`. */
@Repository
interface ResolutionRepository {

    @Query("select * from workops.resolution order by display_order")
    suspend fun getAll(): List<Resolution>

    @Query("select * from workops.resolution where id = :id")
    suspend fun getById(id: UUID): Resolution?

    @Query("select * from workops.resolution where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Resolution>

    @Query(
        """
        insert into workops.resolution (name, description, display_order)
        values (:name, :description, :displayOrder)
        returning *
        """
    )
    suspend fun add(resolution: Resolution): Resolution

    @Query(
        """
        update workops.resolution
        set name = :name,
            description = :description,
            display_order = :displayOrder,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        displayOrder: Int,
        expectedVersion: Long,
    ): Resolution?

    @Query("delete from workops.resolution where id = :id")
    suspend fun deleteById(id: UUID)
}
