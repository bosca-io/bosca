package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.ExperimentResult
import bosca.serialization.UUID

@Repository
interface ExperimentResultRepository {

    @Query("select * from experimentation.experiment_results where experiment_id = :experimentId order by variation_key, goal_id")
    suspend fun getByExperimentId(experimentId: UUID): List<ExperimentResult>

    @Query("""
        select * from experimentation.experiment_results
        where experiment_id = :experimentId
        order by variation_key, goal_id
        limit :limit offset :offset
    """)
    suspend fun getByExperimentId(experimentId: UUID, offset: Long, limit: Int): List<ExperimentResult>

    @Query("""
        insert into experimentation.experiment_results (
            experiment_id, variation_key, goal_id, assignments, impressions, observation_count, conversions, conversion_rate,
            confidence_level, lift_over_control, mean, variance,
            probability_beats_control, expected_loss, adjusted_mean, adjusted_variance
        )
        values (
            :experimentId, :variationKey, :goalId, :assignments, :impressions, :observationCount, :conversions, :conversionRate,
            :confidenceLevel, :liftOverControl, :mean, :variance,
            :probabilityBeatsControl, :expectedLoss, :adjustedMean, :adjustedVariance
        )
        on conflict (experiment_id, variation_key, goal_id)
        do update set assignments = :assignments, impressions = :impressions, observation_count = :observationCount,
                      conversions = :conversions, conversion_rate = :conversionRate,
                      confidence_level = :confidenceLevel, lift_over_control = :liftOverControl,
                      mean = :mean, variance = :variance,
                      probability_beats_control = :probabilityBeatsControl,
                      expected_loss = :expectedLoss,
                      adjusted_mean = :adjustedMean,
                      adjusted_variance = :adjustedVariance,
                      updated_at = now()
        returning *
    """)
    suspend fun upsert(result: ExperimentResult): ExperimentResult

    @Query("delete from experimentation.experiment_results where experiment_id = :experimentId")
    suspend fun deleteByExperimentId(experimentId: UUID)
}
