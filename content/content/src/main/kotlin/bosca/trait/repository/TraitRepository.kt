package bosca.trait.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.trait.model.Trait

@Repository
interface TraitRepository {

    @Query("select * from traits")
    suspend fun getAll(): List<Trait>

    @Query("select * from traits where id = any(:ids)")
    suspend fun getAll(ids: List<String>): List<Trait>

    @Query("select * from traits where id = :id")
    suspend fun getById(id: String): Trait?

    @Query("delete from traits where id = :id")
    suspend fun deleteById(id: String)

    @Query("insert into traits (id, name, description, delete_workflow_id) values (:id, :name, :description, :deleteWorkflowId) returning *")
    suspend fun add(trait: Trait): Trait

    @Query("update traits set name = :name, description = :description, delete_workflow_id = :deleteWorkflowId where id = :id returning *")
    suspend fun update(trait: Trait): Trait

    @Query("select t.* from traits as t inner join trait_content_types ct on t.id = ct.trait_id where ct.content_type = :contentType")
    suspend fun getByContentType(contentType: String): List<Trait>
}