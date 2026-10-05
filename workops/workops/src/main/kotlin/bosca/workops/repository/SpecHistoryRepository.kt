package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.audit.RequirementHistoryEntry
import bosca.workops.model.audit.SpecHistoryEntry
import kotlinx.serialization.json.JsonElement

@Repository
interface SpecHistoryRepository {

    @Query(
        """
        insert into workops.spec_history (
            spec_id, changed_at, changed_by_principal_id, changed_by_profile_id, changes
        ) values (
            :specId, :changedAt, :changedByPrincipalId, :changedByProfileId, :changes::jsonb
        )
        returning *
        """
    )
    suspend fun add(
        specId: UUID,
        changedAt: OffsetDateTime,
        changedByPrincipalId: UUID,
        changedByProfileId: UUID?,
        changes: JsonElement,
    ): SpecHistoryEntry

    @Query(
        """
        select * from workops.spec_history
        where spec_id = :specId
        order by changed_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listBySpec(specId: UUID, offset: Long, limit: Int): List<SpecHistoryEntry>
}

@Repository
interface RequirementHistoryRepository {

    @Query(
        """
        insert into workops.requirement_history (
            requirement_id, changed_at, changed_by_principal_id, changed_by_profile_id, changes
        ) values (
            :requirementId, :changedAt, :changedByPrincipalId, :changedByProfileId, :changes::jsonb
        )
        returning *
        """
    )
    suspend fun add(
        requirementId: UUID,
        changedAt: OffsetDateTime,
        changedByPrincipalId: UUID,
        changedByProfileId: UUID?,
        changes: JsonElement,
    ): RequirementHistoryEntry

    @Query(
        """
        select * from workops.requirement_history
        where requirement_id = :requirementId
        order by changed_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByRequirement(requirementId: UUID, offset: Long, limit: Int): List<RequirementHistoryEntry>
}
