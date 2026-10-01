package bosca.git.configuration

import bosca.test.resources.SharedPostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the BX content-type removal against the PostgreSQL enum used by existing repositories. */
class RemoveBxRepositoryContentTypeMigrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("remove_bx_content_type_test")
            withReuse(true)
            start()
        }
    }

    @Test
    fun `reclassifies existing BX repositories and removes the enum value`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop schema if exists git cascade")
                statement.execute("create schema git")
                statement.execute(
                    """create type git.repository_content_type as enum (
                        'general', 'bx_project', 'script_project', 'documentation',
                        'analytic_query_project', 'agent_project', 'pipeline_project'
                    )""".trimIndent()
                )
                statement.execute(
                    """create table git.repositories (
                        id integer primary key,
                        content_type git.repository_content_type
                    )""".trimIndent()
                )
                statement.execute(
                    """insert into git.repositories (id, content_type) values
                        (1, 'bx_project'), (2, 'script_project')""".trimIndent()
                )

                val sql = javaClass.getResource("/db/migrations/V40__remove_bx_repository_content_type.sql")
                    ?.readText() ?: error("BX removal migration missing")
                statement.execute(sql)

                statement.executeQuery("select content_type::text from git.repositories order by id").use { rows ->
                    rows.next()
                    assertEquals("general", rows.getString(1))
                    rows.next()
                    assertEquals("script_project", rows.getString(1))
                }
                statement.executeQuery(
                    """select enumlabel from pg_enum
                        where enumtypid = 'git.repository_content_type'::regtype
                        order by enumsortorder""".trimIndent()
                ).use { rows ->
                    val labels = buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                    assertEquals(
                        listOf("general", "script_project", "documentation", "analytic_query_project", "agent_project", "pipeline_project"),
                        labels,
                    )
                }
            }
        }
    }
}
