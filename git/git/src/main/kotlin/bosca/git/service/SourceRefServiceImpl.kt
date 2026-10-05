package bosca.git.service

import bosca.git.model.QuerySourceRef
import bosca.git.model.ScriptSourceRef
import bosca.git.model.SourceRefInput
import bosca.git.repository.QuerySourceRefRepository
import bosca.git.repository.ScriptSourceRefRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Delegates source ref CRUD to the underlying repositories. Upsert semantics
 * ensure that re-linking an entity to a different file atomically replaces the
 * prior binding.
 */
@ServiceImplementation
class SourceRefServiceImpl(
    private val scriptSourceRefRepository: ScriptSourceRefRepository,
    private val querySourceRefRepository: QuerySourceRefRepository
) : SourceRefService {

    override suspend fun setScriptSourceRef(scriptId: UUID, input: SourceRefInput): ScriptSourceRef {
        return scriptSourceRefRepository.upsert(
            ScriptSourceRef(
                scriptId = scriptId,
                repositoryId = input.repositoryId,
                path = input.path,
                ref = input.ref
            )
        )
    }

    override suspend fun removeScriptSourceRef(scriptId: UUID) {
        scriptSourceRefRepository.delete(scriptId)
    }

    override suspend fun findScriptSourceRef(scriptId: UUID): ScriptSourceRef? {
        return scriptSourceRefRepository.findByScriptId(scriptId)
    }

    override suspend fun setQuerySourceRef(queryId: UUID, input: SourceRefInput): QuerySourceRef {
        return querySourceRefRepository.upsert(
            QuerySourceRef(
                queryId = queryId,
                repositoryId = input.repositoryId,
                path = input.path,
                ref = input.ref
            )
        )
    }

    override suspend fun removeQuerySourceRef(queryId: UUID) {
        querySourceRefRepository.delete(queryId)
    }

    override suspend fun findQuerySourceRef(queryId: UUID): QuerySourceRef? {
        return querySourceRefRepository.findByQueryId(queryId)
    }

    override suspend fun findSourceRefsByRepository(repositoryId: UUID): List<ScriptSourceRef> {
        return scriptSourceRefRepository.findByRepository(repositoryId)
    }

    override suspend fun findQuerySourceRefsByRepository(repositoryId: UUID): List<QuerySourceRef> {
        return querySourceRefRepository.findByRepository(repositoryId)
    }
}
