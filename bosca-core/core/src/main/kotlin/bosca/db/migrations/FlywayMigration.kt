package bosca.db.migrations

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

class FlywayMigration(
    private val connectionPool: ConnectionPool,
) : Migrations {

    private suspend fun ConnectionManager.execute(sql: String): Boolean =
        useStatement(sql) {
            it.executeQuery().use {
                it.next() && it.getInt(1) == 1
            }
        }

    override suspend fun migrate(migrations: List<Migration>) = withTimeout(300_000.milliseconds) {
        // Ensure core (public schema) migrations run before module-specific migrations
        val sorted = migrations.sortedBy { if (it.schema == "public") 0 else 1 }
        val shouldBaseLine = connectionPool.connection().use { connection ->
            // Bosca Server 1.x introduces Flyway to manage database migrations. To ensure a smooth upgrade
            // for existing production instances, we must establish a baseline for databases created with
            // pre-1.x versions. This prevents Flyway from re-applying scripts that have already been run.
            //
            // The server determines the required action based on the presence of the `principals` table (indicating a
            // pre-1.x schema) and the `flyway_schema_history` table:
            //
            // * Baseline Required: If the `principals` table exists but the `flyway_schema_history` table does not,
            //   the server will apply a baseline.
            // * No Baseline Required: If neither table exists, Flyway will initialize the database from scratch.
            // * Maybe Baseline: If both tables exist, Flyway is already managing the schema and we can leverage
            //   the current state of the database to know if a baseline is needed.

            val hasPrincipalsTable = connection.execute("select 1 from pg_tables where schemaname = 'public' and tablename = 'principals'")
            val hasFlywayTable = connection.execute("select 1 from pg_tables where schemaname = 'public' and tablename = 'flyway_schema_history'")
            if (hasFlywayTable && hasPrincipalsTable) {
                connection.execute("select 1 from flyway_schema_history where type = 'BASELINE'")
            } else if (hasPrincipalsTable) {
                true
            } else {
                false
            }
        }

        val (publicMigrations, nonPublicMigrations) = sorted.partition { it.schema == "public" }

        for (migration in publicMigrations) {
            executeMigration(migration, shouldBaseLine)
        }

        log.info("Public schema migrations complete, running {} module migrations in dependency order", nonPublicMigrations.size)

        val remaining = nonPublicMigrations.toMutableList()
        val completed = mutableSetOf("public")

        // A migration may depend on a schema this application does not manage (e.g. the
        // analytics chain depends on `scheduler`, which the analytics collector never
        // registers). Such a dependency is considered satisfied when another application
        // sharing the database has already created the schema.
        val managedSchemas = nonPublicMigrations.mapTo(mutableSetOf()) { it.schema }
        completed.addAll(existingSchemas() - managedSchemas)

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { m -> m.dependsOn.all { it in completed } }
            if (ready.isEmpty()) {
                val stuck = remaining.map { "${it.schema} -> ${it.dependsOn}" }
                log.error("Skipping migrations with circular or unresolvable dependencies, they will be retried on the next startup: {}", stuck)
                break
            }
            remaining.removeAll(ready)
            coroutineScope {
                ready.map { migration ->
                    async(Dispatchers.IO) {
                        executeMigration(migration, shouldBaseLine)
                    }
                }.awaitAll()
            }
            completed.addAll(ready.map { it.schema })
        }
        Unit
    }

    /**
     * Returns every schema that already exists in the database, regardless of which
     * application created it. Used to satisfy [Migration.dependsOn] entries whose
     * schema is managed by a different application sharing the same database.
     */
    private suspend fun existingSchemas(): Set<String> =
        connectionPool.connection().use { connection ->
            connection.useStatement("select nspname from pg_namespace") {
                it.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) {
                            add(resultSet.getString(1))
                        }
                    }
                }
            }
        }

    /**
     * Configures and executes a Flyway migration for a single schema.
     *
     * Each invocation creates an independent Flyway instance with its own JDBC connection,
     * making it safe to call concurrently for different schemas.
     */
    private fun executeMigration(migration: Migration, shouldBaseLine: Boolean) {
        val classLoader = migration.javaClass.classLoader
        val resources = migration.resources
        val flyway = connectionPool.setDataSource(Flyway.configure())
            .resourceProvider(object : ResourceProvider {
                override fun getResource(name: String) = if (resources.contains(name)) NameLoadableResource(name, classLoader) else null
                override fun getResources(prefix: String?, suffixes: Array<out String?>?): Collection<LoadableResource> {
                    val migrations = resources.associateBy {
                        val v = it.substring(1).split("__")[0]
                        v.toInt()
                    }
                    return migrations.keys.sortedBy { it }.map {
                        NameLoadableResource(migrations.getValue(it), classLoader)
                    }.toList()
                }
            })
            .schemas(migration.schema)
            .baselineOnMigrate(true)
            .baselineDescription("Bosca Server 1.x Baseline")
            .baselineVersion(if (shouldBaseLine && migration.schema == "public") "58" else "0")
            .load()

        try {
            flyway.migrate()
        } catch (e: Exception) {
            log.error("Error migrating database: ${migration.schema}", e)
            throw e
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(FlywayMigration::class.java)
    }
}

private class NameLoadableResource(private val name: String, private val classLoader: ClassLoader) : LoadableResource() {
    override fun read() = classLoader.getResourceAsStream("db/migrations/$name")?.reader(Charsets.UTF_8)
    override fun getAbsolutePath() = ""
    override fun getAbsolutePathOnDisk() = ""
    override fun getFilename() = name
    override fun getRelativePath() = ""
}
