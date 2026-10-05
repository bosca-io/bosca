package yks.lib0

/** Generate a random unsigned 32-bit integer. */
expect fun randomUint32(): Int

/** Generate a random UUID v4 string. */
expect fun randomUuid(): String

/** Current time in milliseconds since epoch. */
expect fun currentTimeMillis(): Long
