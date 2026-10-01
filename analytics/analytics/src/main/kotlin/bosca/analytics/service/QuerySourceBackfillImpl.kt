package bosca.analytics.service

import bosca.analytics.repository.QueryDefinitionRepository
import bosca.git.service.QuerySourceBackfill
import bosca.git.service.QuerySourceUpdate
import bosca.git.service.SourceRefSyncService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

@ServiceImplementation
class QuerySourceBackfillImpl(
    private val sourceRefSyncService: SourceRefSyncService,
    private val queryRepository: QueryDefinitionRepository,
    private val queryService: AnalyticsQueryService,
) : QuerySourceBackfill {

    private val log = LoggerFactory.getLogger(QuerySourceBackfillImpl::class.java)

    override suspend fun backfillRepository(repositoryId: UUID): Int {
        val updates = sourceRefSyncService.findAllQueriesAtHead(repositoryId)
        var changed = 0
        for (update in updates) {
            if (applyUpdate(update)) changed++
        }
        return changed
    }

    override suspend fun backfillQuery(queryId: UUID): Boolean {
        val update = sourceRefSyncService.findQueryAtHead(queryId) ?: return false
        return applyUpdate(update)
    }

    private suspend fun applyUpdate(update: QuerySourceUpdate): Boolean {
        val existing = queryRepository.getById(update.queryId)
        if (existing == null) {
            log.warn("Analytics query {} referenced by source ref no longer exists; skipping", update.queryId)
            return false
        }
        if (existing.query == update.newQuery) return false
        if (queryService.applyGitSync(update.queryId, update.newQuery, parameterDeclarations = null) == null) {
            log.warn("Analytics query {} was deleted while applying source backfill; skipping", update.queryId)
            return false
        }
        log.info("Analytics query {} SQL updated from git at {}", update.queryId, update.commitSha)
        return true
    }
}
