package bosca.lock.postgres

import bosca.db.ConnectionPool
import bosca.db.use
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory

class PostgresDistributedLockFactory(private val pool: ConnectionPool) : DistributedLockFactory {
    override suspend fun create(name: String): DistributedLock = PostgresDistributedLock(pool, name)
    override suspend fun forceRelease(name: String): Boolean = pool.connection().use { manager ->
        manager.useStatement("delete from infrastructure.locks where name = ?") { stmt -> stmt.setString(1, name); stmt.executeUpdate() > 0 }
    }
}
