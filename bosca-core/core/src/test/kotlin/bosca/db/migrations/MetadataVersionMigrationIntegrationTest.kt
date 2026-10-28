package bosca.db.migrations

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.use
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetadataVersionMigrationIntegrationTest {

    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private val id = UUID.randomUUID()

    @BeforeTest
    fun setup() = runBlocking {
        postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withUsername("test")
            withPassword("test")
            withDatabaseName("test")
            withReuse(true)
            start()
        }
        pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
                key = "metadata-version-migration",
            ),
        )
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = "public"
            override val resources = CoreMigration().resources.filter { it != REPAIR }
        }))
        sql("insert into metadata (id, version, name, content_type) values ('$id', 6, 'Original', 'image/png')")
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::pool.isInitialized) pool.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `version advancement retains every existing archive field and leaves access controls on the owner`() = runBlocking {
        migrate()
        val parent = UUID.randomUUID()
        sql("""
            insert into metadata (id, name, content_type) values ('$parent', 'Parent', 'image/png');
            insert into workflows (id, name, description, queue)
                values ('delete-original', 'Delete', 'Delete original', 'default');
            update metadata set parent_id = '$parent', name = 'Before', type = 'variant', content_length = 42,
                language_tag = 'fr', labels = array['one', 'two'], attributes = '{"title":"old"}',
                system_attributes = '{"system":true}', created = '2024-01-02T03:04:05Z',
                modified = '2025-02-03T04:05:06Z', workflow_state_id = 'published',
                workflow_state_pending_id = 'pending', source_id = (select id from sources limit 1),
                source_identifier = 'source-original', delete_workflow_id = 'delete-original',
                public = true, public_content = true, public_supplementary = true, permission_mutation = 9
            where id = '$id';
            insert into metadata_permissions (metadata_id, group_id, action)
                select '$id', id, 'view' from groups limit 1;
        """.trimIndent())
        val fields = "id, version, parent_id, name, type, content_type, content_length, language_tag, labels, " +
            "attributes, system_attributes, created, modified, workflow_state_id, workflow_state_pending_id, " +
            "source_id, source_identifier, delete_workflow_id"
        val before = rows("select $fields from metadata where id = '$id'")
        val controls = ownerControls()
        val permissions = rows("select * from metadata_permissions where metadata_id = '$id'")

        sql("update metadata set version = 7, name = 'After', language_tag = 'en', labels = '{}' where id = '$id'")

        assertEquals(before, rows("select $fields from metadata_versions where id = '$id' and version = 6"))
        assertEquals(listOf("7"), values("select version::text from metadata where id = '$id'"))
        assertEquals(controls, ownerControls())
        assertEquals(permissions, rows("select * from metadata_permissions where metadata_id = '$id'"))
    }

    @Test
    fun `traits belong to the archived version`() = runBlocking {
        migrate()
        seedTrait()
        sql("insert into metadata_traits (metadata_id, trait_id) values ('$id', 'version-trait')")
        advance()
        assertEquals(listOf("6:version-trait"), values(
            "select version::text || ':' || trait_id from metadata_version_traits where metadata_id = '$id'",
        ))
        assertEquals(emptyList(), rows("select * from metadata_traits where metadata_id = '$id'"))
    }

    @Test
    fun `profile relationships belong to the archived version`() = runBlocking {
        migrate()
        val profile = UUID.randomUUID()
        sql("""
            insert into profiles (id, name) values ('$profile', 'Author');
            insert into metadata_profiles (metadata_id, profile_id, relationship, sort)
                values ('$id', '$profile', 'author', 3);
        """.trimIndent())
        advance()
        assertEquals(listOf("6:$profile:author:3"), values(
            "select version::text || ':' || profile_id || ':' || relationship || ':' || sort " +
                "from metadata_version_profiles where metadata_id = '$id'",
        ))
        assertEquals(emptyList(), rows("select * from metadata_profiles where metadata_id = '$id'"))
    }

    @Test
    fun `categories belong to the archived version`() = runBlocking {
        migrate()
        val category = UUID.randomUUID()
        sql("""
            insert into categories (id, name) values ('$category', 'Category');
            insert into metadata_categories (metadata_id, category_id) values ('$id', '$category');
        """.trimIndent())
        advance()
        assertEquals(listOf("6:$category"), values(
            "select version::text || ':' || category_id from metadata_version_categories where metadata_id = '$id'",
        ))
        assertEquals(emptyList(), rows("select * from metadata_categories where metadata_id = '$id'"))
    }

    @Test
    fun `supplementary identities fields and traits survive repeated versions and shared keys`() = runBlocking {
        migrate()
        seedTrait()
        val fields = "id, metadata_id, key, name, plan_id, job_id, attributes, content_type, content_length, " +
            "created, modified, source_id, source_identifier, uploaded"
        for (version in 6..7) {
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            sql("""
                insert into metadata_supplementary
                    (id, metadata_id, key, name, plan_id, job_id, attributes, content_type, content_length,
                     created, modified, source_id, source_identifier, uploaded)
                values
                    ('$first', '$id', 'thumbnail', 'Uploaded', '${UUID.randomUUID()}', '${UUID.randomUUID()}',
                     '{"width":42}', 'image/png', 12, '2024-01-02T03:04:05Z', '2025-02-03T04:05:06Z',
                     (select id from sources limit 1), 'original', '2025-02-03T04:05:06Z'),
                    ('$second', '$id', 'thumbnail', 'Pending', null, null, null, 'image/png', null,
                     '2024-01-02T03:04:05Z', '2025-02-03T04:05:06Z', null, null, null);
                insert into metadata_supplementary_traits (id, trait_id) values
                    ('$first', 'version-trait'), ('$second', 'version-trait');
            """.trimIndent())
            val before = rows("select $fields from metadata_supplementary where metadata_id = '$id' order by id")

            sql("update metadata set version = ${version + 1} where id = '$id'")

            assertEquals(before, rows(
                "select $fields from metadata_versions_supplementary where metadata_id = '$id' " +
                    "and version = $version order by id",
            ))
            assertEquals(setOf("$first:version-trait", "$second:version-trait"), values(
                "select id::text || ':' || trait_id from metadata_version_supplementary_traits " +
                    "where metadata_id = '$id' and version = $version",
            ).toSet())
            assertEquals(emptyList(), rows("select * from metadata_supplementary where metadata_id = '$id'"))
            assertEquals(emptyList(), values("select id::text from metadata_supplementary_traits"))
        }
        assertEquals(listOf("4"), values(
            "select count(*)::text from metadata_versions_supplementary where metadata_id = '$id'",
        ))
        sql("delete from metadata where id = '$id'")
        assertArchivesEmpty()
    }

    @Test
    fun `ordinary updates keep current relationships without creating an archive`() = runBlocking {
        migrate()
        seedAttachment()
        val before = rows("select * from metadata_supplementary where metadata_id = '$id'")
        sql("update metadata set name = 'Renamed' where id = '$id'")
        assertEquals(before, rows("select * from metadata_supplementary where metadata_id = '$id'"))
        assertEquals(emptyList(), rows("select * from metadata_versions where id = '$id'"))
    }

    @Test
    fun `rollback restores current attachments and removes all archive writes`() = runBlocking {
        migrate()
        seedAttachment()
        val before = rows("select * from metadata_supplementary where metadata_id = '$id'")
        pool.connection().use { manager ->
            manager.beginTransaction()
            try {
                manager.useStatement("update metadata set version = 7 where id = '$id'") { it.execute() }
                assertEquals(listOf("6"), values(manager, "select version::text from metadata_versions where id = '$id'"))
            } finally {
                manager.rollbackTransaction()
            }
        }
        assertEquals(before, rows("select * from metadata_supplementary where metadata_id = '$id'"))
        assertEquals(listOf("6"), values("select version::text from metadata where id = '$id'"))
        assertArchivesEmpty()
        assertEquals(listOf("version-trait"), values("select trait_id from metadata_supplementary_traits"))
    }

    @Test
    fun `failure after the trigger leaves the source and archives unchanged`() = runBlocking {
        migrate()
        seedAttachment()
        val before = rows("select * from metadata_supplementary where metadata_id = '$id'")
        sql("alter table metadata add constraint test_version_check check (version < 7)")
        val failure = assertFailsWith<SQLException> { advance() }
        assertEquals("23514", failure.sqlState)
        assertEquals(before, rows("select * from metadata_supplementary where metadata_id = '$id'"))
        assertEquals(listOf("6"), values("select version::text from metadata where id = '$id'"))
        assertArchivesEmpty()
        assertEquals(listOf("version-trait"), values("select trait_id from metadata_supplementary_traits"))
    }

    @Test
    fun `upgrade preserves retained records without fabricating supplementary identities`() = runBlocking {
        seedTrait()
        sql("""
            insert into metadata_versions (id, version, name, content_type) values ('$id', 5, 'Retained', 'image/png');
            insert into metadata_versions_supplementary (metadata_id, version, key, name, content_type, content_length)
                values ('$id', 5, 'thumbnail', 'Retained thumbnail', 'image/png', 5);
            insert into metadata_version_supplementary_traits (metadata_id, version, key, trait_id)
                values ('$id', 5, 'thumbnail', 'version-trait');
        """.trimIndent())
        val before = rows("select * from metadata_versions_supplementary where metadata_id = '$id'")
        val traits = rows("select * from metadata_version_supplementary_traits where metadata_id = '$id'")

        migrate()

        assertEquals(before, values("""
            select (to_jsonb(s) - array['id', 'plan_id', 'job_id', 'attributes'])::text
            from metadata_versions_supplementary s where metadata_id = '$id'
        """.trimIndent()))
        assertEquals(traits, values("""
            select (to_jsonb(t) - 'id')::text from metadata_version_supplementary_traits t where metadata_id = '$id'
        """.trimIndent()))
        assertEquals(listOf("true"), values("select (id is null)::text from metadata_versions_supplementary where metadata_id = '$id'"))
        seedAttachment()
        advance()
        assertEquals(listOf("2"), values("select count(*)::text from metadata_versions_supplementary where metadata_id = '$id'"))
    }

    private suspend fun migrate() = FlywayMigration(pool).migrate(listOf(CoreMigration()))

    private suspend fun advance() = sql("update metadata set version = 7 where id = '$id'")

    private suspend fun ownerControls() = rows(
        "select public, public_content, public_supplementary, permission_mutation from metadata where id = '$id'",
    )

    private suspend fun seedTrait() = sql(
        "insert into traits (id, name, description) values ('version-trait', 'Version trait', 'Trait')",
    )

    private suspend fun seedAttachment() {
        sql("""
            insert into traits (id, name, description) values ('version-trait', 'Version trait', 'Trait') on conflict do nothing;
            insert into metadata_supplementary (metadata_id, key, name, content_type)
                values ('$id', 'thumbnail', 'Thumbnail', 'image/png');
            insert into metadata_supplementary_traits (id, trait_id)
                select id, 'version-trait' from metadata_supplementary where metadata_id = '$id';
        """.trimIndent())
    }

    private suspend fun assertArchivesEmpty() {
        for (table in listOf("metadata_versions", "metadata_versions_supplementary", "metadata_version_supplementary_traits")) {
            assertTrue(rows("select * from $table").isEmpty(), table)
        }
    }

    private suspend fun sql(sql: String) = pool.connection().use { manager ->
        manager.useStatement(sql) { it.execute() }
        Unit
    }

    private suspend fun rows(query: String): List<String> = values("select to_jsonb(t)::text from ($query) t")

    private suspend fun values(query: String): List<String> = pool.connection().use { values(it, query) }

    private suspend fun values(manager: ConnectionManager, query: String): List<String> = manager.useStatement(query) { statement ->
        statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
    }

    private companion object {
        const val REPAIR = "V200__metadata_version_archives.sql"
    }
}
