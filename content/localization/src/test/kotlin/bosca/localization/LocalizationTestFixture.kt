@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.localization

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.localization.repository.LocalizationMigration
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.uuid.ExperimentalUuidApi

/**
 * Shared Postgres + migrations fixture for the localization module's integration tests.
 *
 * Starts a single TestContainers Postgres per JVM (so the ~5s container startup cost is
 * paid once rather than per test class), applies the localization migration plus the
 * minimum set of `public` objects the localization schema depends on
 * (`languages` and `groups`), and exposes a [withDb] helper that binds a fresh
 * connection context for each test method.
 *
 * The test fixture deliberately does *not* run the full CoreMigration: doing so would
 * pull in every `public` table in the product and dramatically slow each test. Instead
 * the subset of tables localization's foreign keys reference is created directly here,
 * keeping each test round-trip under a second on a warm container.
 */
object LocalizationTestFixture {

    val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
        withDatabaseName("bosca_loc_test")
        withReuse(true)
        start()
    }

    val pool: ConnectionPool = ConnectionPool(
        ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password,
                maxConnections = 5
            ),
            key = "localization-test"
        )
    )

    private var schemaInitialized = false

    /**
     * Idempotently installs the prerequisite public schema objects and runs the
     * localization migration. Safe to call from every test class's `@BeforeTest` —
     * the first call does the work, subsequent calls are a no-op.
     */
    fun ensureSchema() {
        if (schemaInitialized) return
        withDb {
            // languages is referenced by localization.projects.source_language and
            // by translations.language_tag. The schema mirrors the public.languages
            // definition in CoreMigration V68.
            connection().useStatement(
                "create table if not exists public.languages (tag varchar primary key, name varchar not null, localName varchar not null, attributes json)"
            ) { it.execute() }

            // groups is referenced by the auto-created project-scoped translator groups.
            // Only the columns the localization code touches are required here.
            connection().useStatement(
                "create table if not exists public.groups (id uuid primary key default gen_random_uuid(), name varchar not null unique, description varchar not null default '', type varchar not null default 'SYSTEM')"
            ) { it.execute() }

            // principals: referenced only loosely by translations.created_by; no FK,
            // but a table keeps hand-written inserts in tests realistic.
            connection().useStatement(
                "create table if not exists public.principals (id uuid primary key default gen_random_uuid())"
            ) { it.execute() }
        }
        runBlocking {
            FlywayMigration(pool).migrate(listOf(LocalizationMigration()))
        }
        schemaInitialized = true
    }

    /**
     * Rebinds the shared connection pool into [ProviderRegistry] and clears every
     * row the localization tests touch. Call from `@BeforeTest`.
     */
    val cacheManager: CacheManager = InMemoryCacheManager()

    fun reset() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) {
            object : RequestCacheSerializer {
                override fun serialize(value: Any?): String? = value?.toString()
                override fun deserialize(value: String?): Any? = value
            }
        }
        runBlocking {
            // Clear all caches between tests so cached permission lists from a prior
            // test's project don't leak into the new one.
            cacheManager.clearAll()
        }
        withDb {
            transaction {
                // Child tables cascade from projects; truncate the top-level tables and
                // let the cascading FK definitions handle children.
                connection().useStatement("delete from localization.translation_history") { it.execute() }
                connection().useStatement("delete from localization.sync_state") { it.execute() }
                connection().useStatement("delete from localization.document_translations") { it.execute() }
                connection().useStatement("delete from localization.project_documents") { it.execute() }
                connection().useStatement("delete from localization.plural_translations") { it.execute() }
                connection().useStatement("delete from localization.translations") { it.execute() }
                connection().useStatement("delete from localization.strings") { it.execute() }
                connection().useStatement("delete from localization.project_permissions") { it.execute() }
                connection().useStatement("delete from localization.projects") { it.execute() }
                connection().useStatement("delete from public.languages") { it.execute() }
                connection().useStatement("delete from public.groups where name like 'translator-%'") { it.execute() }
            }
        }
    }

    /** Inserts a row into `public.languages`. Tests that reference a language tag call this first. */
    fun seedLanguage(tag: String, name: String = tag, localName: String = name) {
        withDb {
            connection().useStatement(
                "insert into public.languages (tag, name, localName) values (?, ?, ?) on conflict (tag) do nothing"
            ) {
                it.setString(1, tag)
                it.setString(2, name)
                it.setString(3, localName)
                it.execute()
            }
        }
    }

    /**
     * Runs [block] with a connection and a fresh [RequestCache] bound to the coroutine
     * context. Services built on top of `ServiceCache` (like `LocalizationServiceImpl`'s
     * permission cache) read the request cache from the context on every get/put/remove,
     * so tests that exercise caching need one present or every cache operation throws.
     */
    fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            val requestCache = RequestCache(
                cacheManager,
                object : RequestCacheSerializer {
                    override fun serialize(value: Any?): String? = value?.toString()
                    override fun deserialize(value: String?): Any? = value
                }
            )
            try {
                withContext(manager.asCoroutineContext() + requestCache.asCoroutineContext()) {
                    block()
                }
            } finally {
                withContext(NonCancellable) {
                    manager.release()
                }
            }
        }
    }

    /**
     * `withDb` specialization that returns a value. Convenient for test bodies that
     * want to run a suspending operation and assert on its result outside the block.
     */
    fun <T> withDbResult(block: suspend () -> T): T {
        var result: T? = null
        withDb { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
