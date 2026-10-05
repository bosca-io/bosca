@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Request payload for configuring or updating an external sync provider binding.
 *
 * [syncConfig] is opaque to the server and passed through to the provider
 * implementation; its shape is provider-specific (e.g. Crowdin expects fields
 * such as `apiToken`, `organizationDomain`, and optional file mappings).
 */
@Serializable
data class LocalizationSyncConfigInput(
    @Contextual
    val projectId: UUID,
    val provider: String = "crowdin",
    val externalId: String? = null,
    @Contextual
    val syncConfig: JsonElement? = null
)
