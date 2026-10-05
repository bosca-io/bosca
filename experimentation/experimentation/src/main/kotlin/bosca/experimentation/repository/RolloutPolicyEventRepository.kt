package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.serialization.UUID

/**
 * Append-only audit log for the rollout controller's decisions. See
 * [RolloutPolicyEvent] for the row shape and the experiments-v2 spec
 * for why every controller wake-up (including holds) writes one row.
 */
@Repository
interface RolloutPolicyEventRepository {

    @Query("""
        insert into experimentation.rollout_policy_events
            (experiment_id, action, reason, old_weights, new_weights)
        values
            (:experimentId, (:action)::experimentation.rollout_policy_action, :reason, :oldWeights::jsonb, :newWeights::jsonb)
        returning *
    """)
    suspend fun add(event: RolloutPolicyEvent): RolloutPolicyEvent

    @Query("""
        select * from experimentation.rollout_policy_events
        where experiment_id = :experimentId
        order by created desc
        limit :limit offset :offset
    """)
    suspend fun getByExperimentId(experimentId: UUID, offset: Long, limit: Int): List<RolloutPolicyEvent>

    @Query("""
        select * from experimentation.rollout_policy_events
        where experiment_id = :experimentId
        order by created desc
        limit 1
    """)
    suspend fun getLatest(experimentId: UUID): RolloutPolicyEvent?
}
