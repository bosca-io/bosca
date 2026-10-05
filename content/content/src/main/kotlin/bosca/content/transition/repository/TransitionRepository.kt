package bosca.content.transition.repository

import bosca.content.transition.model.Transition
import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface TransitionRepository {

    @Query("select * from state_transitions")
    suspend fun getAll(): List<Transition>

    @Query("select * from state_transitions where from_state_id = :fromStateId and to_state_id = :toStateId")
    suspend fun findByFromStateIdAndToStateId(fromStateId: String, toStateId: String): Transition?

    @Query("delete from state_transitions where from_state_id = :fromStateId and to_state_id = :toStateId")
    suspend fun deleteByFromStateIdAndToStateId(fromStateId: String, toStateId: String)

    @Query("insert into state_transitions (from_state_id, to_state_id, description, enter_job_name, exit_job_name, configuration) values (:fromStateId, :toStateId, :description, :enterJobName, :exitJobName, :configuration) returning *")
    suspend fun add(transition: Transition): Transition

    @Query("update state_transitions set description = :description, enter_job_name = :enterJobName, exit_job_name = :exitJobName, configuration = :configuration where from_state_id = :fromStateId and to_state_id = :toStateId returning *")
    suspend fun update(transition: Transition): Transition
}
