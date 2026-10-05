package bosca.ai.chat.repository

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AiMigrationTest {

    private val migration = AiMigration()

    @Test
    fun `AiMigration schema is ai`() {
        assertEquals("ai", migration.schema)
    }

    @Test
    fun `AiMigration resources is not empty`() {
        assertTrue(migration.resources.isNotEmpty())
    }

    @Test
    fun `AiMigration resources contains V1 chat sessions migration`() {
        assertTrue(migration.resources.contains("V1__chat_sessions.sql"))
    }

    @Test
    fun `AiMigration resources contains V2 chat messages id uuid migration`() {
        assertTrue(migration.resources.contains("V2__chat_messages_id_uuid.sql"))
    }

    @Test
    fun `AiMigration resources contains V3 mcp server registrations migration`() {
        assertTrue(migration.resources.contains("V3__mcp_server_registrations.sql"))
    }

    @Test
    fun `AiMigration resources contains V4 agent tool mcp servers fk migration`() {
        assertTrue(migration.resources.contains("V4__agent_tool_mcp_servers_fk.sql"))
    }

    @Test
    fun `AiMigration resources contains V5 chat session status migration`() {
        assertTrue(migration.resources.contains("V5__chat_session_status.sql"))
    }

    @Test
    fun `AiMigration resources contains V6 chat session processing migration`() {
        assertTrue(migration.resources.contains("V6__chat_session_processing.sql"))
    }

    @Test
    fun `AiMigration resources contains V7 mcp server registrations git sync migration`() {
        assertTrue(migration.resources.contains("V7__mcp_server_registrations_git_sync.sql"))
    }

    @Test
    fun `AiMigration resources contains V8 agent tool impl variants migration`() {
        assertTrue(migration.resources.contains("V8__agent_tool_impl_variants.sql"))
    }

    @Test
    fun `AiMigration resources contains V9 agent resources migration`() {
        assertTrue(migration.resources.contains("V9__agent_resources.sql"))
    }

    @Test
    fun `AiMigration resources contains V10 chat session parent migration`() {
        assertTrue(migration.resources.contains("V10__chat_session_parent.sql"))
    }

    @Test
    fun `AiMigration resources has ten entries`() {
        assertEquals(10, migration.resources.size)
    }

    @Test
    fun `AiMigration resources are ordered by version`() {
        val versions = migration.resources.map { it.substringBefore("__") }
        assertEquals(listOf("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10"), versions)
    }
}
