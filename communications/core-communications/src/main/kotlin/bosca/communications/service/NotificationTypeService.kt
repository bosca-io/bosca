package bosca.communications.service

import bosca.communications.model.NotificationType
import bosca.service.Service

/**
 * Manages the admin-definable notification type catalog. Types are
 * the units users opt in or out of per delivery channel; the [key]
 * is the immutable contract sending code references.
 */
interface NotificationTypeService : Service {

    /**
     * All notification types, ordered for presentation
     * (displayOrder, then key).
     */
    suspend fun list(): List<NotificationType>

    /**
     * Get a type by key. Returns null for an unknown key.
     */
    suspend fun get(key: String): NotificationType?

    /**
     * Create or update a type. Keys are lowercase slugs and
     * immutable once created. For [NotificationType.system] rows the
     * [optional] flag cannot be changed — throws
     * [IllegalArgumentException] on an attempt, on a malformed key,
     * on a blank name, or when a non-optional type is configured off
     * by default for either delivery channel.
     */
    suspend fun set(
        key: String,
        name: String,
        description: String?,
        optional: Boolean,
        defaultEmailEnabled: Boolean,
        defaultPushEnabled: Boolean,
        displayOrder: Int,
        hidden: Boolean,
    ): NotificationType

    /**
     * Delete a non-system type. Stored preference rows for the
     * deleted type become inert (the preference matrix is built
     * from the catalog). Throws [IllegalArgumentException] when
     * attempting to delete a system type; returns false for an
     * unknown key.
     */
    suspend fun delete(key: String): Boolean
}
