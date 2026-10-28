package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.annotation.InternalDI
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Uses real archive rows, migrations and restore conflict handling, including absent historical IDs. */
@OptIn(InternalDI::class)
class MetadataVersionRestoreIntegrationTest {

    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var fixture: BackupExecutorFixture
    private val id = UUID.randomUUID()

    @BeforeTest
    fun setup() = runBlocking {
        postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply { start() }
        val pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password), "version-restore",
        ))
        fixture = BackupExecutorFixture(pool)
        FlywayMigration(pool).migrate(listOf(CoreMigration()))
    }

    @AfterTest
    fun teardown() = runBlocking {
        try {
            if (::fixture.isInitialized) fixture.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `archive restore skip and overwrite distinguish supplementary IDs with shared keys`() = runBlocking {
        sql("""
            insert into traits (id, name, description) values ('version-trait', 'Trait', 'Trait');
            insert into metadata (id, version, name, content_type) values ('$id', 6, 'Original', 'image/png');
            insert into metadata_supplementary (metadata_id, key, name, content_type) values
                ('$id', 'thumbnail', 'First', 'image/png'), ('$id', 'thumbnail', 'Second', 'image/png');
            insert into metadata_supplementary_traits (id, trait_id)
                select id, 'version-trait' from metadata_supplementary where metadata_id = '$id';
            update metadata set version = 7 where id = '$id';
            insert into metadata_versions (id, version, name, content_type) values ('$id', 5, 'Legacy', 'image/png');
            insert into metadata_versions_supplementary (metadata_id, version, key, name, content_type)
                values ('$id', 5, 'thumbnail', 'Legacy', 'image/png');
            insert into metadata_version_supplementary_traits (metadata_id, version, key, trait_id)
                values ('$id', 5, 'thumbnail', 'version-trait');
        """.trimIndent())
        val tables = listOf("metadata_versions_supplementary", "metadata_version_supplementary_traits")
        val data = tables.associateWith { table ->
            query("select to_jsonb(t)::text from $table t order by to_jsonb(t)::text").joinToString("\n")
        }
        sql("delete from metadata_version_supplementary_traits; delete from metadata_versions_supplementary")

        repeat(2) { fixture.restore(data = data) }
        for (table in tables) assertEquals(data.getValue(table).lines(), records(table))

        sql("update metadata_versions_supplementary set name = 'Changed'")
        fixture.restore(data = data, conflictStrategy = ConflictStrategy.OVERWRITE)
        for (table in tables) assertEquals(data.getValue(table).lines(), records(table))
        assertEquals(listOf("3"), query("select count(*)::text from metadata_versions_supplementary"))
        assertEquals(listOf("3"), query("select count(*)::text from metadata_version_supplementary_traits"))
    }

    private fun records(table: String) = query("select to_jsonb(t)::text from $table t order by to_jsonb(t)::text")

    private fun sql(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun query(sql: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
}
