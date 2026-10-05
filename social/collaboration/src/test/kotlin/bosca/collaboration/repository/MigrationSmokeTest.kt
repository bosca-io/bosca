@file:OptIn(InternalDI::class)

package bosca.collaboration.repository

import bosca.chat.repository.ChatMigration
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots a real Postgres via TestContainers and runs the chat + collaboration
 * Flyway migrations against it. Asserts the resulting DDL: schemas exist,
 * tables exist with the expected columns, enums are populated, and the
 * migrations are idempotent under repeated invocation.
 *
 * The collaboration tables FK into `chat.channels` and `public.profiles`,
 * and the chat V1 migration moves a set of `public.chat_*` tables into the
 * `chat` schema. The fixture seeds those parents before letting Flyway run
 * so we're testing the migrations themselves, not a missing prerequisite.
 */
class MigrationSmokeTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_collab_migration_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 2,
                ),
                key = "collab-migration-test",
            )
        )

        private var initialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
    }

    @BeforeTest
    fun setup() {
        // Skip cleanly when Docker isn't available (CI sandboxes, dev machines
        // without Docker installed). The test still compiles and the body still
        // exercises real Flyway when run in a Docker-equipped environment.
        org.junit.Assume.assumeTrue(
            "Docker not available -- skipping migration smoke test",
            isDockerAvailable(),
        )
        if (!initialized) {
            ProviderRegistry.clear()
            provides<ConnectionPool>(singleton = true) { pool }
            seedPrerequisiteTables()
            initialized = true
        }
    }

    private fun isDockerAvailable(): Boolean = try {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }

    /**
     * Stands up the small set of `public.*` objects that the chat V1
     * migration's `alter table set schema` statements and the collaboration
     * FKs depend on. Mirrors the relevant subset of CoreMigration without
     * pulling in the rest of the product schema.
     */
    private fun seedPrerequisiteTables() {
        execStatements(
            """
            create extension if not exists "pgcrypto";
            create table if not exists public.profiles (
                id uuid not null primary key default gen_random_uuid()
            );
            create table if not exists public.groups (
                id uuid not null primary key default gen_random_uuid(),
                name varchar not null unique,
                description varchar not null default ''
            );
            do ${'$'}${'$'}
            begin
                if not exists (select 1 from pg_type where typname = 'permission_action') then
                    create type permission_action as enum (
                        'view', 'list', 'edit', 'manage', 'delete', 'execute', 'impersonate'
                    );
                end if;
                if not exists (select 1 from pg_type where typname = 'chat_channel_type') then
                    create type chat_channel_type as enum ('direct', 'group', 'public');
                end if;
            end ${'$'}${'$'};
            create table if not exists public.chat_channels (
                id uuid not null default gen_random_uuid() primary key,
                group_id uuid,
                name varchar not null,
                type chat_channel_type not null,
                attributes jsonb
            );
            create table if not exists public.chat_channel_members (
                channel_id uuid not null references public.chat_channels(id) on delete cascade,
                profile_id uuid not null references public.profiles(id) on delete cascade,
                role varchar not null,
                last_read_at timestamptz,
                last_read_sequence bigint,
                attributes jsonb,
                primary key (channel_id, profile_id)
            );
            create table if not exists public.chat_channel_permissions (
                channel_id uuid not null references public.chat_channels(id) on delete cascade,
                group_id uuid not null references public.groups(id) on delete cascade,
                action permission_action not null,
                primary key (channel_id, group_id, action)
            );
            """.trimIndent(),
        )
    }

    private fun execStatements(sql: String) {
        runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { it.execute() }
                }
            } finally {
                mgr.release()
            }
        }
    }

    private fun querySingleColumn(sql: String, column: String): List<String> {
        return runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt ->
                        val rs = stmt.executeQuery()
                        val results = mutableListOf<String>()
                        while (rs.next()) results.add(rs.getString(column))
                        results
                    }
                }
            } finally {
                mgr.release()
            }
        }
    }

    private fun querySingleLong(sql: String): Long {
        return runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt ->
                        val rs = stmt.executeQuery()
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            } finally {
                mgr.release()
            }
        }
    }

    private fun migrate(migrations: List<Migration>) {
        runBlocking { FlywayMigration(pool).migrate(migrations) }
    }

    @Test
    fun `chat migration moves channels into the chat schema`() {
        // Reactions are no longer a SQL table -- they live in the
        // chat-reactions NATS KV bucket -- so this test only asserts
        // the channel/membership/permission tables that V1 moves into
        // the chat schema, plus the V2 object-channel columns.
        migrate(listOf(ChatMigration()))

        val schemas = querySingleColumn(
            "select schema_name from information_schema.schemata where schema_name = 'chat'",
            "schema_name",
        )
        assertEquals(1, schemas.size, "chat schema should exist")

        val tables = querySingleColumn(
            "select table_name from information_schema.tables where table_schema = 'chat' order by table_name",
            "table_name",
        )
        assertTrue(tables.contains("channels"), "chat.channels missing -- got $tables")
        assertTrue(tables.contains("channel_members"), "chat.channel_members missing -- got $tables")
        assertTrue(tables.contains("channel_permissions"), "chat.channel_permissions missing -- got $tables")

        val channelColumns = querySingleColumn(
            "select column_name from information_schema.columns where table_schema = 'chat' and table_name = 'channels'",
            "column_name",
        )
        assertTrue(channelColumns.contains("object_type"), "chat.channels missing object_type -- got $channelColumns")
        assertTrue(channelColumns.contains("object_id"), "chat.channels missing object_id -- got $channelColumns")

        // V3 dropped the last_read_* columns when read state moved to the
        // chat-read-state NATS KV bucket; the columns must not exist.
        val memberColumns = querySingleColumn(
            "select column_name from information_schema.columns where table_schema = 'chat' and table_name = 'channel_members'",
            "column_name",
        )
        assertTrue(!memberColumns.contains("last_read_at"), "chat.channel_members.last_read_at should have been dropped -- got $memberColumns")
        assertTrue(!memberColumns.contains("last_read_sequence"), "chat.channel_members.last_read_sequence should have been dropped -- got $memberColumns")

    }

    @Test
    fun `collaboration migration creates schema with bridge and federation tables`() {
        migrate(listOf(ChatMigration(), CollaborationMigration()))

        val schemas = querySingleColumn(
            "select schema_name from information_schema.schemata where schema_name = 'collaboration'",
            "schema_name",
        )
        assertEquals(1, schemas.size, "collaboration schema should exist")

        val tables = querySingleColumn(
            "select table_name from information_schema.tables where table_schema = 'collaboration' order by table_name",
            "table_name",
        )
        assertTrue(tables.contains("bridge_bindings"), "missing bridge_bindings: $tables")
        assertTrue(tables.contains("bridge_identity_map"), "missing bridge_identity_map: $tables")
        assertTrue(tables.contains("bridge_message_map"), "missing bridge_message_map: $tables")
        assertTrue(tables.contains("federation_peers"), "missing federation_peers: $tables")
        assertTrue(tables.contains("federated_channels"), "missing federated_channels: $tables")
        assertTrue(tables.contains("federation_profiles"), "missing federation_profiles: $tables")

        val bindingColumns = querySingleColumn(
            "select column_name from information_schema.columns where table_schema = 'collaboration' and table_name = 'bridge_bindings'",
            "column_name",
        )
        assertTrue(bindingColumns.contains("bot_token_nonce"), "bridge_bindings missing bot_token_nonce -- got $bindingColumns")
        assertTrue(bindingColumns.contains("bot_token_data"), "bridge_bindings missing bot_token_data -- got $bindingColumns")
        assertTrue(bindingColumns.contains("webhook_url"), "bridge_bindings missing webhook_url -- got $bindingColumns")

        val peerColumns = querySingleColumn(
            "select column_name from information_schema.columns where table_schema = 'collaboration' and table_name = 'federation_peers'",
            "column_name",
        )
        assertTrue(peerColumns.contains("shared_secret_nonce"), "federation_peers missing shared_secret_nonce -- got $peerColumns")
        assertTrue(peerColumns.contains("shared_secret_data"), "federation_peers missing shared_secret_data -- got $peerColumns")

        val enumValues = querySingleColumn(
            "select unnest(enum_range(null::collaboration.bridge_platform))::text as v",
            "v",
        )
        assertEquals(listOf("slack", "teams"), enumValues)

        val syncDirections = querySingleColumn(
            "select unnest(enum_range(null::collaboration.federation_sync_direction))::text as v",
            "v",
        ).toSet()
        assertEquals(setOf("bidirectional", "inbound", "outbound"), syncDirections)
    }

    @Test
    fun `migrations are idempotent -- running them twice does not error or change state`() {
        migrate(listOf(ChatMigration(), CollaborationMigration()))
        val firstCount = querySingleLong(
            "select count(*) from information_schema.tables where table_schema = 'collaboration'",
        )
        migrate(listOf(ChatMigration(), CollaborationMigration()))
        val secondCount = querySingleLong(
            "select count(*) from information_schema.tables where table_schema = 'collaboration'",
        )
        assertEquals(firstCount, secondCount, "migration count should be stable on reapply")
        assertNotNull(firstCount.takeIf { it > 0 })
    }
}
