package bosca.chat.repository

import bosca.db.migrations.Migration

/**
 * Flyway migration registry for the chat schema, managing channel,
 * membership, and permission table evolution.
 */
class ChatMigration : Migration {

    override val schema: String = "chat"

    // Reactions never had a SQL table — they moved straight to the
    // `chat-reactions` NATS KV bucket (see [bosca.chat.state.ChatReactionStore])
    // before chat ever shipped. V3 drops the last_read_* columns inherited
    // from core's V76 (community schema) now that read state lives in the
    // `chat-read-state` KV bucket too.
    override val resources: List<String> = listOf(
        "V1__chat_schema.sql",
        "V2__object_scoped_channels.sql",
        "V3__drop_last_read_columns.sql",
        "V4__channel_invitations.sql",
    )
}
