package bosca.backup

import bosca.server.BoscaApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Checks and restores an operator-owned, offline destination through its independent libpq service. */
internal class PostgresBackupRestore(private val application: BoscaApplication) {
    suspend fun preflight(manifest: PostgresBackupManifest, directory: Path? = null): PostgresBackupManifest =
        execute(directory, manifest, restore = false)

    suspend fun restore(directory: Path, manifest: PostgresBackupManifest): PostgresBackupManifest =
        execute(directory, manifest, restore = true)

    private suspend fun execute(directory: Path?, manifest: PostgresBackupManifest, restore: Boolean): PostgresBackupManifest {
        currentCoroutineContext().ensureActive()
        val config = application.environment.config
        checkPostgresBackupKeys(manifest, config)
        fun required(key: String): String {
            val value = checkNotNull(config.propertyOrNull("backup.postgres.restore.$key")) {
                "Missing backup.postgres.restore.$key configuration"
            }.getString()
            require(value.isNotBlank()) { "backup.postgres.restore.$key must not be blank" }
            return value
        }
        fun optional(key: String, fallback: String? = null): String? {
            val value = config.propertyOrNull("backup.postgres.restore.$key")?.getString() ?: fallback
            require(value == null || value.isNotBlank()) { "backup.postgres.restore.$key must not be blank" }
            return value
        }
        val service = required("service")
        val database = required("database")
        val serviceFile = optional("serviceFile")
        val psql = checkNotNull(optional("psql", "psql"))
        val escapedService = service.replace("\\", "\\\\").replace("'", "\\'")
        val connection = "--dbname=service='$escapedService'"
        currentCoroutineContext().ensureActive()
        val scratch = Files.createTempDirectory("bosca-postgres-restore-")
        var failure: Throwable? = null
        try {
            runPostgresBackupProcess(
                "psql restore preflight", psql,
                listOf(
                    "--no-psqlrc", "--no-password", "--set=ON_ERROR_STOP=1", connection,
                    "--command=${preflight(database, manifest)}",
                ),
                serviceFile, scratch.resolve("psql.stderr"),
            )
            if (directory != null) {
                val pgRestore = checkNotNull(optional("pgRestore", "pg_restore"))
                val archive = directory.toAbsolutePath().resolve(manifest.payloadPath).toString()
                runPostgresBackupProcess(
                    "pg_restore archive preflight", pgRestore, listOf("--list", archive),
                    serviceFile, scratch.resolve("pg_restore.stderr"),
                )
                if (restore) {
                    runPostgresBackupProcess(
                        "pg_restore", pgRestore,
                        listOf(
                            "--no-password", connection, "--single-transaction", "--exit-on-error",
                            "--no-owner", "--no-acl", "--no-tablespaces", archive,
                        ),
                        serviceFile, scratch.resolve("pg_restore.stderr"),
                    )
                }
            }
            return manifest
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    Files.deleteIfExists(scratch.resolve("psql.stderr"))
                    Files.deleteIfExists(scratch.resolve("pg_restore.stderr"))
                    Files.delete(scratch)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    private fun preflight(database: String, manifest: PostgresBackupManifest): String {
        fun literal(value: String) = "'${value.replace("'", "''")}'"
        val extensions = manifest.extensions.entries.joinToString("\n") { (name, version) ->
            """
            if not exists (select 1 from pg_available_extensions
                           where name = ${literal(name)} and default_version = ${literal(version)}) then
                raise exception 'Required PostgreSQL extension default version is unavailable';
            end if;
            """.trimIndent()
        }
        val body = """
            begin
                if current_database() <> ${literal(database)} then
                    raise exception 'Unexpected restore destination database';
                end if;
                if pg_is_in_recovery() then
                    raise exception 'Restore destination must be writable';
                end if;
                if current_setting('server_version_num')::integer / 10000 < ${manifest.serverVersionNumber / 10000}
                   or current_setting('server_encoding') <> ${literal(manifest.encoding)} then
                    raise exception 'Incompatible restore destination PostgreSQL version or encoding';
                end if;
                if exists (select 1 from pg_namespace
                           where nspname not in ('public', 'information_schema') and nspname !~ '^pg_')
                   or exists (select 1 from pg_depend d join pg_namespace n on n.oid = d.refobjid
                              where d.refclassid = 'pg_namespace'::regclass and n.nspname = 'public')
                   or exists (select 1 from pg_extension where extname <> 'plpgsql')
                   or exists (select 1 from pg_largeobject_metadata)
                   or exists (select 1 from pg_publication)
                   or exists (select 1 from pg_subscription)
                   or exists (select 1 from pg_default_acl)
                   or exists (select 1 from pg_foreign_server)
                   or exists (select 1 from pg_event_trigger) then
                    raise exception 'Restore destination database must be empty';
                end if;
                $extensions
            end
        """.trimIndent()
        var tag = "bosca"
        while (body.contains("${'$'}$tag${'$'}")) tag += "_"
        val delimiter = "${'$'}$tag${'$'}"
        return "begin read only; set local standard_conforming_strings = on; do $delimiter\n$body\n$delimiter; rollback;"
    }
}
