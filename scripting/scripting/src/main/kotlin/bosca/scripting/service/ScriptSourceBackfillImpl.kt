package bosca.scripting.service

import bosca.git.service.ScriptSourceBackfill
import bosca.git.service.ScriptSourceUpdate
import bosca.git.service.SourceRefSyncService
import bosca.scripting.repository.ScriptRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

@ServiceImplementation
class ScriptSourceBackfillImpl(
    private val sourceRefSyncService: SourceRefSyncService,
    private val scriptRepository: ScriptRepository,
) : ScriptSourceBackfill {

    private val log = LoggerFactory.getLogger(ScriptSourceBackfillImpl::class.java)

    override suspend fun backfillRepository(repositoryId: UUID): Int {
        val updates = sourceRefSyncService.findAllScriptsAtHead(repositoryId)
        var changed = 0
        for (update in updates) {
            if (applyUpdate(update)) changed++
        }
        return changed
    }

    override suspend fun backfillScript(scriptId: UUID): Boolean {
        val update = sourceRefSyncService.findScriptAtHead(scriptId) ?: return false
        return applyUpdate(update)
    }

    private suspend fun applyUpdate(update: ScriptSourceUpdate): Boolean {
        val existing = scriptRepository.getById(update.scriptId)
        if (existing == null) {
            log.warn("Script {} referenced by source ref no longer exists; skipping", update.scriptId)
            return false
        }
        if (existing.source == update.newSource) return false
        val saved = scriptRepository.update(existing.copy(source = update.newSource))
        if (saved == null) {
            log.warn("Script {} update failed (optimistic lock); skipping", update.scriptId)
            return false
        }
        log.info("Script {} source updated from git at {}", update.scriptId, update.commitSha)
        return true
    }
}
