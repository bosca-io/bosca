@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.git

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Lightweight projection of an entity's `(key, id)` columns. Used by repository
 * `getKeyIndex()` queries to avoid loading full row contents (description text, JSONB
 * configuration, prompt body) when the sync flow only needs to resolve string keys to UUIDs.
 */
@Serializable
data class KeyIdEntry(
    val key: String,
    @Contextual
    @ColumnName("id")
    val id: Uuid
)
