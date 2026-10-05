package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.serialization.UUID

@Repository
interface FeatureFlagRepository {

    @Query("select * from experimentation.feature_flags order by created desc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<FeatureFlag>

    @Query("select * from experimentation.feature_flags where status = 'enabled' order by key")
    suspend fun getAllActive(): List<FeatureFlag>

    @Query("select * from experimentation.feature_flags where id = :id")
    suspend fun getById(id: UUID): FeatureFlag?

    @Query("select * from experimentation.feature_flags where key = :key")
    suspend fun getByKey(key: String): FeatureFlag?

    @Query("""
        insert into experimentation.feature_flags
            (key, name, description, type, status, variations, default_variation_key, targeting_rules, salt)
        values
            (:key, :name, :description, (:type)::experimentation.flag_type, (:status)::experimentation.flag_status,
             :variations::jsonb, :defaultVariationKey, :targetingRules::jsonb, gen_random_uuid()::text)
        returning *
    """)
    suspend fun add(flag: FeatureFlag): FeatureFlag

    @Query("""
        update experimentation.feature_flags
        set key = :key, name = :name, description = :description,
            type = (:type)::experimentation.flag_type, status = (:status)::experimentation.flag_status,
            variations = :variations::jsonb, default_variation_key = :defaultVariationKey,
            targeting_rules = :targetingRules::jsonb,
            modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(flag: FeatureFlag): FeatureFlag

    @Query("update experimentation.feature_flags set status = (:status)::experimentation.flag_status, modified = now() where id = :id returning *")
    suspend fun updateStatus(id: UUID, status: FlagStatus): FeatureFlag?

    @Query("update experimentation.feature_flags set salt = gen_random_uuid()::text, modified = now() where id = :id returning *")
    suspend fun regenerateSalt(id: UUID): FeatureFlag?

    @Query("delete from experimentation.feature_flags where id = :id returning *")
    suspend fun deleteById(id: UUID): FeatureFlag?
}
