package bosca.content.search

import bosca.storage.model.StorageSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Indicates whether this storage system is configured to receive content indexing
 * (metadata and collections). Domain-specific indexes such as profile search, API
 * documentation, and code search use `contentIndex = false` and route explicitly.
 * The flag is stored in [bosca.search.model.IndexConfiguration].
 *
 * Defaults to `true` when the field is absent, preserving backwards compatibility
 * with indexes created before the flag was introduced.
 */
val StorageSystem.isContentIndex: Boolean
    get() {
        val config = configuration.takeIf { it is JsonObject } ?: return true
        val value = config.jsonObject["contentIndex"] ?: return true
        return value.jsonPrimitive.boolean
    }
