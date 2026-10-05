package bosca.localization.model

import kotlinx.serialization.Serializable

/**
 * Summary of a sync operation against an external translation management platform.
 *
 * Each counter reflects work the platform performed during this sync; [errors]
 * captures human-readable messages for items that could not be synchronized so
 * the caller can surface them in the admin UI or CLI output.
 */
@Serializable
data class SyncResult(
    val stringsAdded: Int = 0,
    val stringsUpdated: Int = 0,
    val translationsAdded: Int = 0,
    val translationsUpdated: Int = 0,
    val errors: List<String> = emptyList()
)
