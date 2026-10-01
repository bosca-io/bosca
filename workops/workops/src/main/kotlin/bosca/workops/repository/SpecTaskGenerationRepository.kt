package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.spec.GenerationSource
import bosca.workops.model.spec.SpecTaskGeneration

@Repository
interface SpecTaskGenerationRepository {

    @Query("select * from workops.spec_task_generation where id = :id")
    suspend fun getById(id: UUID): SpecTaskGeneration?

    @Query(
        """
        select * from workops.spec_task_generation
        where spec_id = :specId
        order by created_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listBySpec(specId: UUID, offset: Long, limit: Int): List<SpecTaskGeneration>

    @Query(
        """
        select * from workops.spec_task_generation
        where spec_id = :specId and source = (:source)::workops.generation_source
        order by created_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listBySpecAndSource(
        specId: UUID,
        source: GenerationSource,
        offset: Long,
        limit: Int,
    ): List<SpecTaskGeneration>

    @Query(
        """
        insert into workops.spec_task_generation (
            spec_id, metadata_version, source, agent_session_id,
            generated_task_ids, created_by_principal_id
        ) values (
            :specId, :metadataVersion, (:source)::workops.generation_source, :agentSessionId,
            :generatedTaskIds, :createdByPrincipalId
        )
        returning *
        """
    )
    suspend fun add(generation: SpecTaskGeneration): SpecTaskGeneration
}
