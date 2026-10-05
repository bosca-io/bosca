package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.ExclusionLayer
import bosca.serialization.UUID

@Repository
interface ExclusionLayerRepository {

    @Query("select * from experimentation.exclusion_layers order by name")
    suspend fun getAll(): List<ExclusionLayer>

    @Query("select * from experimentation.exclusion_layers where id = :id")
    suspend fun getById(id: UUID): ExclusionLayer?

    @Query("""
        insert into experimentation.exclusion_layers (name, description)
        values (:name, :description)
        returning *
    """)
    suspend fun add(layer: ExclusionLayer): ExclusionLayer

    @Query("""
        update experimentation.exclusion_layers
        set name = :name, description = :description
        where id = :id
        returning *
    """)
    suspend fun update(layer: ExclusionLayer): ExclusionLayer

    @Query("delete from experimentation.exclusion_layers where id = :id")
    suspend fun deleteById(id: UUID)
}
