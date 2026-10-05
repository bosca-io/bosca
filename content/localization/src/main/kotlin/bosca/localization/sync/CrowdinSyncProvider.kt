@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.sync

import bosca.localization.model.LocalizationSyncState
import bosca.localization.model.SyncResult
import bosca.serialization.UUID
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi

private val logger = LoggerFactory.getLogger(CrowdinSyncProvider::class.java)

/**
 * Placeholder Crowdin sync provider.
 *
 * The full implementation (Ktor HTTP client wrapping Crowdin API v2, source string
 * push, translation pull with plural mapping, conflict handling) is tracked as
 * follow-up work. The stub exists now so the rest of the subsystem (sync state
 * storage, GraphQL surface, admin UI settings page) can be wired and reviewed
 * without blocking on an external API integration.
 *
 * Both sync directions return a `SyncResult` with a single entry in [SyncResult.errors]
 * so callers surface a clear message rather than silently reporting success or throwing
 * an opaque exception.
 */
class CrowdinSyncProvider : SyncProvider {

    override val provider: String = "crowdin"

    override suspend fun syncToProvider(projectId: UUID, state: LocalizationSyncState): SyncResult {
        logger.info("Crowdin syncToProvider invoked for project {} (stub)", projectId)
        return SyncResult(errors = listOf(NOT_IMPLEMENTED_MESSAGE))
    }

    override suspend fun syncFromProvider(projectId: UUID, state: LocalizationSyncState): SyncResult {
        logger.info("Crowdin syncFromProvider invoked for project {} (stub)", projectId)
        return SyncResult(errors = listOf(NOT_IMPLEMENTED_MESSAGE))
    }

    private companion object {
        const val NOT_IMPLEMENTED_MESSAGE =
            "Crowdin sync is not yet implemented. The sync binding and UI surface are in place; the HTTP integration is tracked as follow-up work."
    }
}
