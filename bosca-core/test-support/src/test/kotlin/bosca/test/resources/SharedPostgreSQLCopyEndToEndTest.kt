package bosca.test.resources

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SharedPostgreSQLCopyEndToEndTest {
    @Test
    fun `copies preserve schema seed data and sequences while isolating mutations`() {
        val template = SharedPostgreSQLContainer().withDatabaseName("copy template").apply { start() }
        try {
            template.connection().use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE EXTENSION vector")
                    it.execute("CREATE TYPE item_state AS ENUM ('seeded', 'edited')")
                    it.execute(
                        "CREATE TABLE item (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                            "state item_state NOT NULL DEFAULT 'seeded', embedding vector(3))",
                    )
                    it.execute("INSERT INTO item (embedding) VALUES ('[1,2,3]')")
                    it.execute("CREATE TABLE child (item_id bigint REFERENCES item(id))")
                }
            }

            val first = template.copy()
            try {
                assertNotEquals(template.databaseName, first.databaseName)
                first.connection().use { connection ->
                    assertEquals("[1,2,3]", connection.scalar("SELECT embedding::text FROM item WHERE id = 1"))
                    assertEquals("2", connection.scalar("INSERT INTO item DEFAULT VALUES RETURNING id"))
                    connection.createStatement().use { it.execute("UPDATE item SET state = 'edited'") }
                    assertFailsWith<SQLException> {
                        connection.createStatement().use { it.execute("INSERT INTO child VALUES (999)") }
                    }
                }
            } finally {
                first.stop()
            }

            val second = template.copy()
            try {
                second.connection().use { connection ->
                    assertEquals("1", connection.scalar("SELECT count(*) FROM item"))
                    assertEquals("seeded", connection.scalar("SELECT state FROM item"))
                    assertEquals("2", connection.scalar("INSERT INTO item DEFAULT VALUES RETURNING id"))
                }
            } finally {
                second.stop()
            }
            template.connection().use { connection ->
                assertEquals("1", connection.scalar("SELECT count(*) FROM item"))
                assertEquals("seeded", connection.scalar("SELECT state FROM item"))
            }
        } finally {
            template.stop()
        }
    }

    @Test
    fun `one pristine template supports concurrent independent copies`() {
        val template = SharedPostgreSQLContainer().apply { start() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            template.connection().use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE item (value text)") }
            }
            val copies = (1..2).map { index ->
                executor.submit<String> {
                    val copy = template.copy()
                    try {
                        copy.connection().use { connection ->
                            connection.createStatement().use { it.execute("INSERT INTO item VALUES ('$index')") }
                            assertEquals(index.toString(), connection.scalar("SELECT value FROM item"))
                        }
                        copy.databaseName
                    } finally {
                        copy.stop()
                    }
                }
            }
            assertNotEquals(copies[0].get(30, TimeUnit.SECONDS), copies[1].get(30, TimeUnit.SECONDS))
            template.connection().use { assertEquals("0", it.scalar("SELECT count(*) FROM item")) }
        } finally {
            try {
                executor.shutdown()
                check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Database copies did not finish" }
            } finally {
                template.stop()
            }
        }
    }

    private fun SharedPostgreSQLContainer.connection(): Connection =
        DriverManager.getConnection(jdbcUrl, username, password)

    private fun Connection.scalar(sql: String): String = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            check(result.next()) { "Query returned no rows: $sql" }
            result.getString(1)
        }
    }
}
