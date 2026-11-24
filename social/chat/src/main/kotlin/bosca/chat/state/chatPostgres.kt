package bosca.chat.state

import bosca.db.*

internal suspend fun <T> withChatConnection(pool: ConnectionPool, block: suspend (ConnectionManager) -> T): T {
    val manager = connectionOrNull()
    return if (manager != null) block(manager) else pool.connection().use { block(it) }
}

internal suspend fun chatUpdate(pool: ConnectionPool, sql: String, vararg values: Any?): Int = withChatConnection(pool) { manager ->
    manager.useStatement(sql) { stmt ->
        values.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
        stmt.executeUpdate()
    }
}
