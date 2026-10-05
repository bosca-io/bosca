package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.serialization.UUID

/**
 * Persists [PersonalizationSignalDefinition]s — the admin config for how profile attributes / segments
 * personalize recommendations. Read by the write-time compute pipeline (by source) and by the admin
 * surface + serving.
 */
@Repository
interface PersonalizationSignalRepository {

    @Query("select * from recommendations.personalization_signals order by priority desc, key asc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<PersonalizationSignalDefinition>

    @Query("select * from recommendations.personalization_signals where enabled = true order by priority desc, key asc")
    suspend fun getEnabled(): List<PersonalizationSignalDefinition>

    @Query("select * from recommendations.personalization_signals where id = :id")
    suspend fun getById(id: UUID): PersonalizationSignalDefinition?

    @Query("select * from recommendations.personalization_signals where key = :key")
    suspend fun getByKey(key: String): PersonalizationSignalDefinition?

    @Query("""
        select * from recommendations.personalization_signals
        where source_type = (:sourceType)::recommendations.signal_source_type and source_id = :sourceId and enabled = true
        order by priority desc, key asc
    """)
    suspend fun getEnabledBySource(sourceType: PersonalizationSignalSourceType, sourceId: String): List<PersonalizationSignalDefinition>

    @Query("""
        insert into recommendations.personalization_signals
            (key, source_type, source_id, expression, value_type, priority, use_as_feature, use_as_cohort, enabled)
        values
            (:key, (:sourceType)::recommendations.signal_source_type, :sourceId, :expression,
             (:valueType)::recommendations.signal_value_type, :priority, :useAsFeature, :useAsCohort, :enabled)
        returning *
    """)
    suspend fun add(definition: PersonalizationSignalDefinition): PersonalizationSignalDefinition

    @Query("""
        update recommendations.personalization_signals
        set key = :key, source_type = (:sourceType)::recommendations.signal_source_type, source_id = :sourceId,
            expression = :expression, value_type = (:valueType)::recommendations.signal_value_type,
            priority = :priority, use_as_feature = :useAsFeature, use_as_cohort = :useAsCohort, enabled = :enabled,
            modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(definition: PersonalizationSignalDefinition): PersonalizationSignalDefinition

    @Query("delete from recommendations.personalization_signals where id = :id")
    suspend fun deleteById(id: UUID)
}
