package bosca.analytics.service

import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.query.QueryParameterDeclaration
import bosca.analytics.query.QuerySourceCodec
import bosca.analytics.query.QuerySourceParseException
import bosca.analytics.repository.QueryDefinitionRepository
import bosca.git.service.CommitFileInput
import bosca.git.service.QuerySourceUpdate
import bosca.git.service.RepositoryWriteService
import bosca.git.service.SourceRefService
import bosca.git.service.SourceRefSyncService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

@ServiceImplementation
class AnalyticsQueryGitSyncServiceImpl(
    private val sourceRefSyncService: SourceRefSyncService,
    private val sourceRefService: SourceRefService,
    private val queryRepository: QueryDefinitionRepository,
    private val queryService: AnalyticsQueryService,
    private val repositoryWriteService: RepositoryWriteService,
) : AnalyticsQueryGitSyncService {

    private val log = LoggerFactory.getLogger(AnalyticsQueryGitSyncServiceImpl::class.java)

    override suspend fun onPushEvent(
        repositoryId: UUID,
        ref: String,
        beforeSha: String,
        afterSha: String,
    ) {
        val updates = sourceRefSyncService.findAffectedQueries(
            repositoryId = repositoryId,
            pushedRef = ref,
            beforeSha = beforeSha,
            afterSha = afterSha,
        )
        if (updates.isEmpty()) return

        val failures = mutableListOf<Pair<UUID, QuerySourceParseException>>()
        for (update in updates) {
            try {
                applyUpdate(repositoryId, ref, update)
            } catch (e: QuerySourceParseException) {
                // Per-query parse failure: skip this query, surface the error after all
                // valid updates have been applied so one bad file can't block the rest.
                log.error(
                    "Analytics query {} skipped: invalid @bosca-query block at {} {}:{}: {}",
                    update.queryId, repositoryId, ref, update.commitSha, e.message,
                )
                failures += update.queryId to e
            }
        }
        if (failures.isNotEmpty()) {
            val summary = failures.joinToString(", ") { (id, e) -> "$id (${e.message})" }
            throw QuerySourceParseException(
                "Analytics query sync had ${failures.size} parse failure(s): $summary"
            )
        }
    }

    private suspend fun applyUpdate(
        repositoryId: UUID,
        ref: String,
        update: QuerySourceUpdate,
    ) {
        val parsed = QuerySourceCodec.parse(update.newQuery)
        val updated = queryService.applyGitSync(
            queryId = update.queryId,
            newSql = parsed.cleanSql,
            parameterDeclarations = parsed.parameters,
        )
        if (updated == null) {
            log.warn("Analytics query {} referenced by source ref no longer exists; skipping", update.queryId)
            return
        }
        val parameters = parsed.parameters
        val parameterDescription = if (parameters == null) "untouched" else "n=${parameters.size}"
        log.info(
            "Analytics query {} synced from {} {}:{} (parameters: {})",
            update.queryId, repositoryId, ref, update.commitSha,
            parameterDescription,
        )
    }

    override suspend fun pushToGit(
        queryId: UUID,
        authorName: String,
        authorEmail: String,
    ): String? {
        val sourceRef = sourceRefService.findQuerySourceRef(queryId) ?: return null
        val query = queryRepository.getById(queryId) ?: return null
        val parameters = queryService.getParameters(queryId)
        val content = QuerySourceCodec.render(query.query, parameters.map { it.toDeclaration() })
        val result = repositoryWriteService.commitFile(
            CommitFileInput(
                repositoryId = sourceRef.repositoryId,
                branch = sourceRef.ref,
                path = sourceRef.path,
                content = content,
                message = "Update analytics query ${query.key}",
                authorName = authorName,
                authorEmail = authorEmail,
            )
        )
        log.info(
            "Analytics query {} pushed to {}:{} at {}",
            queryId, sourceRef.repositoryId, sourceRef.path, result.commitSha,
        )
        return result.commitSha
    }

    override suspend fun pushAllToGit(
        repositoryId: UUID,
        authorName: String,
        authorEmail: String,
    ): Int {
        val refs = sourceRefService.findQuerySourceRefsByRepository(repositoryId)
        var pushed = 0
        var failed = 0
        for (ref in refs) {
            // Per-query isolation so one bad commit (auth, network, conflict)
            // doesn't abandon the remaining queries in the repository. Errors
            // are logged with the query id; the caller sees the partial count
            // and can re-run the backfill to retry the failed queries.
            try {
                val sha = pushToGit(ref.queryId, authorName, authorEmail)
                if (sha != null) pushed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                log.error(
                    "Analytics query {} push failed during backfill of repository {}: {}",
                    ref.queryId, repositoryId, e.message, e,
                )
            }
        }
        log.info(
            "Analytics query backfill pushed {} of {} git-backed queries in repository {} ({} failed)",
            pushed, refs.size, repositoryId, failed,
        )
        return pushed
    }

    private fun AnalyticsQueryParameter.toDeclaration() = QueryParameterDeclaration(
        parameter = parameter,
        name = name,
        description = description,
        type = type,
        arrayType = arrayType,
        defaultValue = defaultValue,
        required = required,
        sort = sort,
    )
}
