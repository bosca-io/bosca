package bosca.content.tools.repository

import bosca.content.tools.model.TemplateAttributeTool
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface TemplateAttributeToolRepository {
    @Query("select * from template_attribute_tools order by name")
    suspend fun getAll(): List<TemplateAttributeTool>

    @Query("select * from template_attribute_tools where id = :id")
    suspend fun get(id: UUID): TemplateAttributeTool?

    @Query("insert into template_attribute_tools (key, name, description, query, result_path, configuration) values (:key, :name, :description, :query, :resultPath, (:configuration)::jsonb) returning *")
    suspend fun add(tool: TemplateAttributeTool): TemplateAttributeTool

    @Query("update template_attribute_tools set key = :key, name = :name, description = :description, query = :query, result_path = :resultPath, configuration = (:configuration)::jsonb where id = :id returning *")
    suspend fun edit(tool: TemplateAttributeTool): TemplateAttributeTool

    @Query("delete from template_attribute_tools where id = :id")
    suspend fun delete(id: UUID)
}
