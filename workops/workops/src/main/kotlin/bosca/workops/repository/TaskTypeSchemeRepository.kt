package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.task.TaskTypeScheme

/**
 * Persists [TaskTypeScheme] rows in `workops.task_type_scheme`.
 *
 * The optimistic-locked [update] takes the whole [TaskTypeScheme]
 * model as its single parameter (the model path's flattening lets
 * `task_type_ids: List<UUID>` ride along — multi-param queries can
 * not bind a `List` because the KSP repository generator rejects
 * mixed model / collection parameter shapes). Callers pre-load the
 * row, mutate it as an immutable copy, and call [update]; a `null`
 * result means another writer bumped the version first.
 */
@Repository
interface TaskTypeSchemeRepository {

    @Query("select * from workops.task_type_scheme order by name")
    suspend fun getAll(): List<TaskTypeScheme>

    @Query("select * from workops.task_type_scheme where id = :id")
    suspend fun getById(id: UUID): TaskTypeScheme?

    @Query("select * from workops.task_type_scheme where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<TaskTypeScheme>

    @Query(
        """
        insert into workops.task_type_scheme (name, description, task_type_ids, default_task_type_id)
        values (:name, :description, :taskTypeIds, :defaultTaskTypeId)
        returning *
        """
    )
    suspend fun add(scheme: TaskTypeScheme): TaskTypeScheme

    @Query(
        """
        update workops.task_type_scheme
        set name = :name,
            description = :description,
            task_type_ids = :taskTypeIds,
            default_task_type_id = :defaultTaskTypeId,
            version = version + 1
        where id = :id and version = :version
        returning *
        """
    )
    suspend fun update(scheme: TaskTypeScheme): TaskTypeScheme?

    @Query("delete from workops.task_type_scheme where id = :id")
    suspend fun deleteById(id: UUID)
}
