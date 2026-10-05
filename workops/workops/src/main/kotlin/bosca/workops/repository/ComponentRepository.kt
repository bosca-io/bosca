package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.component.Component
import bosca.workops.model.component.ComponentAssigneeMode

/** Persists [Component] rows (R9). */
@Repository
interface ComponentRepository {

    @Query("select * from workops.component where id = :id")
    suspend fun getById(id: UUID): Component?

    @Query("select * from workops.component where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Component>

    @Query("select * from workops.component where project_id = :projectId order by name")
    suspend fun listByProject(projectId: UUID): List<Component>

    @Query(
        """
        insert into workops.component
            (project_id, name, description, default_assignee_profile_id, lead_profile_id, assignee_mode)
        values
            (:projectId, :name, :description, :defaultAssigneeProfileId, :leadProfileId, (:assigneeMode)::workops.component_assignee_mode)
        returning *
        """
    )
    suspend fun add(
        projectId: UUID,
        name: String,
        description: String?,
        defaultAssigneeProfileId: UUID?,
        leadProfileId: UUID?,
        assigneeMode: ComponentAssigneeMode,
    ): Component

    @Query("delete from workops.component where id = :id")
    suspend fun deleteById(id: UUID)
}
