package bosca.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupTablesTest {

    @Test
    fun `BackupTables ordered list is not empty`() {
        assertTrue(BackupTables.ordered.isNotEmpty())
    }

    @Test
    fun `BackupTables first entries are independent reference tables`() {
        val firstTable = BackupTables.ordered.first()
        assertEquals("traits", firstTable.name)
        assertEquals(listOf("id"), firstTable.primaryKeys)
        assertEquals("public", firstTable.schema)
    }

    @Test
    fun `BackupTables contains scheduler tables with scheduler schema`() {
        val schedulerTables = BackupTables.ordered.filter { it.schema == "scheduler" }
        assertTrue(schedulerTables.isNotEmpty())
        val scheduledJobs = schedulerTables.find { it.name == "scheduled_jobs" }
        assertEquals("scheduler", scheduledJobs?.schema)
        assertEquals(listOf("id"), scheduledJobs?.primaryKeys)
    }

    @Test
    fun `BackupTables contains AI schema tables`() {
        val aiTables = BackupTables.ordered.filter { it.schema == "ai" }
        assertTrue(aiTables.isNotEmpty())
        assertTrue(aiTables.any { it.name == "chat_sessions" })
        assertTrue(aiTables.any { it.name == "chat_messages" })
    }

    @Test
    fun `BackupTables contains scripting schema tables`() {
        val scriptingTables = BackupTables.ordered.filter { it.schema == "scripting" }
        assertTrue(scriptingTables.isNotEmpty())
        assertTrue(scriptingTables.any { it.name == "scripts" })
        assertTrue(scriptingTables.any { it.name == "trigger_bindings" })
    }

    @Test
    fun `BackupTables metadata appears before metadata_traits`() {
        val metadataIndex = BackupTables.ordered.indexOfFirst { it.name == "metadata" }
        val traitsIndex = BackupTables.ordered.indexOfFirst { it.name == "metadata_traits" }
        assertTrue(metadataIndex < traitsIndex, "metadata should appear before metadata_traits")
    }

    @Test
    fun `BackupTables collections appears before collection_items`() {
        val collectionsIndex = BackupTables.ordered.indexOfFirst { it.name == "collections" }
        val itemsIndex = BackupTables.ordered.indexOfFirst { it.name == "collection_items" }
        assertTrue(collectionsIndex < itemsIndex, "collections should appear before collection_items")
    }

    @Test
    fun `All BackupTableDefinitions have non-empty primary keys`() {
        for (table in BackupTables.ordered) {
            assertTrue(table.primaryKeys.isNotEmpty(), "Table ${table.name} should have primary keys")
        }
    }

    @Test
    fun `All BackupTableDefinitions have non-empty names`() {
        for (table in BackupTables.ordered) {
            assertTrue(table.name.isNotEmpty(), "Table name should not be empty")
        }
    }
}
