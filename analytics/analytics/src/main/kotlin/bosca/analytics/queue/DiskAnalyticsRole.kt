package bosca.analytics.queue

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Lifetime ownership of one role; producer and consumer can be separate processes. */
internal class DiskAnalyticsRole(directory: Path, role: String) : Closeable {
    private val channel = FileChannel.open(directory.resolve("$role.lock"), CREATE, WRITE)
    private val lock: FileLock = try {
        channel.tryLock() ?: error("Analytics spool already has a $role: $directory")
    } catch (e: Exception) { channel.close(); throw e }
    override fun close() { try { lock.release() } finally { channel.close() } }
}
