package bosca.chat.repository

import bosca.db.migrations.Migration

/**
 * Flyway migration registry for the chat schema, managing channel,
 * membership, and permission table evolution.
 */
class ChatMigration : Migration {

    override val schema: String = "chat"

    override val resources: List<String> = listOf(
        "V1__chat_schema.sql",
        "V2__object_scoped_channels.sql",
        "V3__drop_last_read_columns.sql",
        "V4__channel_invitations.sql",
        "V5__postgres_chat_storage.sql",
    )
}
