package bosca.atlas.server

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns one read-only blocking query so cancellation/termination never targets application work. */
internal class AtlasPostgresSession(private val connection: Connection) : AutoCloseable {
    val pid: Int = connection.createStatement().use { statement -> statement.executeQuery("select pg_backend_pid()").use { result -> check(result.next()); result.getInt(1) } }
    private val executor = Executors.newSingleThreadExecutor { task -> Thread.ofPlatform().daemon().name("atlas-postgres-probe-$pid").unstarted(task) }
    private val result = executor.submit<String?> {
        try {
            connection.createStatement().use { it.execute("select pg_sleep(600) from public.system_ui_console_probe where id = 1 for share") }
            null
        } catch (failure: SQLException) { failure.sqlState }
    }

    fun result(): String? = result.get(15, TimeUnit.SECONDS)
    override fun close() {
        connection.abort(Executor { it.run() })
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Diagnostic query did not stop" }
    }
}
