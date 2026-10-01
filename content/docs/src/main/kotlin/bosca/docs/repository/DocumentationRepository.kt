package bosca.docs.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.docs.model.ApiDocumentation

@Repository
interface DocumentationRepository {

    @Query("select * from api_documentation where qualified_name = :qualifiedName")
    suspend fun getByQualifiedName(qualifiedName: String): ApiDocumentation?

    @Query(
        "insert into api_documentation (qualified_name, content, indexed_at) " +
            "values (:qualifiedName, :content, now()) " +
            "on conflict (qualified_name) do update set content = excluded.content, indexed_at = now() " +
            "returning *"
    )
    suspend fun upsert(doc: ApiDocumentation): ApiDocumentation

    @Query("delete from api_documentation")
    suspend fun deleteAll()
}
