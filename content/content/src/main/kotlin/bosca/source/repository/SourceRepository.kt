package bosca.source.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.source.model.Source

@Repository
interface SourceRepository {

    @Query("select * from sources order by name")
    suspend fun getAll(): List<Source>

    @Query("select * from sources where id = :id")
    suspend fun getById(id: UUID): Source?

    @Query("select * from sources where name = :name")
    suspend fun getByName(name: String): Source?

    @Query("insert into sources (name, description, configuration) values (:name, :description, :configuration) returning *")
    suspend fun add(source: Source): Source

    @Query("update sources set name = :name, description = :description, configuration = :configuration where id = :id returning *")
    suspend fun update(source: Source): Source

    @Query("delete from sources where id = :id")
    suspend fun deleteById(id: UUID)
}
