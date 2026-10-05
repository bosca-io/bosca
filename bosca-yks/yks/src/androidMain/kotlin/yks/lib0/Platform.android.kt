package yks.lib0

import java.security.SecureRandom
import java.util.UUID

private val secureRandom = SecureRandom()

actual fun randomUint32(): Int = secureRandom.nextInt()

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
