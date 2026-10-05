@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Tracks the binding between a [LocalizationProject] and an external translation
 * management platform such as Crowdin.
 *
 * One row per `(projectId, provider)` pair. The [syncConfig] JSON holds any
 * provider-specific settings (API tokens, organization slugs, file mappings).
 */
@Serializable
data class LocalizationSyncState(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    val provider: String = "crowdin",
    @ColumnName("external_id")
    val externalId: String? = null,
    @ColumnName("last_synced")
    @Contextual
    val lastSynced: OffsetDateTime? = null,
    @ColumnName("sync_config")
    @Contextual
    val syncConfig: JsonElement? = null,
    @Contextual
    val attributes: JsonElement? = null
)
