package bosca.bible.providers.configuration

import bosca.bible.providers.model.BibleProviderSettings
import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.serialization.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads saved provider settings, locking the configuration when [forUpdate] is true.
 * Decoding failures omit the stored value because it contains credentials.
 */
internal suspend fun ConfigurationService.getBibleProviderSettings(
    id: UUID,
    json: Json,
    forUpdate: Boolean = false,
): BibleProviderSettings = try {
    requireNotNull(getValueAs<BibleProviderSettings>(id, json, forUpdate)) {
        "Bible provider configuration has no settings"
    }
} catch (_: SerializationException) {
    throw SerializationException("Invalid Bible provider settings for configuration $id")
}
