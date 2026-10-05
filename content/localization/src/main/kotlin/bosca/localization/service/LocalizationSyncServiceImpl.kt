@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.service

import bosca.localization.model.LocalizationSyncConfigInput
import bosca.localization.model.LocalizationSyncState
import bosca.localization.model.SyncResult
import bosca.localization.repository.LocalizationSyncStateRepository
import bosca.localization.sync.SyncProviders
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(LocalizationSyncServiceImpl::class.java)

/**
 * Default implementation of [LocalizationSyncService]. Delegates provider-specific
 * behaviour to a [SyncProvider] looked up by the provider string on the per-project
 * [LocalizationSyncState]; persistence of the binding lives in
 * [LocalizationSyncStateRepository].
 *
 * `syncToProvider` / `syncFromProvider` return the provider's [SyncResult] unchanged
 * and bump `last_synced` on the sync state row so operators can see when the binding
 * was last exercised regardless of whether the provider side reported errors.
 */
@ServiceImplementation
class LocalizationSyncServiceImpl(
    private val repository: LocalizationSyncStateRepository,
    private val syncProviders: SyncProviders
) : LocalizationSyncService {

    private val providersByName = syncProviders.providers.associateBy { it.provider }

    override suspend fun getSyncState(projectId: UUID): LocalizationSyncState? =
        repository.getByProjectId(projectId)

    override suspend fun configureSyncProvider(input: LocalizationSyncConfigInput): LocalizationSyncState {
        require(providersByName.containsKey(input.provider)) { "Unknown sync provider: ${input.provider}" }
        return repository.upsert(
            projectId = input.projectId,
            provider = input.provider,
            externalId = input.externalId,
            syncConfig = input.syncConfig
        )
    }

    override suspend fun syncToProvider(projectId: UUID): SyncResult {
        val state = repository.getByProjectId(projectId)
            ?: return SyncResult(errors = listOf("No sync provider configured for project $projectId"))
        val provider = providersByName[state.provider]
            ?: return SyncResult(errors = listOf("Sync provider '${state.provider}' is not registered"))
        val result = try {
            provider.syncToProvider(projectId, state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("syncToProvider failed for project {}", projectId, e)
            return SyncResult(errors = listOf("Sync failed: ${e.message}"))
        }
        if (result.errors.isEmpty()) {
            repository.markSynced(projectId)
        }
        return result
    }

    override suspend fun syncFromProvider(projectId: UUID): SyncResult {
        val state = repository.getByProjectId(projectId)
            ?: return SyncResult(errors = listOf("No sync provider configured for project $projectId"))
        val provider = providersByName[state.provider]
            ?: return SyncResult(errors = listOf("Sync provider '${state.provider}' is not registered"))
        val result = try {
            provider.syncFromProvider(projectId, state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("syncFromProvider failed for project {}", projectId, e)
            return SyncResult(errors = listOf("Sync failed: ${e.message}"))
        }
        if (result.errors.isEmpty()) {
            repository.markSynced(projectId)
        }
        return result
    }

    override suspend fun removeSyncProvider(projectId: UUID) = repository.deleteByProjectId(projectId)
}
