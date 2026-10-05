package bosca.communications

import bosca.communications.configuration.CommunicationsMigration
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.AfterClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real pre-V15 to V15 rename against PostgreSQL, including persisted user data. */
class MessageTemplateNamesMigrationTest {

    private class BeforeMessageRenameMigration : Migration {
        override val schema: String = "communications"
        override val resources: List<String> = CommunicationsMigration().resources
            .takeWhile { it != "V15__message_template_names.sql" }
    }

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_communications_message_rename_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 3,
                ),
                key = "communications-message-rename-test",
            ),
        )

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    @Test
    fun `migration carries branding and registry metadata to message keys`() = runBlocking {
        FlywayMigration(pool).migrate(listOf(BeforeMessageRenameMigration()))
        val legacyRepositoryId = UUID.random()
        val brandingId = UUID.random()
        withDb {
            transaction {
                connection().useStatement(
                    """
                    create table public.configurations (
                        id uuid primary key,
                        key varchar not null unique,
                        description varchar not null
                    );
                    insert into public.configurations (id, key, description)
                    values ('$brandingId', 'bosca.emails.branding', 'Custom branding');

                    insert into communications.bml_email_projects
                        (key, description, repository_id, pinned_version)
                    values
                        ('bosca-emails', 'Legacy project', '$legacyRepositoryId', '2026.08.1'),
                        ('bosca-messages', 'Current project', null, null);
                    """.trimIndent(),
                ) { it.execute() }
            }
        }

        FlywayMigration(pool).migrate(listOf(CommunicationsMigration()))

        withDb {
            connection().useStatement(
                "select id, key, description from public.configurations",
            ) { statement ->
                val row = statement.executeQuery()
                assertTrue(row.next())
                assertEquals(brandingId.toString(), row.getString("id"))
                assertEquals("bosca.messages.branding", row.getString("key"))
                assertEquals("Custom branding", row.getString("description"))
                assertFalse(row.next())
            }
            connection().useStatement(
                "select key, description, repository_id, pinned_version from communications.bml_message_projects",
            ) { statement ->
                val row = statement.executeQuery()
                assertTrue(row.next())
                assertEquals("bosca-messages", row.getString("key"))
                assertEquals("Current project", row.getString("description"))
                assertEquals(legacyRepositoryId.toString(), row.getString("repository_id"))
                assertEquals("2026.08.1", row.getString("pinned_version"))
                assertFalse(row.next())
            }
        }
    }

    private suspend fun <T> withDb(block: suspend () -> T): T {
        val manager = pool.connection()
        return try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
