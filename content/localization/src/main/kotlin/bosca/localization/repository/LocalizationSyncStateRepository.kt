@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.localization.model.LocalizationSyncState
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data access for sync provider bindings in `localization.sync_state`.
 *
 * One row per `(project_id, provider)` pair; currently only `'crowdin'` is
 * implemented, but the schema and API are provider-agnostic so additional
 * providers (Lokalise, Transifex) can be added without schema changes.
 */
@Repository
interface LocalizationSyncStateRepository {

    @Query("select * from localization.sync_state where project_id = :projectId")
    suspend fun getByProjectId(projectId: UUID): LocalizationSyncState?

    @Query("insert into localization.sync_state (project_id, provider, external_id, sync_config) values (:projectId, :provider, :externalId, :syncConfig) on conflict (project_id, provider) do update set external_id = excluded.external_id, sync_config = excluded.sync_config returning *")
    suspend fun upsert(
        projectId: UUID,
        provider: String,
        externalId: String?,
        syncConfig: JsonElement?
    ): LocalizationSyncState

    @Query("update localization.sync_state set last_synced = now() where project_id = :projectId")
    suspend fun markSynced(projectId: UUID)

    @Query("delete from localization.sync_state where project_id = :projectId")
    suspend fun deleteByProjectId(projectId: UUID)
}
