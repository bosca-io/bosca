@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.repository

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Lightweight `(key, id)` projection used by `ScriptRepository.getKeyIndex()` so callers
 * that only need to resolve script keys to ids avoid loading the full row (which includes
 * potentially large source-text and JSONB schema columns).
 */
@Serializable
data class KeyIdEntry(
    val key: String,
    @Contextual
    @ColumnName("id")
    val id: Uuid
)
