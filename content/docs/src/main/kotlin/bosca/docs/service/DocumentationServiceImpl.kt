package bosca.docs.service

import bosca.db.transaction
import bosca.docs.model.ApiDocumentation
import bosca.docs.repository.DocumentationRepository
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class DocumentationServiceImpl(
    private val repository: DocumentationRepository,
) : DocumentationService {

    override suspend fun getByQualifiedName(qualifiedName: String): ApiDocumentation? =
        repository.getByQualifiedName(qualifiedName)

    override suspend fun getByQualifiedNames(qualifiedNames: List<String>): List<ApiDocumentation> =
        qualifiedNames.mapNotNull { repository.getByQualifiedName(it) }

    override suspend fun upsertAll(docs: List<ApiDocumentation>) = transaction {
        for (doc in docs) {
            repository.upsert(doc)
        }
    }

    override suspend fun deleteAll() = repository.deleteAll()
}
