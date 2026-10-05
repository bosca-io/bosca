package bosca.pipelines.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/** Persistence for named shapes (`pipelines.pipeline_shape`), keyed by name. */
@Repository
interface PipelineShapeRepository {
    @Query("select * from pipelines.pipeline_shape order by name")
    suspend fun findAll(): List<PipelineShapeRecord>

    @Query("select * from pipelines.pipeline_shape where name = :name")
    suspend fun findByName(name: String): PipelineShapeRecord?

    @Query(
        """
        insert into pipelines.pipeline_shape (name, fields)
        values (:name, :fields)
        on conflict (name) do update set fields = excluded.fields, modified_at = now()
        returning *
        """,
    )
    suspend fun upsert(shape: PipelineShapeRecord): PipelineShapeRecord

    @Query("delete from pipelines.pipeline_shape where name = :name")
    suspend fun delete(name: String)
}
