package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.serialization.UUID

@Repository
interface ExperimentRepository {

    @Query("select * from experimentation.experiments order by created desc limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Experiment>

    @Query("select * from experimentation.experiments where id = :id")
    suspend fun getById(id: UUID): Experiment?

    /**
     * Locks an experiment definition until the current transaction completes.
     * Rollout uses this to serialize its traffic change with definition edits.
     */
    @Query("select * from experimentation.experiments where id = :id for update")
    suspend fun getByIdForUpdate(id: UUID): Experiment?

    @Query("select * from experimentation.experiments where feature_flag_id = :flagId order by created desc")
    suspend fun getByFlagId(flagId: UUID): List<Experiment>

    @Query("""
        select * from experimentation.experiments
        where feature_flag_id = :flagId
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun getByFlagId(flagId: UUID, offset: Long, limit: Int): List<Experiment>

    @Query("select * from experimentation.experiments where feature_flag_id = :flagId and status = 'running'")
    suspend fun getRunningByFlagId(flagId: UUID): List<Experiment>

    @Query("""
        select * from experimentation.experiments
        where feature_flag_id = :flagId
          and status = 'running'
          and coalesce(targeting_rule_id, '') = coalesce(:targetingRuleId, '')
        limit 1
    """)
    suspend fun getRunningByFlagAndRule(flagId: UUID, targetingRuleId: String?): Experiment?

    @Query("select * from experimentation.experiments where status = 'running'")
    suspend fun getAllRunning(): List<Experiment>

    @Query("select * from experimentation.experiments where exclusion_layer_id = :layerId order by created desc")
    suspend fun getByLayerId(layerId: UUID): List<Experiment>

    @Query("""
        select * from experimentation.experiments
        where exclusion_layer_id = :layerId
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun getByLayerId(layerId: UUID, offset: Long, limit: Int): List<Experiment>

    @Query("""
        insert into experimentation.experiments (feature_flag_id, name, description, hypothesis, status, targeting_rule_id, control_variation_key, excluded_principal_ids, activation_filter, exclusion_layer_id, start_date, end_date, target_sample_size, rollout_policy, analysis_method, bayesian_prior)
        values (:featureFlagId, :name, :description, :hypothesis, (:status)::experimentation.experiment_status, :targetingRuleId, :controlVariationKey, :excludedPrincipalIds, :activationFilter::jsonb, :exclusionLayerId, :startDate, :endDate, :targetSampleSize, :rolloutPolicy::jsonb, (:analysisMethod)::experimentation.analysis_method, :bayesianPrior::jsonb)
        returning *
    """)
    suspend fun add(experiment: Experiment): Experiment

    @Query("""
        update experimentation.experiments
        set name = :name, description = :description, hypothesis = :hypothesis,
            targeting_rule_id = :targetingRuleId,
            control_variation_key = :controlVariationKey,
            excluded_principal_ids = :excludedPrincipalIds,
            activation_filter = :activationFilter::jsonb,
            exclusion_layer_id = :exclusionLayerId, start_date = :startDate,
            end_date = :endDate, target_sample_size = :targetSampleSize,
            rollout_policy = :rolloutPolicy::jsonb,
            analysis_method = (:analysisMethod)::experimentation.analysis_method,
            bayesian_prior = :bayesianPrior::jsonb,
            analysis_revision = analysis_revision + 1,
            modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(experiment: Experiment): Experiment

    @Query("update experimentation.experiments set status = (:status)::experimentation.experiment_status, modified = now() where id = :id returning *")
    suspend fun updateStatus(id: UUID, status: ExperimentStatus): Experiment?

    @Query("update experimentation.experiments set analysis_revision = analysis_revision + 1, modified = now() where id = :id")
    suspend fun touchAnalysisRevision(id: UUID)

    @Query("delete from experimentation.experiments where id = :id")
    suspend fun deleteById(id: UUID)
}
