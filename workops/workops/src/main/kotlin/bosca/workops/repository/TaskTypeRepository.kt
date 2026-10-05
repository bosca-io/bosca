package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.task.TaskHierarchyLevel
import bosca.workops.model.task.TaskType

/** Persists [TaskType] rows in `workops.task_type`. */
@Repository
interface TaskTypeRepository {

    @Query("select * from workops.task_type order by name")
    suspend fun getAll(): List<TaskType>

    @Query("select * from workops.task_type where id = :id")
    suspend fun getById(id: UUID): TaskType?

    @Query("select * from workops.task_type where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<TaskType>

    @Query(
        """
        insert into workops.task_type (name, description, icon_key, color_hex, hierarchy_level)
        values (:name, :description, :iconKey, :colorHex, (:hierarchyLevel)::workops.task_hierarchy_level)
        returning *
        """
    )
    suspend fun add(taskType: TaskType): TaskType

    @Query(
        """
        update workops.task_type
        set name = :name,
            description = :description,
            icon_key = :iconKey,
            color_hex = :colorHex,
            hierarchy_level = (:hierarchyLevel)::workops.task_hierarchy_level,
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
        hierarchyLevel: TaskHierarchyLevel,
        expectedVersion: Long,
    ): TaskType?

    @Query("delete from workops.task_type where id = :id")
    suspend fun deleteById(id: UUID)
}
