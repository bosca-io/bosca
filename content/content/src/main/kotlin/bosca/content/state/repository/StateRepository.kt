package bosca.content.state.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.content.state.model.State

@Repository
interface StateRepository {

    @Query("select * from states order by name")
    suspend fun getAll(): List<State>

    @Query("select * from states where id = :id")
    suspend fun getById(id: String): State?

    @Query("insert into states (id, name, description, type, configuration, job_name) values (:id, :name, :description, :type, :configuration, :jobName) returning *")
    suspend fun add(state: State): State

    @Query("update states set name = :name, description = :description, type = :type, configuration = :configuration, job_name = :jobName where id = :id returning *")
    suspend fun update(state: State): State

    @Query("delete from states where id = :id")
    suspend fun deleteById(id: String)
}
