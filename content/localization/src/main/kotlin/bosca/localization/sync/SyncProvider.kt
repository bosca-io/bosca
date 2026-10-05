@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.sync

import bosca.localization.model.LocalizationSyncState
import bosca.localization.model.SyncResult
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Provider-agnostic interface for pushing Bosca localization data to an external
 * translation management platform and pulling translations back.
 *
 * A provider is identified by the opaque string stored in [LocalizationSyncState.provider]
 * (e.g. `"crowdin"`). Implementations are responsible for translating between Bosca's
 * string and plural-translation model and the provider's API shape, including any
 * required plural category mapping.
 *
 * Pulled translations should be marked with [bosca.localization.model.TranslationOrigin.SYNC]
 * and enter the review workflow at [bosca.localization.model.TranslationState.DRAFT]
 * so they are treated consistently with other imported work.
 */
interface SyncProvider {

    /** The provider identifier this implementation handles (e.g. `"crowdin"`). */
    val provider: String

    /**
     * Pushes source strings and existing translations for [projectId] to the provider.
     * The concrete provider decides which states are eligible for push and how to resolve
     * conflicts against existing remote entries.
     */
    suspend fun syncToProvider(projectId: UUID, state: LocalizationSyncState): SyncResult

    /**
     * Pulls translations for [projectId] from the provider, upserting them as
     * origin = SYNC, state = draft so they enter the same review flow as imports.
     */
    suspend fun syncFromProvider(projectId: UUID, state: LocalizationSyncState): SyncResult
}
