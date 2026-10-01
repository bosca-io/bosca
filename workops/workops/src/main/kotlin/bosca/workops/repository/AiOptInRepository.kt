package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface AiOptInRepository {

    @Query("select enabled from workops.ai_org_settings where id = '00000000-0000-0000-0000-000000000001'")
    suspend fun orgEnabled(): Boolean

    @Query(
        """
        update workops.ai_org_settings
        set enabled = :enabled, last_modified = now()
        where id = '00000000-0000-0000-0000-000000000001'
        """
    )
    suspend fun setOrgEnabled(enabled: Boolean)

    @Query("select ai_opt_in from workops.project where id = :projectId")
    suspend fun projectEnabled(projectId: UUID): Boolean

    @Query(
        """
        update workops.project set ai_opt_in = :enabled, modified_at = now()
        where id = :projectId
        """
    )
    suspend fun setProjectEnabled(projectId: UUID, enabled: Boolean)

    @Query("select ai_opt_in from workops.task where id = :taskId")
    suspend fun taskOverride(taskId: UUID): Boolean?

    @Query(
        """
        update workops.task set ai_opt_in = :enabled, modified_at = now()
        where id = :taskId
        """
    )
    suspend fun setTaskOverride(taskId: UUID, enabled: Boolean?)
}
