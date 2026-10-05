package bosca.ai.chat.repository

import bosca.db.migrations.Migration

class AiMigration : Migration {

    override val schema: String = "ai"

    override val resources: List<String> = listOf(
        "V1__chat_sessions.sql",
        "V2__chat_messages_id_uuid.sql",
        "V3__mcp_server_registrations.sql",
        "V4__agent_tool_mcp_servers_fk.sql",
        "V5__chat_session_status.sql",
        "V6__chat_session_processing.sql",
        "V7__mcp_server_registrations_git_sync.sql",
        "V8__agent_tool_impl_variants.sql",
        "V9__agent_resources.sql",
        "V10__chat_session_parent.sql"
    )
}
