package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecContextType
import kotlinx.serialization.json.JsonElement

@Repository
interface SpecContextRepository {

    @Query("select * from workops.spec_context where id = :id")
    suspend fun getById(id: UUID): SpecContext?

    @Query(
        """
        select * from workops.spec_context
        where spec_id = :specId
        order by created_at desc
        """
    )
    suspend fun listBySpec(specId: UUID): List<SpecContext>

    @Query(
        """
        select * from workops.spec_context
        where spec_id = :specId
          and context_type = (:contextType)::workops.spec_context_type
        order by created_at desc
        """
    )
    suspend fun listBySpecAndType(specId: UUID, contextType: SpecContextType): List<SpecContext>

    @Query(
        """
        select * from workops.spec_context
        where context_type = (:contextType)::workops.spec_context_type
          and target_id = :targetId
        order by created_at desc
        """
    )
    suspend fun listByTarget(contextType: SpecContextType, targetId: String): List<SpecContext>

    @Query(
        """
        insert into workops.spec_context (
            spec_id, context_type, target_id, label, attributes, added_by_profile_id
        ) values (
            :specId, (:contextType)::workops.spec_context_type, :targetId, :label, :attributes::jsonb, :addedByProfileId
        )
        returning *
        """
    )
    suspend fun add(
        specId: UUID,
        contextType: SpecContextType,
        targetId: String,
        label: String?,
        attributes: JsonElement?,
        addedByProfileId: UUID,
    ): SpecContext

    @Query("delete from workops.spec_context where id = :id and spec_id = :specId")
    suspend fun delete(id: UUID, specId: UUID)

    @Query("delete from workops.spec_context where spec_id = :specId")
    suspend fun deleteAllBySpec(specId: UUID)
}
