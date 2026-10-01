package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.communications.model.NotificationType

@Repository
interface NotificationTypeRepository {

    @Query("select * from communications.notification_types order by display_order, key")
    suspend fun list(): List<NotificationType>

    @Query("select * from communications.notification_types where key = :key")
    suspend fun get(key: String): NotificationType?

    @Query("""
        insert into communications.notification_types (
            key, name, description, optional, default_email_enabled, default_push_enabled, display_order, hidden
        )
        values (
            :key, :name, :description, :optional, :defaultEmailEnabled, :defaultPushEnabled, :displayOrder, :hidden
        )
        on conflict (key)
        do update set
            name = :name,
            description = :description,
            optional = :optional,
            default_email_enabled = :defaultEmailEnabled,
            default_push_enabled = :defaultPushEnabled,
            display_order = :displayOrder,
            hidden = :hidden,
            updated_at = now()
        returning *
    """)
    suspend fun upsert(
        key: String,
        name: String,
        description: String?,
        optional: Boolean,
        defaultEmailEnabled: Boolean,
        defaultPushEnabled: Boolean,
        displayOrder: Int,
        hidden: Boolean,
    ): NotificationType

    @Query("delete from communications.notification_types where key = :key and system = false", returnUpdateCount = true)
    suspend fun delete(key: String): Int
}
