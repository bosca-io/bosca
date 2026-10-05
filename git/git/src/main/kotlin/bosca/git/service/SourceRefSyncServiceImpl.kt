package bosca.git.service

import bosca.git.repository.QuerySourceRefRepository
import bosca.git.repository.ScriptSourceRefRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.eclipse.jgit.lib.ObjectId
import org.slf4j.LoggerFactory

/**
 * Reads new file content from the DFS repository for scripts and queries
 * whose source refs were affected by a push, producing update commands
 * for downstream consumers. Computes the diff between the push's before
 * and after commits via [RepositoryBrowseService.listChangedPaths] so
 * callers don't have to know JGit; for branch creations (zero before sha)
 * every linked file at the new commit is treated as changed.
 */
@ServiceImplementation
class SourceRefSyncServiceImpl(
    private val scriptSourceRefRepository: ScriptSourceRefRepository,
    private val querySourceRefRepository: QuerySourceRefRepository,
    private val browseService: RepositoryBrowseService
) : SourceRefSyncService {

    private val log = LoggerFactory.getLogger(SourceRefSyncServiceImpl::class.java)

    override suspend fun findAffectedScripts(
        repositoryId: UUID,
        pushedRef: String,
        beforeSha: String,
        afterSha: String
    ): List<ScriptSourceUpdate> {
        if (afterSha == ZERO_SHA) return emptyList()
        val refs = scriptSourceRefRepository.findByRepository(repositoryId)
        val branchName = pushedRef.removePrefix("refs/heads/")
        val candidates = refs.filter { it.ref == branchName || it.ref == pushedRef }
        if (candidates.isEmpty()) return emptyList()

        val changedFiles = computeChangedFiles(repositoryId, beforeSha, afterSha)
        val updates = mutableListOf<ScriptSourceUpdate>()
        for (ref in candidates) {
            if (changedFiles != null && ref.path !in changedFiles) continue
            val blob = browseService.readBlob(repositoryId, afterSha, ref.path)
            val content = blob?.content
            if (content != null) {
                scriptSourceRefRepository.updateResolvedCommit(ref.scriptId, afterSha)
                updates.add(ScriptSourceUpdate(ref.scriptId, content, afterSha))
                log.info("Script {} synced from {}:{}", ref.scriptId, repositoryId, ref.path)
            } else {
                log.warn("Script source ref {}:{} not found or binary at commit {}", repositoryId, ref.path, afterSha)
            }
        }
        return updates
    }

    override suspend fun findAffectedQueries(
        repositoryId: UUID,
        pushedRef: String,
        beforeSha: String,
        afterSha: String
    ): List<QuerySourceUpdate> {
        if (afterSha == ZERO_SHA) return emptyList()
        val refs = querySourceRefRepository.findByRepository(repositoryId)
        val branchName = pushedRef.removePrefix("refs/heads/")
        val candidates = refs.filter { it.ref == branchName || it.ref == pushedRef }
        if (candidates.isEmpty()) return emptyList()

        val changedFiles = computeChangedFiles(repositoryId, beforeSha, afterSha)
        val updates = mutableListOf<QuerySourceUpdate>()
        for (ref in candidates) {
            if (changedFiles != null && ref.path !in changedFiles) continue
            val blob = browseService.readBlob(repositoryId, afterSha, ref.path)
            val content = blob?.content
            if (content != null) {
                querySourceRefRepository.updateResolvedCommit(ref.queryId, afterSha)
                updates.add(QuerySourceUpdate(ref.queryId, content, afterSha))
                log.info("Query {} synced from {}:{}", ref.queryId, repositoryId, ref.path)
            } else {
                log.warn("Query source ref {}:{} not found or binary at commit {}", repositoryId, ref.path, afterSha)
            }
        }
        return updates
    }

    override suspend fun findAllQueriesAtHead(repositoryId: UUID): List<QuerySourceUpdate> {
        val refs = querySourceRefRepository.findByRepository(repositoryId)
        if (refs.isEmpty()) return emptyList()

        val updates = mutableListOf<QuerySourceUpdate>()
        val resolved = mutableMapOf<String, String?>()
        for (ref in refs) {
            val headSha = resolved.getOrPut(ref.ref) { browseService.resolveRef(repositoryId, ref.ref) }
            if (headSha == null) {
                log.warn("Query source ref {}:{} points to unresolvable ref {}", repositoryId, ref.path, ref.ref)
                continue
            }
            val update = readQueryUpdate(repositoryId, ref.path, headSha)?.copy(queryId = ref.queryId)
            if (update != null) {
                querySourceRefRepository.updateResolvedCommit(ref.queryId, headSha)
                updates.add(update)
                log.info("Query {} resolved from {}:{} at {}", ref.queryId, repositoryId, ref.path, headSha)
            }
        }
        return updates
    }

    override suspend fun findQueryAtHead(queryId: UUID): QuerySourceUpdate? {
        val ref = querySourceRefRepository.findByQueryId(queryId) ?: return null
        val headSha = browseService.resolveRef(ref.repositoryId, ref.ref)
        if (headSha == null) {
            log.warn("Query source ref {}:{} points to unresolvable ref {}", ref.repositoryId, ref.path, ref.ref)
            return null
        }
        val update = readQueryUpdate(ref.repositoryId, ref.path, headSha) ?: return null
        querySourceRefRepository.updateResolvedCommit(queryId, headSha)
        return update.copy(queryId = queryId)
    }

    override suspend fun findAllScriptsAtHead(repositoryId: UUID): List<ScriptSourceUpdate> {
        val refs = scriptSourceRefRepository.findByRepository(repositoryId)
        if (refs.isEmpty()) return emptyList()

        val updates = mutableListOf<ScriptSourceUpdate>()
        val resolved = mutableMapOf<String, String?>()
        for (ref in refs) {
            val headSha = resolved.getOrPut(ref.ref) { browseService.resolveRef(repositoryId, ref.ref) }
            if (headSha == null) {
                log.warn("Script source ref {}:{} points to unresolvable ref {}", repositoryId, ref.path, ref.ref)
                continue
            }
            val update = readScriptUpdate(repositoryId, ref.path, headSha)?.copy(scriptId = ref.scriptId)
            if (update != null) {
                scriptSourceRefRepository.updateResolvedCommit(ref.scriptId, headSha)
                updates.add(update)
                log.info("Script {} resolved from {}:{} at {}", ref.scriptId, repositoryId, ref.path, headSha)
            }
        }
        return updates
    }

    override suspend fun findScriptAtHead(scriptId: UUID): ScriptSourceUpdate? {
        val ref = scriptSourceRefRepository.findByScriptId(scriptId) ?: return null
        val headSha = browseService.resolveRef(ref.repositoryId, ref.ref)
        if (headSha == null) {
            log.warn("Script source ref {}:{} points to unresolvable ref {}", ref.repositoryId, ref.path, ref.ref)
            return null
        }
        val update = readScriptUpdate(ref.repositoryId, ref.path, headSha) ?: return null
        scriptSourceRefRepository.updateResolvedCommit(scriptId, headSha)
        return update.copy(scriptId = scriptId)
    }

    private suspend fun readQueryUpdate(repositoryId: UUID, path: String, sha: String): QuerySourceUpdate? {
        val blob = browseService.readBlob(repositoryId, sha, path)
        val content = blob?.content
        if (content == null) {
            log.warn("Query source ref {}:{} not found or binary at commit {}", repositoryId, path, sha)
            return null
        }
        return QuerySourceUpdate(queryId = UUID.NIL, newQuery = content, commitSha = sha)
    }

    private suspend fun readScriptUpdate(repositoryId: UUID, path: String, sha: String): ScriptSourceUpdate? {
        val blob = browseService.readBlob(repositoryId, sha, path)
        val content = blob?.content
        if (content == null) {
            log.warn("Script source ref {}:{} not found or binary at commit {}", repositoryId, path, sha)
            return null
        }
        return ScriptSourceUpdate(scriptId = UUID.NIL, newSource = content, commitSha = sha)
    }

    private suspend fun computeChangedFiles(
        repositoryId: UUID,
        beforeSha: String,
        afterSha: String
    ): Set<String>? {
        if (beforeSha == ZERO_SHA) return null
        return browseService.listChangedPaths(repositoryId, beforeSha, afterSha)
    }

    companion object {
        private val ZERO_SHA = ObjectId.zeroId().name()
    }
}
