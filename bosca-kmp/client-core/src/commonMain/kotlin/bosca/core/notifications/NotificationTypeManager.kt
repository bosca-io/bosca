package bosca.core.notifications

import kotlinx.coroutines.flow.StateFlow

/** Server-defined notification types and their native presentation channels. */
interface NotificationTypeManager {
    /** Most recently synchronized visible server types. */
    val types: StateFlow<List<NotificationTypeDefinition>>

    /** Refreshes the server catalog and reconciles native notification channels. */
    suspend fun synchronize()
}
