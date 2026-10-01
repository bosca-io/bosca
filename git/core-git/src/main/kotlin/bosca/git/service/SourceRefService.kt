package bosca.git.service

import bosca.git.model.QuerySourceRef
import bosca.git.model.ScriptSourceRef
import bosca.git.model.SourceRefInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages the linkage between domain entities (scripts, queries) and files in
 * Bosca-hosted git repositories. Each entity can be backed by at most one file;
 * upserting replaces any prior linkage.
 */
interface SourceRefService : Service {

    suspend fun setScriptSourceRef(scriptId: UUID, input: SourceRefInput): ScriptSourceRef

    suspend fun removeScriptSourceRef(scriptId: UUID)

    suspend fun findScriptSourceRef(scriptId: UUID): ScriptSourceRef?

    suspend fun setQuerySourceRef(queryId: UUID, input: SourceRefInput): QuerySourceRef

    suspend fun removeQuerySourceRef(queryId: UUID)

    suspend fun findQuerySourceRef(queryId: UUID): QuerySourceRef?

    suspend fun findSourceRefsByRepository(repositoryId: UUID): List<ScriptSourceRef>

    suspend fun findQuerySourceRefsByRepository(repositoryId: UUID): List<QuerySourceRef>
}
