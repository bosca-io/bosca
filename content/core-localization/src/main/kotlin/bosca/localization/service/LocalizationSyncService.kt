@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.service

import bosca.localization.model.LocalizationSyncConfigInput
import bosca.localization.model.LocalizationSyncState
import bosca.localization.model.SyncResult
import bosca.serialization.UUID
import bosca.service.Service
import kotlin.uuid.ExperimentalUuidApi

/**
 * Manages bidirectional synchronization between Bosca localization projects and an
 * external translation management platform (Crowdin first; the interface is provider-
 * agnostic so other providers can be added later).
 *
 * Sync operations are idempotent with respect to state: pulling the same set of
 * translations twice produces identical outcomes in the Bosca database.
 */
interface LocalizationSyncService : Service {

    /**
     * Current sync configuration for a project, or `null` if no provider has been
     * configured for it yet.
     */
    suspend fun getSyncState(projectId: UUID): LocalizationSyncState?

    /**
     * Creates or updates the sync binding between a project and an external provider.
     * The returned [LocalizationSyncState] reflects the persisted configuration.
     */
    suspend fun configureSyncProvider(input: LocalizationSyncConfigInput): LocalizationSyncState

    /**
     * Pushes Bosca source strings and translations to the configured provider.
     * The specific semantics (which states are pushed, how conflicts resolve) are
     * delegated to the provider implementation.
     */
    suspend fun syncToProvider(projectId: UUID): SyncResult

    /**
     * Pulls translations from the configured provider into Bosca, marking them with
     * [bosca.localization.model.TranslationOrigin.SYNC] and
     * [bosca.localization.model.TranslationState.DRAFT] so they enter the same review
     * workflow as any other imported work.
     */
    suspend fun syncFromProvider(projectId: UUID): SyncResult

    /** Removes the sync binding for a project. Translations already pulled remain. */
    suspend fun removeSyncProvider(projectId: UUID)
}
