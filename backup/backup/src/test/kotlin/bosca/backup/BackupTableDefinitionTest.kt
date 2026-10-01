package bosca.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the behaviour of [BackupTableDefinition] and the global
 * [BackupTables.ordered] registry that drives backup and restore operations.
 */
class BackupTableDefinitionTest {

    /**
     * The default schema is `public`, so a plain table name should produce
     * a qualified identifier in the form `"public"."tablename"`.
     */
    @Test
    fun qualifiedNameUsesDefaultPublicSchema() {
        val definition = BackupTableDefinition("users", listOf("id"))
        assertEquals("\"public\".\"users\"", definition.qualifiedName)
    }

    /**
     * When an explicit schema is provided the qualified name must reflect
     * that schema rather than the default.
     */
    @Test
    fun qualifiedNameUsesCustomSchema() {
        val definition = BackupTableDefinition("chat_sessions", listOf("id"), "ai")
        assertEquals("\"ai\".\"chat_sessions\"", definition.qualifiedName)
    }

    /**
     * The ordered list must contain at least one entry; an empty list would
     * mean backups export nothing.
     */
    @Test
    fun orderedListIsNotEmpty() {
        assertTrue(BackupTables.ordered.isNotEmpty(), "BackupTables.ordered must not be empty")
    }

    /**
     * Duplicate table names (within the same schema) would cause data to be
     * exported or imported twice, so every qualified name must be unique.
     */
    @Test
    fun allEntriesHaveUniqueQualifiedNames() {
        val qualifiedNames = BackupTables.ordered.map { it.qualifiedName }
        val duplicates = qualifiedNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue(duplicates.isEmpty(), "Duplicate qualified names found: ${duplicates.keys}")
    }

    /**
     * Every table definition must have a non-blank name and at least one
     * primary key column so that conflict resolution during restore can
     * build valid ON CONFLICT clauses.
     */
    @Test
    fun allEntriesHaveNonEmptyNameAndAtLeastOnePrimaryKey() {
        BackupTables.ordered.forEach { definition ->
            assertTrue(
                definition.name.isNotBlank(),
                "Table name must not be blank"
            )
            assertTrue(
                definition.primaryKeys.isNotEmpty(),
                "Table '${definition.name}' must have at least one primary key"
            )
        }
    }
}
