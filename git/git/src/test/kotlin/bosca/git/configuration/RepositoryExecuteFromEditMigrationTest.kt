package bosca.git.configuration

import bosca.test.resources.SharedPostgreSQLContainer
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the EDIT/MANAGE → EXECUTE repository grant backfill against the real permission enum. */
class RepositoryExecuteFromEditMigrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("repository_execute_from_edit_test")
            withReuse(true)
            start()
        }
    }

    @Test
    fun `grants execute to every group holding edit or manage and is idempotent`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("drop schema if exists git cascade")
                statement.execute("drop type if exists permission_action cascade")
                statement.execute(
                    "create type permission_action as enum ('view', 'edit', 'delete', 'manage', 'list', 'execute', 'impersonate')"
                )
                statement.execute("create schema git")
                statement.execute(
                    """create table git.repository_permissions (
                        repository_id integer not null,
                        group_id integer not null,
                        action permission_action not null,
                        primary key (repository_id, group_id, action)
                    )""".trimIndent()
                )
                statement.execute(
                    """insert into git.repository_permissions (repository_id, group_id, action) values
                        (1, 10, 'edit'),
                        (1, 11, 'view'),
                        (1, 12, 'manage'),
                        (2, 10, 'edit'),
                        (2, 10, 'execute')""".trimIndent()
                )

                val sql = javaClass.getResource("/db/migrations/V42__repository_execute_from_edit.sql")
                    ?.readText() ?: error("EDIT to EXECUTE migration missing")
                statement.execute(sql)
                val afterFirst = grants(statement)
                statement.execute(sql)

                assertEquals(
                    listOf(
                        "1:10:edit", "1:10:execute", "1:11:view", "1:12:execute", "1:12:manage",
                        "2:10:edit", "2:10:execute",
                    ),
                    afterFirst,
                )
                assertEquals(afterFirst, grants(statement))
            }
        }
    }

    private fun grants(statement: Statement): List<String> =
        statement.executeQuery(
            "select repository_id, group_id, action::text from git.repository_permissions order by 1, 2, 3"
        ).use { rows ->
            buildList {
                while (rows.next()) add("${rows.getInt(1)}:${rows.getInt(2)}:${rows.getString(3)}")
            }
        }
}
