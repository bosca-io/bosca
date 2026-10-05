package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.task.Priority

/** Persists [Priority] rows in `workops.priority`. */
@Repository
interface PriorityRepository {

    @Query("select * from workops.priority order by display_order")
    suspend fun getAll(): List<Priority>

    @Query("select * from workops.priority where id = :id")
    suspend fun getById(id: UUID): Priority?

    @Query("select * from workops.priority where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Priority>

    @Query(
        """
        insert into workops.priority (name, description, icon_key, color_hex, display_order)
        values (:name, :description, :iconKey, :colorHex, :displayOrder)
        returning *
        """
    )
    suspend fun add(priority: Priority): Priority

    @Query(
        """
        update workops.priority
        set name = :name,
            description = :description,
            icon_key = :iconKey,
            color_hex = :colorHex,
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
        iconKey: String,
        colorHex: String,
        displayOrder: Int,
        expectedVersion: Long,
    ): Priority?

    @Query("delete from workops.priority where id = :id")
    suspend fun deleteById(id: UUID)
}
