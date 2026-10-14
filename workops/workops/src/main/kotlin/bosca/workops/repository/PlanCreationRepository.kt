package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.spec.PlanCreation

@Repository
interface PlanCreationRepository {
    @Query("insert into workops.plan_creation (key, principal_id, fingerprint) values (:key, :principalId, :fingerprint) on conflict (principal_id, key) do nothing", returnUpdateCount = true)
    suspend fun reserve(key: UUID, principalId: UUID, fingerprint: String): Int

    @Query("select * from workops.plan_creation where key = :key and principal_id = :principalId for update")
    suspend fun getForUpdate(key: UUID, principalId: UUID): PlanCreation?

    @Query("update workops.plan_creation set target_id = :targetId where key = :key and principal_id = :principalId", returnUpdateCount = true)
    suspend fun complete(key: UUID, principalId: UUID, targetId: UUID): Int
}
