package bosca.docs.service

import bosca.docs.model.ApiDocumentation
import bosca.service.Service

interface DocumentationService : Service {
    suspend fun getByQualifiedName(qualifiedName: String): ApiDocumentation?
    suspend fun getByQualifiedNames(qualifiedNames: List<String>): List<ApiDocumentation>
    suspend fun upsertAll(docs: List<ApiDocumentation>)
    suspend fun deleteAll()
}
