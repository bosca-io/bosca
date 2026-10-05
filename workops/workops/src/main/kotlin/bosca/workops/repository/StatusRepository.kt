package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory

/** Persists [Status] rows in `workops.status`. */
@Repository
interface StatusRepository {

    @Query("select * from workops.status order by name")
    suspend fun getAll(): List<Status>

    @Query("select * from workops.status where id = :id")
    suspend fun getById(id: UUID): Status?

    @Query("select * from workops.status where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Status>

    @Query("select * from workops.status where category = (:category)::workops.status_category order by name")
    suspend fun getByCategory(category: StatusCategory): List<Status>

    @Query(
        """
        insert into workops.status (name, description, category, color_hex)
        values (:name, :description, (:category)::workops.status_category, :colorHex)
        returning *
        """
    )
    suspend fun add(status: Status): Status

    @Query(
        """
        update workops.status
        set name = :name,
            description = :description,
            category = (:category)::workops.status_category,
            color_hex = :colorHex,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        category: StatusCategory,
        colorHex: String,
        expectedVersion: Long,
    ): Status?

    @Query("delete from workops.status where id = :id")
    suspend fun deleteById(id: UUID)
}
